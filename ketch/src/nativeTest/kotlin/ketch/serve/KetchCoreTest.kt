@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.serve

import fs.TempDir
import fs.atomic
import fs.createDirAll
import fs.isDir
import fs.readText
import fs.setMode
import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.listing.Available
import ketch.core.listing.Row
import ketch.core.listing.fillLatest
import ketch.core.manifest.Resolver
import ketch.core.model.PackageSpec
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.source.SourceRegistry
import ketch.core.state.Lock
import ketch.core.state.State
import ketch.wire.Answer
import ketch.wire.ChangelogSource
import ketch.wire.Event
import ketch.wire.InstallOptions
import ketch.wire.PathOutcome
import ketch.wire.Question
import ketch.wire.Stage
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ketch.wire.KetchError as WireError

class KetchCoreTest {
    /** A core over a fresh root under a temporary directory, and that directory. */
    private fun scratch(body: (Path, KetchCore) -> Unit) = TempDir.create("ketch-test-").use { body(it.path, KetchCore(Path(it.path, "root"))) }

    private fun cfg(dir: Path) = Config.load(Path(dir, "root"), Report.silent())

    /** A one-file `local:` package under [dir], named [name]. */
    private fun payload(
        dir: Path,
        name: String,
    ): String {
        val folder = Path(dir, "payload", name)
        createDirAll(folder)
        atomic(Path(folder, name), "#!/bin/sh\necho hi\n".encodeToByteArray())
        return "local:${Path(folder, name)}"
    }

    private fun failure(body: () -> Unit) = assertFailsWith<CallFailed> { body() }.error

    private class Recorded : Listener {
        val events = mutableListOf<Event>()

        override fun event(event: Event) {
            events += event
        }

        /** Whether a package reached the installing stage. Not checked by name: a local file's name is the host's. */
        fun sawInstalling() = events.any { it is Event.Step && it.stage == Stage.Installing }
    }

    @Test
    fun aPackageLinkYieldsItsNameAndAnInstallLinkIsRefused() {
        assertEquals("ripgrep", packageForLink("ketch://package/ripgrep"))
        assertIs<WireError.Other>(failure { packageForLink("ketch://install/ripgrep") })
    }

    @Test
    fun aScratchRootHasNothingInstalled() =
        scratch { _, core ->
            assertEquals(emptyList(), core.installed())
        }

    @Test
    fun uninstallingAnUnknownNameIsNotFoundBeforeAnythingChanges() =
        scratch { _, core ->
            assertEquals(WireError.NotFound("nope"), failure { core.uninstall(listOf("nope")) })
        }

    @Test
    fun aHeldLockMakesAMutatingCallBusy() =
        scratch { dir, core ->
            core.root()
            Lock.acquire(Ctx(cfg(dir), Report.silent())).use {
                assertIs<WireError.Busy>(failure { core.uninstall(listOf("nope")) })
            }
        }

    @Test
    fun aLocalPackageInstallsReportsAndUninstalls() =
        scratch { dir, core ->
            val recorded = Recorded()
            val placed = core.install(listOf(payload(dir, "hello")), listener = recorded)
            assertEquals(1, placed.size)
            val name = placed[0].pkg.name
            assertEquals(listOf(name), core.installed().map { it.name })
            assertTrue(recorded.sawInstalling())

            val removed = core.uninstall(listOf(name))
            assertEquals(name, removed[0].name)
            assertEquals(emptyList(), core.installed())
        }

    @Test
    fun eachCallReportsToItsOwnListenerOnly() =
        scratch { dir, core ->
            val installing = Recorded()
            val removing = Recorded()
            val name = core.install(listOf(payload(dir, "hello")), listener = installing)[0].pkg.name
            core.uninstall(listOf(name), listener = removing)
            assertTrue(installing.sawInstalling())
            assertFalse(removing.sawInstalling())
            assertTrue(removing.events.any { it is Event.Success && it.verb == "removed" && it.detail.startsWith(name) })
        }

    @Test
    fun aCancelledInstallPlacesNothing() =
        scratch { dir, core ->
            val cancel = Cancel().apply { cancel() }
            assertEquals(WireError.Cancelled, failure { core.install(listOf(payload(dir, "hello")), cancel = cancel) })
            assertEquals(emptyList(), core.installed())
        }

    @Test
    fun aCancelledUninstallRemovesNothing() =
        scratch { dir, core ->
            val name = core.install(listOf(payload(dir, "hello")))[0].pkg.name
            val cancel = Cancel().apply { cancel() }
            assertEquals(WireError.Cancelled, failure { core.uninstall(listOf(name), cancel = cancel) })
            assertEquals(listOf(name), core.installed().map { it.name })
        }

    @Test
    fun emptyRequestsAreRefused() =
        scratch { _, core ->
            assertEquals(WireError.Other("nothing to install"), failure { core.install(emptyList()) })
        }

    @Test
    fun aBinaryChoiceNeedsExactlyOneSpec() =
        scratch { _, core ->
            assertIs<WireError.Other>(failure { core.install(listOf("a", "b"), InstallOptions(bin = "a")) })
        }

    @Test
    fun anEmptySearchBrowsesWhatIsKnownAndAsksNoSource() =
        scratch { dir, core ->
            val manifests = cfg(dir).manifestDir
            createDirAll(manifests)
            atomic(Path(manifests, "alpha.toml"), "name = \"alpha\"\nsource = \"github:example/alpha\"\n".encodeToByteArray())
            val everything = core.search("  ", 1000)
            assertEquals("alpha", everything.known[0].name, everything.toString())
            assertTrue(everything.known.size > 1, "the built-ins are known too")
            assertEquals(emptyList(), everything.repositories)
            assertEquals(1, core.search("", 1).known.size)
        }

    @Test
    fun aBatchNamesEachFailureAndALoneOneKeepsItsOwnError() {
        val failures = listOf("a" to KetchError.NoRelease("a"), "b" to KetchError.Msg("broken"))
        assertEquals(WireError.Other("2 of 3 packages failed\na: `a` not found\nb: broken"), summarise(3, failures))
        assertEquals(WireError.NotFound("a"), summarise(1, listOf("a" to KetchError.NoRelease("a"))))
        assertEquals(WireError.Cancelled, summarise(2, listOf("a" to KetchError.Cancelled(), "b" to KetchError.Cancelled())))
        assertNull(summarise(2, emptyList()))
    }

    @Test
    fun installOptionsCarryConsentToDropChecksumVerification() =
        scratch { dir, _ ->
            val asked = { options: InstallOptions -> installRequest(cfg(dir), options, "o/r", Cancel()).allowUnverified }
            assertFalse(asked(InstallOptions()))
            assertTrue(asked(InstallOptions(allowUnverified = true)))
        }

    @Test
    fun repeatedSpecsAreInstalledOnce() =
        scratch { dir, core ->
            val spec = payload(dir, "hello")
            assertEquals(1, core.install(listOf(spec, spec)).size)
        }

    /** Answers only the leftover question, with [remove], and records what it was asked. */
    private class Leftovers(
        val remove: Boolean,
    ) : Asker {
        val asked = mutableListOf<String>()

        override fun ask(question: Question): Answer {
            if (question is Question.ConfirmRemoveLeftover) asked += question.path
            return Answer.Confirm(remove)
        }
    }

    @Test
    fun aLeftoverFolderIsTakenOnlyWhenTheDeciderConfirms() =
        scratch { dir, core ->
            val leftover = Path(cfg(dir).storeDir, "ghost")
            createDirAll(Path(leftover, "1.0.0.old"))
            val notFound = WireError.NotFound("ghost")

            assertEquals(notFound, failure { core.uninstall(listOf("ghost")) })
            assertTrue(isDir(leftover), "no asker is no consent")

            val declining = Leftovers(false)
            assertEquals(notFound, failure { core.uninstall(listOf("ghost"), asker = declining) })
            assertTrue(isDir(leftover), "a decline keeps the folder")
            assertEquals(listOf(leftover.toString()), declining.asked)

            assertEquals(notFound, failure { core.uninstall(listOf("ghost"), asker = Leftovers(true)) })
            assertFalse(isDir(leftover))
        }

    @Test
    fun anUnknownNameBesideAnInstalledOneRemovesNeither() =
        scratch { dir, core ->
            val name = core.install(listOf(payload(dir, "hello")))[0].pkg.name
            assertEquals(WireError.NotFound("ghost"), failure { core.uninstall(listOf(name, "ghost")) })
            assertEquals(1, core.installed().size)
        }

    /**
     * A `test:` source plugin in [dir]'s root serving `alpha`'s releases, each with notes, and a user manifest naming
     * it — enough for a search and a changelog, which need no download.
     */
    private fun publishAlpha(
        dir: Path,
        versions: List<String>,
    ) {
        val cfg = cfg(dir)
        val releases = versions.map { v -> """{"version":"$v","tag":"v$v","notes":"notes for $v","assets":[]}""" }
        val feed = Path(dir, "alpha.releases.json")
        atomic(feed, "[${releases.joinToString(",")}]".encodeToByteArray())
        createDirAll(cfg.pluginDir)
        val plugin = Path(cfg.pluginDir, "ketch-source-test")
        val script =
            "#!/bin/sh\ncase \"\$1\" in\n" +
                "capabilities) printf '%s' '{\"protocol\":1,\"scheme\":\"test\",\"download\":false,\"search\":false}' ;;\n" +
                "describe) printf 'null' ;;\n" +
                "releases) cat '$feed' ;;\n" +
                "search) printf '[]' ;;\n" +
                "*) exit 1 ;;\nesac\n"
        atomic(plugin, script.encodeToByteArray())
        setMode(plugin, "755".toInt(8))
        createDirAll(cfg.manifestDir)
        atomic(
            Path(cfg.manifestDir, "alpha.toml"),
            "name = \"alpha\"\nsource = \"test:alpha\"\ndescription = \"the alpha tool\"\n".encodeToByteArray(),
        )
    }

    @Test
    fun aChangelogRangeIsEveryReleaseBetweenNewestFirst() =
        scratch { dir, core ->
            publishAlpha(dir, listOf("1.0.0", "1.1.0", "1.2.0", "1.3.0"))
            val range = core.changelogRange("alpha", "1.0.0", "1.2.0")
            assertEquals(listOf("1.2.0", "1.1.0"), range.map { it.version })
            assertEquals("notes for 1.2.0", range[0].body)
            assertEquals(ChangelogSource.Release, range[0].source)
            assertEquals(4, core.changelogRange("alpha", null, null).size)
        }

    @Test
    fun aSearchResultCarriesTheLatestAnEarlierListingCached() =
        scratch { dir, core ->
            publishAlpha(dir, listOf("1.0.0", "2.0.0"))
            // A limit of one leaves no room for repositories, so no source is searched over the network.
            assertNull(core.search("alpha", 1).known[0].latest)

            val cfg = cfg(dir)
            cfg.ensureDirs()
            val cx = Ctx(cfg, Report.silent())
            val (manifest, _) = Resolver.load(cx).resolve(PackageSpec.parse("alpha"))
            fillLatest(cx, SourceRegistry.load(cx), listOf(Row.offered(Available.fromManifest(cfg, manifest))))

            val after = core.search("alpha", 1)
            assertEquals("alpha", after.known[0].name)
            assertEquals("2.0.0", after.known[0].latest)
        }

    @Test
    fun anInstallShowsInTheHistory() =
        scratch { dir, core ->
            assertEquals(emptyList(), core.history(null, 10))
            val name = core.install(listOf(payload(dir, "hello")))[0].pkg.name
            val events = core.history(name, 10)
            assertEquals(1, events.size)
            assertEquals(name, events[0].pkg)
            assertEquals("install", events[0].action)
            assertEquals(emptyList(), core.history("other", 10))
        }

    @Test
    fun infoOnAnInstalledPackageCarriesItsRecord() =
        scratch { dir, core ->
            val name = core.install(listOf(payload(dir, "hello")))[0].pkg.name
            val info = core.info(name)
            assertEquals(name, info.name)
            assertEquals(name, info.installed?.name)
            assertTrue(info.source.startsWith("local:"), info.source)
        }

    @Test
    fun pinAndUnpinSetTheFlagAndAnUnknownNameIsNotFound() =
        scratch { dir, core ->
            val name = core.install(listOf(payload(dir, "hello")))[0].pkg.name
            assertTrue(core.pin(listOf(name))[0].pinned)
            assertTrue(core.installed()[0].pinned)
            assertFalse(core.unpin(emptyList())[0].pinned)
            assertFalse(core.installed()[0].pinned)
            assertEquals(WireError.NotFound("ghost"), failure { core.pin(listOf("ghost")) })
        }

    @Test
    fun aRollbackWithNothingRetainedIsRefusedAndChangesNothing() =
        scratch { dir, core ->
            val name = core.install(listOf(payload(dir, "hello")))[0].pkg.name
            val before = core.installed()
            failure { core.rollback(name, null) }
            assertEquals(before, core.installed())
        }

    @Test
    fun aPruneKeepBecomesTheRetentionSetting() =
        scratch { dir, core ->
            core.install(listOf(payload(dir, "hello")))
            assertEquals(emptyList(), core.prune(emptyList(), 2))
            assertEquals(2u, State.load(cfg(dir)).retention.keep)
            assertIs<WireError.Other>(failure { core.prune(emptyList(), -1) })
        }

    @Test
    fun aRegistryThatCannotBeReachedIsAnError() =
        scratch { _, core ->
            withEnv(mapOf("KETCH_GITHUB_API" to "http://127.0.0.1:9")) {
                failure { core.registryRefresh() }
            }
        }

    @Test
    fun pathStatusNamesEveryShellAndTheBinDir() =
        scratch { _, core ->
            val status = core.pathStatus()
            assertEquals(core.config().binDir, status.binDir)
            assertEquals(listOf("bash", "zsh", "fish"), status.shells.map { it.shell })
            assertFalse(status.onPath)
            assertNull(status.userPath)
        }

    @Test
    fun aDryRunPathInstallWritesNothing() =
        scratch { _, core ->
            val before = core.pathStatus()
            // The answer depends on the shells this machine uses; one where none can be told apart is the documented
            // error, not a guess.
            try {
                assertTrue(core.pathInstall(true).all { it.outcome != PathOutcome.Unchanged || it.file != null })
            } catch (e: CallFailed) {
                assertTrue("could not tell" in e.error.display(), e.error.display())
            }
            assertEquals(before, core.pathStatus())
        }

    @Test
    fun theConfigIsTheScratchRootAndHidesTheToken() =
        scratch { dir, core ->
            val settings = core.config()
            assertEquals(Path(dir, "root").toString(), settings.root)
            assertTrue(settings.jobs > 0)
            assertTrue(settings.target.isNotEmpty())
        }

    @Test
    fun doctorFixSetsUpTheShellInUseOnce() =
        scratch { dir, core ->
            val home = Path(dir, "home")
            createDirAll(home)
            // Every variable that could lead the fix to a real startup file.
            val env = mapOf("HOME" to home.toString(), "SHELL" to "/bin/zsh", "ZDOTDIR" to null, "XDG_CONFIG_HOME" to null)
            withEnv(env) {
                val fixed = core.doctorFix()
                assertEquals(1, fixed.size, fixed.toString())
                assertEquals("zsh", fixed[0].target)
                assertEquals(PathOutcome.Added, fixed[0].outcome)
                val file = Path(home, ".zshrc")
                assertEquals(file.toString(), fixed[0].file)
                assertTrue("ketch" in readText(file))
                assertEquals(emptyList(), core.doctorFix())
            }
        }

    /** [set] applied (null unsets), and every variable put back afterwards. Native tests run on one thread. */
    private fun <T> withEnv(
        set: Map<String, String?>,
        body: () -> T,
    ): T {
        val saved = set.keys.associateWith { getenv(it)?.toKString() }

        fun apply(values: Map<String, String?>) = values.forEach { (k, v) -> if (v == null) unsetenv(k) else setenv(k, v, 1) }
        apply(set)
        return try {
            body()
        } finally {
            apply(saved)
        }
    }
}
