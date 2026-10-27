package ketch.core.install

import fs.createDirAll
import fs.entryKind
import fs.listDir
import fs.removeAny
import ketch.core.binchoice.Known
import ketch.core.binchoice.Picked
import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.decide.Decider
import ketch.core.decide.NoDecider
import ketch.core.error.KetchError
import ketch.core.extract.Extractor
import ketch.core.model.Arch
import ketch.core.model.AssetSelector
import ketch.core.model.LinkRecord
import ketch.core.model.Os
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.TargetSpec
import ketch.core.platform.AssetScore
import ketch.core.platform.DoctorCheck
import ketch.core.platform.Placement
import ketch.core.platform.Platform
import ketch.core.platform.host
import ketch.core.process.Occupant
import ketch.core.report.Ctx
import ketch.core.report.Event
import ketch.core.report.Recorder
import ketch.core.report.Report
import ketch.core.report.SilentProgress
import ketch.core.report.Stage
import ketch.core.report.Task
import ketch.core.resolve.scoreAssets
import ketch.core.source.ListOpts
import ketch.core.source.Source
import ketch.core.source.SourceRegistry
import ketch.core.state.State
import ketch.core.testing.program
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stands in for a real platform: macOS assets only. */
private object FakePlatform : Platform {
    override val id = "fake"

    override fun target() = TargetSpec(Os.MacOs, Arch.Aarch64)

    override fun scoreAsset(
        assetName: String,
        allowEmulation: Boolean,
    ) = AssetScore(50, Arch.Aarch64, false, "fake").takeIf { "darwin" in assetName }

    override fun extractors(): List<Extractor> = emptyList()

    override fun place(plan: Placement): List<LinkRecord> = emptyList()

    override fun unplace(
        links: List<LinkRecord>,
        report: Report,
    ) = Unit

    override fun isExecutable(path: Path) = true

    override fun doctor(cfg: Config): List<DoctorCheck> = emptyList()
}

/** Answers the binary question from a script and remembers what it was asked, as the front end would see it. */
private class Scripted(
    private val pick: Int?,
) : Decider {
    val asked = mutableListOf<Pair<String, List<String>>>()

    override fun chooseBinary(
        pkg: String,
        candidates: List<String>,
    ): Int? {
        asked += pkg to candidates
        return pick
    }

    override fun stopProcesses(occupants: List<Occupant>) = false

    override fun confirmRemoveLeftover(path: Path) = false
}

/** A source whose `checksums` either fails (the fetch broke) or answers cleanly empty (it publishes none). */
private class StubSource(
    private val fail: Boolean,
) : Source {
    override fun scheme() = "stub"

    override fun listReleases(
        id: String,
        opts: ListOpts,
    ): List<Release> = emptyList()

    override fun checksums(
        id: String,
        release: Release,
        wanted: String,
    ): Map<String, String> = if (fail) throw KetchError.Msg("fetch failed") else emptyMap()

    override fun download(
        asset: ketch.core.model.ReleaseAsset,
        dest: Path,
        progress: ketch.core.report.ProgressSink,
        cancel: Cancel,
    ): String = throw KetchError.Msg("verifyChecksum never downloads")
}

class InstallTest {
    private fun config(tmp: Path) = Config.load(Path(tmp, "root"), Report.silent()).copy(target = TargetSpec(Os.MacOs, Arch.Aarch64))

    @Test
    fun dropsAssetsThePlatformCannotRun(): Unit =
        withTempDir { tmp ->
            val picked = scoreAssets(config(tmp), FakePlatform, release("tool-linux.tar.gz", "tool-darwin.tar.gz"), AssetSelector())
            assertEquals(listOf("tool-darwin.tar.gz"), picked.map { it.asset.name })
        }

    @Test
    fun excludeWinsOverIncludeAndOverTheTargetPin(): Unit =
        withTempDir { tmp ->
            val selector = AssetSelector(listOf("*darwin*"), listOf("*.dmg"), mapOf("macos-aarch64" to "*.dmg"))
            // The pin would have taken the dmg; the exclusion removes it first, and with a pin present the tarball is
            // not considered either.
            assertTrue(scoreAssets(config(tmp), FakePlatform, release("tool-darwin.dmg", "tool-darwin.tar.gz"), selector).isEmpty())
        }

    @Test
    fun aTargetPinOverridesPlatformScoring(): Unit =
        withTempDir { tmp ->
            val selector = AssetSelector(target = mapOf("macos-aarch64" to "*-mac-universal.zip"))
            // `mac` alone would score nothing from FakePlatform; the pin still wins.
            val picked = scoreAssets(config(tmp), FakePlatform, release("tool-darwin.tar.gz", "tool-mac-universal.zip"), selector)
            assertEquals(listOf("tool-mac-universal.zip"), picked.map { it.asset.name })
        }

    @Test
    fun anUnverifiedReleaseReplacingAVerifiedInstallNeedsConsent() {
        val spec = PackageSpec.parse("o/r")
        val verified = installed("r", "1.0.0", Path("/nowhere"))
        val unverified = verified.copy(checksumVerified = false)
        val next = asset("r-2.0.0.tar.gz")
        val plain = InstallRequest(spec)

        val refused = assertFailsWith<KetchError.ChecksumDropped> { refuseChecksumDowngrade(plain, verified, false, next) }
        assertEquals("r", refused.name)
        // A verified release, a fresh install and an install that was never verified lose nothing, so they are not
        // asked about.
        refuseChecksumDowngrade(plain, verified, true, next)
        refuseChecksumDowngrade(plain, null, false, next)
        refuseChecksumDowngrade(plain, unverified, false, next)
        refuseChecksumDowngrade(plain.copy(allowUnverified = true), verified, false, next)
        refuseChecksumDowngrade(plain.copy(expectedSha256 = "0".repeat(64)), verified, false, next)
    }

    @Test
    fun anInstallCancelledBeforeItCommitsLeavesNoStoreFolderOrStateEntry(): Unit =
        withTempDir { tmp ->
            val cfg = Config.load(Path(tmp, "root"), Report.silent())
            val tool = program(tmp, "canceltool", "#!/bin/sh\necho hi\n")
            val cx = Ctx(cfg, Report.silent())
            val state = State()
            val cancel = Cancel()
            val prepared = prepare(cx, SourceRegistry.load(cx), state, localRequest(tool, cancel), SilentProgress)
            // The host pressed Stop after the download, before anything was placed.
            cancel.cancel()
            assertFailsWith<KetchError.Cancelled> { commit(cx, state, prepared) }
            assertTrue(state.iter().isEmpty(), "no state entry")
            assertNull(entryKind(Path(cfg.storeDir, "canceltool")), "no store folder")
            assertEquals(0, runCatching { listDir(cfg.cacheDir).size }.getOrDefault(0), "temp dir removed")
            assertNull(entryKind(Path(cfg.binDir, "canceltool")), "no link")
        }

    @Test
    fun aReinstallWhoseLinkingFailsAfterTheSwapPutsThePreviousStoreBack(): Unit =
        withTempDir { tmp ->
            val cfg = Config.load(Path(tmp, "root"), Report.silent())
            val tool = program(tmp, "swaptool", "#!/bin/sh\necho hi\n")
            val cx = Ctx(cfg, Report.silent())
            val sources = SourceRegistry.load(cx)
            val state = State()
            val req = localRequest(tool)
            install(cx, sources, state, req)
            val before = assertNotNull(state.get("swaptool"))
            // Marks the tree state names, so a swapped-in copy cannot pass for it.
            Path(before.prefix, "previous").write("kept")
            // A local reinstall lands on the same version, so the swap goes into the prefix state already records —
            // the case the orphan guard skips. A file where the bin dir was passes preflight and fails only when
            // linking creates the dir, after the swap.
            removeAny(cfg.binDir)
            cfg.binDir.write("")

            val err = assertFailsWith<KetchError> { install(cx, sources, state, req.copy(force = true)) }
            // Any earlier failure would leave the store untouched and prove nothing.
            assertTrue(cfg.binDir.toString() in err.message, err.message)
            assertEquals(before.prefix, state.get("swaptool")?.prefix)
            assertEquals("kept", Path(before.prefix, "previous").read(), "the store state names is the one that was there before")
            assertNull(entryKind(Path("${before.prefix}.old")), "no retired copy left behind")
        }

    /** The event sequence a front end draws an install from: every stage in order, the download as one task that ends, and nothing printed. */
    @Test
    fun aLocalInstallReportsEachStageThroughTheReporter(): Unit =
        withTempDir { tmp ->
            val recorder = Recorder()
            val report = Report(recorder)
            val cfg = Config.load(Path(tmp, "root"), report)
            val cx = Ctx(cfg, report)
            val tool = program(tmp, "tool", "#!/bin/sh\necho tool\n")
            install(cx, SourceRegistry.builtinOnly(cx), State(), InstallRequest(PackageSpec.parse("local:$tool")))

            val events = recorder.events()
            assertEquals(
                listOf(Stage.Resolving, Stage.Downloading, Stage.Verifying, Stage.Extracting, Stage.Trusting, Stage.Installing),
                events.filterIsInstance<Event.Step>().map { it.stage },
            )
            val download =
                assertNotNull(
                    events.filterIsInstance<Event.Began>().firstOrNull { (it.task as? Task.Download)?.batch == null && it.task is Task.Download },
                    "the download is announced",
                ).id
            assertTrue(events.any { it is Event.Ended && it.id == download }, "a download that arrived ends rather than being abandoned: $events")
            assertTrue(events.any { it is Event.Status && it.verb == "resolving" && it.detail.startsWith("tool ") }, "$events")
            assertTrue(events.none { it is Event.Warn }, "a clean install warns about nothing: $events")
        }

    /** A payload with two executables that share the package name and none named exactly like it, so only a decision can pick. */
    private fun pickWith(decider: Decider): BinPick? =
        withTempDir { tmp ->
            val payload = Path(tmp, "payload")
            createDirAll(payload)
            for (stem in listOf("rtok-cli", "rtok-hook")) program(payload, stem)
            val cx = Ctx(Config.load(Path(tmp, "root"), Report.silent()), Report.silent()).withDecider(decider)
            pickBin(cx, host(), payload, null, "rtok", Known())
        }

    @Test
    fun theDeciderPicksTheBinaryWhenNothingElseCan() {
        val decider = Scripted(1)
        val pick = assertNotNull(pickWith(decider), "a pick")
        assertEquals(1, decider.asked.size, "asked exactly once: ${decider.asked}")
        val (pkg, candidates) = decider.asked[0]
        assertEquals("rtok", pkg)
        assertEquals(2, candidates.size, "$candidates")
        assertEquals(candidates[1], pick.file)
        assertEquals(Picked.Asked, pick.how)
    }

    @Test
    fun aDeclinedChoiceIsTheAmbiguityError() {
        val decider = Scripted(null)
        val err = assertFailsWith<KetchError> { pickWith(decider) }.message
        assertTrue("ships several binaries sharing its name" in err, err)
        assertEquals(1, decider.asked.size)
    }

    @Test
    fun noDeciderDeclinesLikeAScript() {
        val err = assertFailsWith<KetchError> { pickWith(NoDecider) }.message
        assertTrue("ships several binaries sharing its name" in err, err)
    }

    private fun verifyWith(source: StubSource): Pair<Boolean, List<String>> {
        val warns = Warns()
        val rel = release("ketch-aarch64-apple-darwin.tar.gz")
        val verified = verifyChecksum(source, "stub/example", rel, rel.assets[0], "deadbeef", false, Report(warns))
        return verified to warns.all
    }

    /**
     * A checksum sidecar that cannot be fetched (network, rate limit, a proxy eating the request) is not the same as
     * a release that publishes no checksum: the install still fails open to first use, but with a warning.
     */
    @Test
    fun aChecksumFetchFailureWarnsAndStaysFirstUse() {
        val (verified, warns) = verifyWith(StubSource(fail = true))
        assertFalse(verified, "fail-open without --require-checksums")
        assertEquals(1, warns.size, "exactly one warning: $warns")
        assertTrue("could not read published checksums" in warns[0], "$warns")
    }

    /** The clean case stays quiet: an empty answer is the source saying nothing is published here. */
    @Test
    fun aCleanAbsenceOfChecksumsStaysQuiet() {
        val (verified, warns) = verifyWith(StubSource(fail = false))
        assertFalse(verified, "first use either way")
        assertTrue(warns.isEmpty(), "$warns")
    }
}
