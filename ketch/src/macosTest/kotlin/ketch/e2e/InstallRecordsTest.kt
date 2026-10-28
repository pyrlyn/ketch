// Rust tests/install.rs, continued: batches, the log, history and statistics, `self uninstall`, and the root lock
// (see InstallTest).
package ketch.e2e

import fs.createDirAll
import fs.entryKind
import fs.listDir
import fs.readText
import fs.symlink
import ketch.e2e.support.Archive
import ketch.e2e.support.Entry
import ketch.e2e.support.KETCH_EXE
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.get
import ketch.e2e.support.hostArch
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.ketch
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.said
import ketch.e2e.support.str
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallRecordsTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private val tool get() = sandbox.bin.resolve("testtool")

    /** One tool per name, so a batch has several distinct packages to install. */
    private fun publishNamed(
        name: String,
        version: String,
    ) {
        val archive = Archive.TarGz(listOf(Entry.program("$name-$version/bin/$name", "$name $version")))
        val asset = sandbox.asset("$name-$version-${hostArch()}-apple-darwin.tar.gz", archive)
        sandbox.publish(name, listOf(Release(version, listOf(asset))))
    }

    private fun installedNames() =
        sandbox
            .ok("list", "local", "--names-only")
            .lines()
            .filter { it.isNotEmpty() }
            .sorted()

    /**
     * A batch runs its downloads concurrently, so this proves the part concurrency could break: every package ends up
     * placed, runnable and recorded, and the results are reported in the order they were asked for.
     */
    @Test
    fun aBatchInstallsEveryPackageAndReportsThemInTheOrderAsked() {
        val names = listOf("delta", "alpha", "charlie", "bravo")
        names.forEach { publishNamed(it, "1.0.0") }

        val out = sandbox.ketch(listOf("install") + names.map { "test:$it" } + "--yes")
        assertTrue(out.success, "install failed\n$out")

        val reported =
            out.stderr
                .lines()
                .filter { "installed" in it }
                .mapNotNull { it.split(Regex("\\s+")).filter(String::isNotEmpty).getOrNull(1) }
        assertEquals(names, reported, "reported out of order:\n${out.stderr}")

        names.forEach { assertEquals("$it 1.0.0", said(sandbox.bin.resolve(it))) }
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), installedNames())

        // Every download and unpack is staged in the cache; concurrency is where a leaked staging dir turns invisible.
        val left = listDir(sandbox.root.resolve("cache"))
        assertTrue(left.isEmpty(), "left behind in the cache: $left")
    }

    /**
     * Two spellings of one package in a single batch resolve to the same name and asset, so before each download was
     * staged in a directory of its own they raced for one path in the cache.
     */
    @Test
    fun theSamePackageAskedForTwoWaysInstallsCleanly() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "test:testtool@1.0.0", "--yes")
        assertEquals("testtool 1.0.0", said(tool))
        assertEquals("testtool", sandbox.ok("list", "local", "--names-only").trim())
    }

    /** A batch is not all-or-nothing: the packages that resolved are installed and the one that did not is named. */
    @Test
    fun oneBadPackageInABatchDoesNotLoseTheGoodOnes() {
        publishNamed("alpha", "1.0.0")
        publishNamed("bravo", "1.0.0")

        val err = sandbox.fails("install", "test:alpha", "test:nothing-published-here", "test:bravo", "--yes")
        assertTrue("nothing-published-here" in err, err)
        assertEquals(listOf("alpha", "bravo"), installedNames(), "good packages were lost")
    }

    /** `--jobs 1` is the escape hatch, and has to install exactly the same tree. */
    @Test
    fun aBatchWithOneJobInstallsTheSameThing() {
        publishNamed("alpha", "1.0.0")
        publishNamed("bravo", "1.0.0")

        sandbox.ok("install", "test:alpha", "test:bravo", "--jobs", "1", "--yes")
        assertEquals("alpha 1.0.0", said(sandbox.bin.resolve("alpha")))
        assertEquals("bravo 1.0.0", said(sandbox.bin.resolve("bravo")))
    }

    /** The log is what is left after the terminal scrolls away, so a run has to be in it, and a failure says where it is. */
    @Test
    fun everyRunIsLoggedAndAFailureSaysWhereTheLogIs() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        var log = sandbox.log()
        assertTrue("INFO" in log, "no records:\n$log")
        assertTrue("install test:testtool" in log, "no command:\n$log")
        assertTrue("installed testtool 1.0.0" in log, "no result:\n$log")

        val err = sandbox.fails("install", "test:not-published", "--yes")
        assertTrue("ketch.log" in err, "no pointer to the log:\n$err")
        log = sandbox.log()
        assertTrue("ERROR" in log, "the failure was not logged:\n$log")
        assertTrue(log.trimEnd('\n').lines().none { it.isEmpty() }, "a record was split across lines:\n$log")
    }

    /** The other half of "a common log format": JSON Lines, for anything that is not a person reading it. */
    @Test
    fun theLogCanBeJsonLinesInstead() {
        sandbox.configure("log_format = \"json\"\nlog_level = \"debug\"\n")
        sandbox.ok("list", "local")

        val log = sandbox.log()
        val first = json(log.lineSequence().first())
        assertEquals("info", first["level"].str)
        assertTrue(first["msg"].str.orEmpty().contains("list"), log)
        assertTrue(first["time"].str.orEmpty().endsWith('Z'), log)
        assertTrue(log.lines().any { "\"debug\"" in it }, log)
    }

    /** A bad setting is the user's own file, and has to say which one. */
    @Test
    fun anUnreadableLogSettingIsRefusedByName() {
        sandbox.configure("log_level = \"chatty\"\n")
        val err = sandbox.fails("list", "local")
        assertTrue("chatty" in err, err)
        assertTrue("config.toml" in err, err)
    }

    /** The database is the only thing that still remembers a package once it is removed, which is why it sits beside `state.json`. */
    @Test
    fun historyRecordsAnInstallAnUpgradeAndAnUninstallNewestFirst() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        sandbox.publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        sandbox.ok("uninstall", "testtool", "--yes")

        val report = sandbox.ok("history", "testtool", "--json")
        val events = json(report).items
        assertEquals(listOf("uninstall", "upgrade", "install"), events.mapNotNull { it["action"].str }, report)

        // The upgrade names both sides: that makes this a version history, not a list of versions that were installed.
        assertEquals("2.0.0", events[1]["version"].str)
        assertEquals("1.0.0", events[1]["previous_version"].str)
        assertEquals(JsonNull, events[2]["previous_version"], "a first install replaced nothing")

        // `state.json` has forgotten the package entirely; the history has not.
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
    }

    @Test
    fun statisticsTotalWhatTheHistoryRecorded() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        sandbox.publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")

        val report = sandbox.ok("stats", "--json")
        val stats = json(report)
        assertEquals(JsonPrimitive(1), stats["installs"], report)
        assertEquals(JsonPrimitive(1), stats["upgrades"], report)
        assertEquals(JsonPrimitive(0), stats["uninstalls"], report)
        assertEquals(JsonPrimitive(1), stats["packages"], "one package, twice: $report")
        // Both went through the download pipeline, so both were timed.
        assertNotNull((stats["mean_duration_ms"] as? JsonPrimitive)?.longOrNull, report)
    }

    /** `--limit 0` asks for no rows; that must not be read as "nothing was recorded". */
    @Test
    fun historyWithAZeroLimitDoesNotClaimNothingWasRecorded() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")

        val out = sandbox.ok("history", "--limit", "0")
        assertFalse("no history recorded" in out, "limit 0 must not look like an empty database:\n$out")

        val report = sandbox.ok("history", "--limit", "0", "--json")
        assertEquals(JsonArray(emptyList()), json(report), report)

        val table = sandbox.ok("history")
        assertTrue("testtool" in table, "history still exists:\n$table")
    }

    /** Nothing recorded is an ordinary state, a fresh machine or an older root, and reading it must not make a file. */
    @Test
    fun historyAndStatsAreCalmAboutARootThatHasNeverInstalledAnything() {
        assertTrue("no history recorded" in sandbox.ok("history"))
        assertTrue("no statistics recorded" in sandbox.ok("stats"))
        assertFalse(SystemFileSystem.exists(sandbox.root.resolve("stats.db")), "a read created the database")
    }

    /** A shell startup file with the user's own lines in it, so a test can prove removing ketch's block leaves them as they were. */
    private fun zshrc(): Pair<Path, String> {
        val file = sandbox.home.resolve(".zshrc")
        val original = "# mine\nexport EDITOR=vi\n"
        writeText(file, original)
        return file to original
    }

    /** Installed, with the PATH block written and a Homebrew cask: everything `self uninstall` has to take back. */
    private fun everythingSelfUninstallTakes(): Triple<Path, String, Path> {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        val (file, original) = zshrc()
        sandbox.ok("path", "install", "--shell", "zsh")
        return Triple(file, original, sandbox.installCask())
    }

    /**
     * `self install --link-dir` records the bootstrap on the package so uninstall can take it back. Without that
     * record, removing the root leaves `<link-dir>/ketch` pointing at nothing.
     */
    @Test
    fun selfUninstallRemovesABootstrapLinkOutsideTheRoot() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")

        val bootstrap = sandbox.fixture("bootstrap")
        createDirAll(bootstrap)
        val link = bootstrap.resolve("ketch")
        symlink(tool, link)

        val record =
            buildJsonObject {
                put("link", link.toString())
                put("target", tool.toString())
                put("kind", "symlink")
            }
        sandbox.editPackage("testtool") { JsonObject(it + ("links" to JsonArray(it.getValue("links") as JsonArray + record))) }

        sandbox.ok("self", "uninstall", "--yes")
        assertEquals(null, entryKind(link), "bootstrap link must be gone, not left dangling at $link")
    }

    @Test
    fun selfUninstallRemovesThePackagesThePathBlockAndTheHomebrewCask() {
        val (file, original, brewLog) = everythingSelfUninstallTakes()

        val out = sandbox.ok("self", "uninstall", "--yes")

        // The tree is gone rather than emptied: the root goes with it because nothing else was in there.
        assertFalse(SystemFileSystem.exists(sandbox.root), "the root survived, holding ${sandbox.names(sandbox.root)}\n$out")
        assertEquals(original, readText(file), "the PATH block was not taken back out")
        // Handed back to Homebrew rather than deleted behind its back, which would leave `brew` believing ketch is installed.
        assertTrue("uninstall --cask ketch" in readText(brewLog), "brew was called with something else")
        assertFalse(SystemFileSystem.exists(sandbox.homebrew.resolve("Caskroom/ketch")))
        // The binary being run is outside the root, a build rather than an install, so it is not ketch's to delete.
        assertTrue(SystemFileSystem.exists(Path(KETCH_EXE)))
    }

    @Test
    fun selfUninstallSaysWhatItWillTakeAndRemovesNothingWithoutAnAnswer() {
        val (file, original, _) = everythingSelfUninstallTakes()

        // No terminal and no `--yes`: the answer is no, the only safe default for something that cannot be undone.
        val out = sandbox.ketch("self", "uninstall")
        assertTrue(out.success, "declining is not a failure:\n${out.stderr}")
        assertTrue("cancelled" in out.stderr, out.stderr)
        assertTrue("permanent" in out.stderr, out.stderr)
        for (named in listOf("testtool", sandbox.root.toString(), file.toString(), "Homebrew cask")) {
            assertTrue(named in out.stderr, "the question did not say $named was at stake:\n${out.stderr}")
        }

        assertEquals("testtool 1.0.0", said(tool))
        assertTrue(SystemFileSystem.exists(sandbox.store.resolve("testtool")))
        assertNotEquals(original, readText(file))
        assertTrue(SystemFileSystem.exists(sandbox.homebrew.resolve("Caskroom/ketch")))
    }

    @Test
    fun selfUninstallCanKeepThePackagesAndLeaveTheCaskToHomebrew() {
        val (file, _, brewLog) = everythingSelfUninstallTakes()

        // What the cask itself runs on the way out: Homebrew is already removing the cask, and the packages are not its to take.
        sandbox.ok("self", "uninstall", "--yes", "--keep-packages", "--no-brew")

        assertEquals("testtool 1.0.0", said(tool))
        assertTrue(SystemFileSystem.exists(sandbox.store.resolve("testtool")))
        assertTrue(sandbox.bin.toString() in readText(file), "the block was removed for a tree that is still there")
        assertFalse(SystemFileSystem.exists(brewLog), "brew was run despite --no-brew")
        assertTrue(SystemFileSystem.exists(sandbox.homebrew.resolve("Caskroom/ketch")))
    }

    /** A second ketch must not wait on, or steal, a live run's lock: a GUI host and the CLI share one root. */
    @Test
    fun aCommandRunWhileAnotherProcessHoldsTheLockReportsItBusy() {
        // This test process is alive and is not the ketch about to run.
        val holder = getpid().toString()
        writeText(sandbox.root.resolve(".lock"), holder)

        val out = sandbox.ketch("install", "--path", "/nonexistent", "-y")
        assertEquals(8, out.code, out.stderr)
        assertTrue("another ketch process holds the lock (pid $holder)" in out.stderr, out.stderr)
        // The holder's lock file survives the refused run.
        assertEquals(holder, readText(sandbox.root.resolve(".lock")))
    }
}
