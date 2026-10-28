// Rust tests/install.rs, continued: `lock` and `sync`, the name a package keeps, and `changelog` (see InstallTest).
package ketch.e2e

import fs.readText
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.said
import ketch.e2e.support.writeText
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallLockTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private val tool get() = sandbox.bin.resolve("testtool")

    /** Where the lockfile goes in a test: inside the sandbox, never the cwd the suite happens to run from. */
    private val lock get() = sandbox.home.resolve("ketch.lock")

    private val lockArg get() = lock.toString()

    @Test
    fun aLockfileRecordsWhatIsInstalledAndSyncPutsItBack() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        sandbox.ok("lock", "--file", lockArg)

        val text = readText(lock)
        assertTrue("name = \"testtool\"" in text, text)
        assertTrue("tag = \"v1.0.0\"" in text, text)
        assertTrue("source = \"test:testtool\"" in text, text)

        sandbox.ok("lock", "--check", "--file", lockArg)

        // Same tag, different recorded hash: the lock no longer describes the tree.
        sandbox.editPackage("testtool") { JsonObject(it + ("sha256" to JsonPrimitive("b".repeat(64)))) }
        sandbox.fails("lock", "--check", "--file", lockArg)
        sandbox.ok("sync", "--file", lockArg)
        sandbox.ok("lock", "--check", "--file", lockArg)

        // Wipe it, then let the lockfile put it back.
        sandbox.ok("uninstall", "testtool", "--yes")
        assertFalse(SystemFileSystem.exists(tool))
        sandbox.fails("lock", "--check", "--file", lockArg)

        sandbox.ok("sync", "--file", lockArg)
        assertEquals("testtool 1.0.0", said(tool))
        sandbox.ok("lock", "--check", "--file", lockArg)
    }

    /** The reason to write versions down: a newer release exists and sync must still produce the one that was locked. */
    @Test
    fun syncInstallsTheLockedTagNotTheLatestOne() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        sandbox.ok("lock", "--file", lockArg)

        // A newer release lands, and the machine takes it.
        val newer = sandbox.asset(darwinTool("2.0.0"), toolArchive("2.0.0"))
        val older = sandbox.asset(darwinTool("1.0.0"), toolArchive("1.0.0"))
        sandbox.publish("testtool", listOf(Release("2.0.0", listOf(newer)), Release("1.0.0", listOf(older))))
        sandbox.ok("upgrade", "--yes")
        assertEquals("testtool 2.0.0", said(tool))

        sandbox.fails("lock", "--check", "--file", lockArg)
        sandbox.ok("sync", "--file", lockArg)
        assertEquals("testtool 1.0.0", said(tool))
    }

    /** A release replaced under a tag it already published is what a lockfile exists to catch, before anything is unpacked. */
    @Test
    fun syncRefusesAPayloadThatIsNotTheOneThatWasLocked() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        sandbox.ok("lock", "--file", lockArg)
        sandbox.ok("uninstall", "testtool", "--yes")

        // Same tag, different bytes: exactly what a re-tagged release looks like.
        val text = readText(lock)
        val recorded = text.substringAfter("sha256 = \"", "").substringBefore('"')
        assertTrue(recorded.isNotEmpty(), "no sha256 in the lockfile:\n$text")
        writeText(lock, text.replace(recorded, "b".repeat(64)))

        val err = sandbox.fails("sync", "--file", lockArg)
        assertTrue("does not match the lockfile" in err, err)
        assertFalse(SystemFileSystem.exists(tool), "a payload that did not match the lock was installed anyway")
    }

    /**
     * `--name` is the name a package lives under from then on. An upgrade that re-resolved the source would infer
     * `testtool` and install a second copy beside `tt` instead of replacing it.
     */
    @Test
    fun upgradeKeepsTheNameGivenAtInstall() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--name", "tt", "--yes")

        sandbox.publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")

        assertEquals("tt", sandbox.ok("list", "local", "--names-only").trim())
        assertEquals("testtool 2.0.0", said(tool))
    }

    /** The lockfile records the name; sync has to put the package back under it, or `lock --check` never agrees. */
    @Test
    fun syncPutsARenamedPackageBackUnderItsName() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--name", "tt", "--yes")
        sandbox.ok("lock", "--file", lockArg)
        sandbox.ok("uninstall", "tt", "--yes")

        sandbox.ok("sync", "--file", lockArg)
        assertEquals("tt", sandbox.ok("list", "local", "--names-only").trim())
        sandbox.ok("lock", "--check", "--file", lockArg)
    }

    @Test
    fun pruneRemovesWhatTheLockfileDoesNotName() {
        sandbox.publishTool("1.0.0")
        sandbox.publish("testapp", listOf(Release("1.0.0", listOf(sandbox.asset("TestApp-1.0.0-macos.zip", appArchive("1.0.0"))))))

        sandbox.ok("install", "test:testtool", "--yes")
        sandbox.ok("lock", "--file", lockArg)
        sandbox.ok("install", "test:testapp", "--yes")

        // An extra is not drift on its own: only `--prune` treats it as such.
        sandbox.ok("lock", "--check", "--file", lockArg)
        sandbox.ok("sync", "--prune", "--yes", "--file", lockArg)
        assertFalse(SystemFileSystem.exists(sandbox.apps.resolve("TestApp.app")))
        assertEquals("testtool 1.0.0", said(tool))
    }

    @Test
    fun aLockfileNamingAPathInsteadOfAPackageIsRefused() {
        writeText(
            lock,
            "version = 1\n\n[[package]]\nname = \"../../.zshrc\"\nsource = \"test:testtool\"\n" +
                "version = \"1.0.0\"\ntag = \"1.0.0\"\ntarget = \"macos-aarch64\"\n" +
                "asset = \"t.tar.gz\"\nsha256 = \"${"a".repeat(64)}\"\n",
        )
        val err = sandbox.fails("sync", "--file", lockArg)
        assertTrue("not a usable package name" in err, err)
    }

    /** The changelog a package ships is preferred over the release notes, and the section is the installed version's. */
    @Test
    fun changelogPrintsTheSectionForTheInstalledVersion() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")

        val out = sandbox.ok("changelog", "testtool")
        assertTrue("the first one" in out, "no 1.0.0 section:\n$out")
        assertFalse("the second one" in out, "ran past 1.0.0 into 2.0.0:\n$out")

        val notes = sandbox.ok("changelog", "testtool", "--release")
        assertTrue("published notes for 1.0.0" in notes, "release notes not reached:\n$notes")
        assertFalse("the first one" in notes, "read the file:\n$notes")
    }

    /** A package that is not installed has no file to read, so `--file` says so rather than printing the notes instead. */
    @Test
    fun changelogForAPackageWithNoFileSaysWhereToLook() {
        sandbox.publishTool("1.0.0")
        val err = sandbox.fails("changelog", "test:testtool", "--file")
        assertTrue("not installed" in err, err)
    }
}
