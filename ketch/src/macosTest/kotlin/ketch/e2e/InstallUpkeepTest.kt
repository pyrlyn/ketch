// Rust tests/install.rs, continued: relinking, `doctor`, and the shell setup `path` writes (see InstallTest).
package ketch.e2e

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.listDir
import fs.readText
import fs.removeAny
import fs.removeFile
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.get
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.ketch
import ketch.e2e.support.ketchOffPath
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.said
import ketch.e2e.support.str
import ketch.e2e.support.writeText
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallUpkeepTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private val tool get() = sandbox.bin.resolve("testtool")

    private fun installed() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
    }

    /** The doctor check called [name] in a `doctor --json` report. */
    private fun check(
        report: String,
        name: String,
    ) = json(report)["checks"].items.firstOrNull { it["name"].str == name } ?: error("no $name check: $report")

    @Test
    fun relinkRebuildsALinkThatWasDeletedByHand() {
        installed()
        removeFile(tool)
        sandbox.ok("link", "testtool")
        assertEquals("testtool 1.0.0", said(tool))
    }

    @Test
    fun relinkKeepsExistingLinksWhenPlacementFails() {
        installed()
        assertTrue(SystemFileSystem.exists(tool), "install must have linked testtool")

        val store = sandbox.store.resolve("testtool")
        for (version in listDir(store).map(store::resolve).filter(::isDir)) {
            listDir(version).forEach { removeAny(version.resolve(it)) }
        }

        sandbox.fails("link", "testtool")
        // The store target is gone, so `exists` would follow the dangling symlink and say no.
        assertEquals(EntryKind.Symlink, entryKind(tool), "a failed relink must not take the working link with it")
    }

    @Test
    fun doctorReportsAHealthyTree() {
        installed()
        // Exit status is the assertion: doctor fails when the tree is broken.
        sandbox.ok("doctor")
    }

    @Test
    fun doctorJsonIsAnObjectOfChecks() {
        installed()
        val report = sandbox.ok("doctor", "--json")
        val parsed = json(report)
        assertTrue(parsed["status"].str in setOf("ok", "warn"), "unexpected status: $report")
        check(report, "version")
        check(report, "packages")
        assertFalse("ok  " in report, "text report leaked into JSON:\n$report")
    }

    @Test
    fun doctorJsonStillFailsWhenACheckFails() {
        val failed = sandbox.ketchOffPath("doctor", "--json")
        assertFalse(failed.success, "doctor --json passed without PATH set up")
        val parsed = json(failed.stdout)
        assertEquals("fail", parsed["status"].str, failed.stdout)
        assertTrue(parsed["checks"].items.any { it["status"].str == "fail" }, failed.stdout)
    }

    @Test
    fun doctorWarnsAboutAStorePrefixWithNoStateEntry() {
        createDirAll(sandbox.store.resolve("ghost"))
        val report = sandbox.ok("doctor", "--json")
        val orphans = check(report, "orphans")
        assertEquals("warn", orphans["status"].str, report)
        assertTrue(orphans["detail"].str!!.contains("ghost"), report)
    }

    @Test
    fun doctorWarnsAboutAStaleLock() {
        writeText(sandbox.root.resolve(".lock"), "999999999")
        val report = sandbox.ok("doctor", "--json")
        val lock = check(report, "lock")
        assertEquals("warn", lock["status"].str, report)
        assertTrue(lock["detail"].str!!.contains("stale"), report)
    }

    @Test
    fun doctorWarnsAboutAHomebrewCaskLeftAfterPackagesAreGone() {
        createDirAll(sandbox.homebrew.resolve("Caskroom/ketch"))
        val report = sandbox.ok("doctor", "--json")
        val cask = check(report, "cask")
        assertEquals("warn", cask["status"].str, report)
        assertTrue(cask["detail"].str!!.contains("Caskroom"), report)
    }

    /**
     * A shell startup file is the one thing ketch writes outside its own root, so the guarantee worth proving end to
     * end is that the user's own file survives being written, rewritten and taken back out.
     */
    @Test
    fun pathInstallEditsAShellConfigAndCanUndoItself() {
        val zshrc = sandbox.home.resolve(".zshrc")
        val original = "# mine\nexport EDITOR=vi\n"
        writeText(zshrc, original)

        sandbox.ok("path", "install", "--shell", "zsh")
        val after = readText(zshrc)
        assertTrue(after.startsWith(original), "the user's own lines moved:\n$after")
        assertTrue(sandbox.bin.toString() in after, "bin dir missing from:\n$after")

        // Twice must not mean two blocks.
        sandbox.ok("path", "install", "--shell", "zsh")
        assertEquals(after, readText(zshrc), "a second install changed the file")

        sandbox.ok("path", "uninstall", "--shell", "zsh")
        assertEquals(original, readText(zshrc), "uninstall did not restore the file")
    }

    @Test
    fun aDryRunSaysWhatItWouldDoAndWritesNothing() {
        // Progress goes to stderr, so output stays pipeable.
        val out = sandbox.ketch("path", "install", "--shell", "fish", "--dry-run")
        assertTrue(out.success, out.stderr)
        assertTrue("would add" in out.stderr, out.stderr)
        assertFalse(SystemFileSystem.exists(sandbox.home.resolve(".config/fish/config.fish")), "a dry run created the file")
    }

    @Test
    fun selfUninstallDryRunNamesWhatItWouldRemoveAndRemovesNothing() {
        installed()
        assertTrue(SystemFileSystem.exists(tool), "install did not link testtool")

        val out = sandbox.ketch("self", "uninstall", "--dry-run")
        assertTrue(out.success, "dry-run failed\n$out")
        assertTrue("would remove" in out.stderr, "dry-run did not name the plan:\n${out.stderr}")
        assertFalse("cancelled" in out.stderr, "dry-run asked for confirmation:\n${out.stderr}")
        assertTrue(SystemFileSystem.exists(tool), "dry-run deleted the installed package")
    }

    /** The whole point of `--fix`: a PATH no shell knows about is the one doctor failure the user should not have to act on. */
    @Test
    fun doctorFixesAPathNoShellKnowsAbout() {
        val failed = sandbox.ketchOffPath("doctor")
        assertFalse(failed.success, "doctor passed without PATH set up")
        assertTrue("is not on PATH" in failed.stdout, failed.stdout)

        val fixed = sandbox.ketchOffPath("doctor", "--fix")
        assertTrue(fixed.success, "doctor --fix still failed:\n$fixed")
        // Fixed, but not in *this* process: the check has to say so rather than report the failure it just repaired.
        assertTrue("but not in this shell" in fixed.stdout, fixed.stdout)

        val zshrc = readText(sandbox.home.resolve(".zshrc"))
        assertTrue(sandbox.bin.toString() in zshrc, zshrc)
    }

    @Test
    fun aPathTheUserWiredUpByHandIsNeverDuplicated() {
        val zshrc = sandbox.home.resolve(".zshrc")
        val mine = "export PATH=\"${sandbox.bin}:\$PATH\"\n"
        writeText(zshrc, mine)
        sandbox.ok("path", "install", "--shell", "zsh")
        assertEquals(mine, readText(zshrc), "ketch added a second copy of a line the user already had")
    }
}
