// End-to-end tests: the real binary, a real install tree, real archives (Rust tests/install.rs, first half; the rest
// is in InstallUpkeepTest). These exist because the unit tests each prove one function and none of them prove the
// pipeline: every bug this suite was written against (a bundle unwrapped into its own `Contents`, a link left pointing
// at a deleted store, an upgrade that removed the old version before the new one was in place) passed every unit
// test. macOS-only, like the platform layer they exercise.
package ketch.e2e

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.readText
import fs.removeFile
import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.hostArch
import ketch.e2e.support.ketch
import ketch.e2e.support.ketchOnTty
import ketch.e2e.support.ketchOverrides
import ketch.e2e.support.ok
import ketch.e2e.support.okEnv
import ketch.e2e.support.resolve
import ketch.e2e.support.said
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    private val tool get() = sandbox.bin.resolve("testtool")

    @Test
    fun aToolIsDownloadedVerifiedLinkedAndRunnable() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")

        assertEquals(EntryKind.Symlink, entryKind(tool), "expected a symlink at $tool")
        assertEquals("testtool 1.0.0", said(tool))
        val state = sandbox.state()
        assertTrue("\"name\": \"testtool\"" in state, state)
        // The plugin published a digest, so this was verified rather than trusted on first use.
        assertTrue("\"checksum_verified\": true" in state, state)
        // The asset naming this machine's architecture beat the Linux decoy.
        assertTrue(darwinTool("1.0.0") in state, state)
    }

    @Test
    fun anAppBundleIsPlacedWholeAndRemovedAgain() {
        sandbox.publish("testapp", listOf(Release("1.0.0", listOf(sandbox.asset("TestApp-1.0.0-macos.zip", appArchive("1.0.0"))))))
        sandbox.ok("install", "test:testapp", "--yes")

        // The bundle is the payload. Unwrapping it as though it were a wrapper directory would place `Contents`.
        val app = sandbox.apps.resolve("TestApp.app")
        assertTrue(isDir(app), "expected $app to exist")
        assertEquals("app 1.0.0", said(app.resolve("Contents/MacOS/TestApp")))
        assertEquals(EntryKind.File, entryKind(app.resolve("Contents/Info.plist")))
        // An app is not a command-line tool: its executables stay out of PATH.
        assertFalse(exists(sandbox.bin.resolve("TestApp")))

        sandbox.ok("uninstall", "testapp", "--yes")
        assertFalse(exists(app), "$app outlived its package")
    }

    @Test
    fun anUpgradeReplacesThePayloadAndTheLinkStillWorks() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        assertEquals("testtool 1.0.0", said(tool))

        sandbox.publishTool("2.0.0")
        assertTrue("2.0.0" in sandbox.ok("outdated"))
        sandbox.ok("upgrade", "--yes")

        assertEquals("testtool 2.0.0", said(tool))
        assertTrue("\"installed\": \"2.0.0\"" in sandbox.ok("list", "local", "--json"))
    }

    @Test
    fun pipedOutputJsonAndTheLogCarryNoEmojiEvenWhenTheyAreWanted() {
        sandbox.publishTool("1.0.0")
        val out = sandbox.ketchOverrides(listOf("install", "test:testtool@1.0.0", "--yes"), mapOf("KETCH_EMOJI" to "1"))
        assertTrue(out.success, "$out")
        assertNoIcon("stderr", out.stderr)
        assertNoIcon("stdout", out.stdout)
        val json = sandbox.okEnv(listOf("list", "local", "--json"), mapOf("KETCH_EMOJI" to "1"))
        assertTrue("\"installed\": \"1.0.0\"" in json, json)
        assertNoIcon("--json", json)
        assertNoIcon("the log", sandbox.log())
    }

    @Test
    fun upgradeInstallsIntoAFreshPrefixThatNothingStaleReaches() {
        val first =
            sandbox.asset(
                darwinTool("1.0.0"),
                tarGz(Entry.program("testtool-1.0.0/bin/testtool", "testtool 1.0.0"), Entry.file("testtool-1.0.0/only-in-1.0.txt", "gone in 2.0\n")),
            )
        sandbox.publish("testtool", listOf(Release("1.0.0", listOf(first))))
        sandbox.ok("install", "test:testtool", "--yes")
        // An interrupted earlier swap to 2.0.0: its staging folder is exactly where the next swap stages the payload.
        val folder = sandbox.store.resolve("testtool")
        writeText(folder.resolve("2.0.0.incoming/planted"), "x")

        sandbox.publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")

        val names = namesUnder(folder.resolve("2.0.0"))
        assertTrue("testtool" in names, "$names")
        assertFalse("only-in-1.0.txt" in names, "$names")
        assertFalse("planted" in names, "$names")
        assertFalse(exists(folder.resolve("2.0.0.incoming")))
    }

    @Test
    fun aForcedReinstallOfTheSameVersionLeavesNoStaleFile() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        val prefix = sandbox.store.resolve("testtool/1.0.0")
        writeText(prefix.resolve("stale.txt"), "x")

        sandbox.ok("install", "test:testtool", "--force", "--yes")

        assertFalse(exists(prefix.resolve("stale.txt")))
        assertEquals("testtool 1.0.0", said(tool))
    }

    @Test
    fun installOfAnInstalledPackageUpdatesItWhenTheAnswerIsYes() {
        sandbox.installedWithAnUpdate()
        val out = sandbox.ketchOnTty(listOf("install", "test:testtool"), "y\n")
        assertTrue(out.success, out.stdout)
        assertTrue("testtool 1.0.0 is installed; update to 2.0.0?" in out.stdout, out.stdout)
        assertEquals("testtool 2.0.0", sandbox.installedVersion(), out.stdout)
    }

    @Test
    fun installOfAnInstalledPackageChangesNothingWhenTheAnswerIsNo() {
        sandbox.installedWithAnUpdate()
        val out = sandbox.ketchOnTty(listOf("install", "test:testtool"), "n\n")
        assertTrue(out.success, out.stdout)
        assertEquals("testtool 1.0.0", sandbox.installedVersion())
    }

    @Test
    fun installWithYesUpdatesAnInstalledPackageWithoutAsking() {
        sandbox.installedWithAnUpdate()
        sandbox.ok("install", "test:testtool", "--yes")
        assertEquals("testtool 2.0.0", sandbox.installedVersion())
    }

    @Test
    fun installWithoutATerminalOrYesRefusesToUpdateAndSaysHow() {
        sandbox.installedWithAnUpdate()
        val out = sandbox.ketch("install", "test:testtool")
        assertEquals(5, out.code, out.stderr)
        assertTrue("2.0.0 is available" in out.stderr, out.stderr)
        assertTrue("--yes" in out.stderr, out.stderr)
        assertTrue("ketch upgrade testtool" in out.stderr, out.stderr)
        assertEquals("testtool 1.0.0", sandbox.installedVersion())
    }

    @Test
    fun installOfAnUpToDatePackageSaysNoUpdateIsAvailable() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        val out = sandbox.ketch("install", "test:testtool")
        assertEquals(5, out.code, out.stderr)
        assertTrue("cannot install `testtool`: 1.0.0 is already installed and no update is available" in out.stderr, out.stderr)
        assertTrue("--force" in out.stderr, out.stderr)
    }

    /** Installed at 1.0.0, then upgraded to 2.0.0: the store keeps 1.0.0 for a rollback. */
    private fun upgradedFromOne() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        sandbox.publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
    }

    @Test
    fun rollbackRestoresThePreviousPrefixWithoutRedownloading() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        val v1 = sandbox.store.resolve("testtool/1.0.0")
        assertTrue(isDir(v1), "1.0.0 should be in the store")

        sandbox.publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        assertEquals("testtool 2.0.0", said(tool))
        assertTrue(isDir(v1), "upgrade must keep the previous prefix")

        // Unpublish 1.0.0 so a redownload would fail.
        sandbox.publishTool("2.0.0")
        sandbox.ok("rollback", "testtool")
        assertEquals("testtool 1.0.0", said(tool))

        val listed = sandbox.ok("list", "local")
        assertTrue("1.0.0" in listed && "retained" in listed, listed)
        val info = sandbox.ok("info", "testtool")
        assertTrue("1.0.0" in info && "2.0.0" in info && "keep 1" in info, info)
        val json = sandbox.ok("info", "testtool", "--json")
        assertTrue("\"installed\": \"1.0.0\"" in json, json)
        assertTrue("\"keep\": 1" in json, json)
        assertTrue(isDir(sandbox.store.resolve("testtool/2.0.0")))
    }

    @Test
    fun rollbackWithoutARetainedVersionLeavesTheInstallAlone() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        val err = sandbox.fails("rollback", "testtool")
        assertTrue("no retained version" in err, err)
        assertEquals("testtool 1.0.0", said(tool))
    }

    @Test
    fun rollbackRefusesAnOccupiedDestinationAndKeepsTheCurrentVersion() {
        upgradedFromOne()
        removeFile(tool)
        writeText(tool, "#!/bin/sh\necho occupied\n")

        val err = sandbox.fails("rollback", "testtool")
        assertTrue("not installed by ketch" in err || "already exists" in err, err)
        assertEquals("#!/bin/sh\necho occupied\n", readText(tool))
        val listed = sandbox.ok("list", "local", "--json")
        assertTrue("\"installed\": \"2.0.0\"" in listed, listed)
    }

    @Test
    fun rollbackRefusesAPinnedPackage() {
        upgradedFromOne()
        sandbox.ok("pin", "testtool")
        val err = sandbox.fails("rollback", "testtool")
        assertTrue("pinned" in err.lowercase(), err)
        assertEquals("testtool 2.0.0", said(tool))
    }

    @Test
    fun uninstallAfterRollbackRemovesEveryRetainedPrefix() {
        upgradedFromOne()
        sandbox.ok("rollback", "testtool")
        sandbox.ok("uninstall", "testtool", "--yes")

        assertFalse(exists(tool))
        assertFalse(exists(sandbox.store.resolve("testtool")))
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
    }

    @Test
    fun pruneAfterUpgradingBackToARetainedVersionKeepsTheLiveStore() {
        upgradedFromOne()
        sandbox.ok("rollback", "testtool")
        sandbox.ok("upgrade", "--yes")
        assertEquals("testtool 2.0.0", said(tool))

        sandbox.ok("prune", "testtool", "--keep", "1")

        assertTrue(isDir(sandbox.store.resolve("testtool/2.0.0")))
        assertEquals("testtool 2.0.0", said(tool))
        val json = sandbox.ok("info", "testtool", "--json")
        assertTrue("\"installed\": \"2.0.0\"" in json, json)
    }

    @Test
    fun installingTheSameNameFromAnotherSourceIsRefused() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        val fork = sandbox.asset("fork-testtool-2.0.0-${hostArch()}-apple-darwin.tar.gz", toolArchive("2.0.0"))
        sandbox.publish("fork/testtool", listOf(Release("2.0.0", listOf(fork))))

        val err = sandbox.fails("install", "test:fork/testtool", "--yes")

        assertTrue("--name" in err, err)
        assertEquals("testtool 1.0.0", said(tool))
        val json = sandbox.ok("info", "testtool", "--json")
        assertTrue("\"installed\": \"1.0.0\"" in json, json)
    }

    @Test
    fun aPinnedPackageIsLeftWhereItIs() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        sandbox.ok("pin", "testtool")
        sandbox.publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        assertEquals("testtool 1.0.0", said(tool))
    }

    @Test
    fun aDownloadThatDoesNotMatchItsChecksumInstallsNothing() {
        val tampered = sandbox.asset(darwinTool("1.0.0"), toolArchive("1.0.0")).withWrongDigest()
        sandbox.publish("testtool", listOf(Release("1.0.0", listOf(tampered))))

        val err = sandbox.fails("install", "test:testtool", "--yes")
        assertTrue("checksum" in err.lowercase(), err)

        // A refused install leaves nothing behind: no link, no store directory, and nothing recorded.
        assertFalse(exists(tool))
        assertFalse(exists(sandbox.store.resolve("testtool")))
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
    }

    @Test
    fun anUpgradeThatDropsTheChecksumIsRefusedWithoutConsent() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        sandbox.publishUnverifiedTool("2.0.0")

        // Without a terminal `upgrade` declines before it installs anything, so the exact-version install is the
        // path that reaches the check unasked.
        val out = sandbox.ketch("install", "test:testtool@2.0.0")
        assertEquals(6, out.code, out.stderr)
        assertTrue("publishes none" in out.stderr, out.stderr)
        assertTrue("--allow-unverified" in out.stderr, out.stderr)
        assertEquals("testtool 1.0.0", said(tool))

        sandbox.ok("install", "test:testtool@2.0.0", "--allow-unverified")
        assertEquals("testtool 2.0.0", said(tool))
        assertTrue("\"checksum_verified\": false" in sandbox.state())
    }

    @Test
    fun yesConsentsToAnUpgradeThatDropsTheChecksum() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        sandbox.publishUnverifiedTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        assertEquals("testtool 2.0.0", said(tool))
    }

    @Test
    fun uninstallRemovesEveryTraceOfATool() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        sandbox.ok("uninstall", "testtool", "--yes")
        assertFalse(exists(tool))
        assertFalse(exists(sandbox.store.resolve("testtool")))
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
    }

    @Test
    fun uninstallRemovesThePackageFolderWithWhatAFailedSwapLeftInIt() {
        sandbox.publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        // What moving into the store leaves when its best-effort cleanup fails.
        val folder = sandbox.store.resolve("testtool")
        writeText(folder.resolve("1.0.0.old/stale"), "x")
        createDirAll(folder.resolve("1.0.0.incoming"))

        sandbox.ok("uninstall", "testtool", "--yes")

        assertFalse(exists(folder), "$folder survived")
    }

    @Test
    fun hooksRunAroundInstallUpgradeRollbackAndUninstallInOrder() {
        val log = sandbox.fixture("hooks.log")
        sandbox.writeHookedManifest(log)
        sandbox.publishTool("1.0.0")

        sandbox.ok("install", "testtool", "--yes")
        sandbox.publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        sandbox.ok("rollback", "testtool")
        sandbox.ok("uninstall", "testtool", "--yes")

        // The prefix is the working directory once it exists: not yet before an install, and no longer after an
        // uninstall. A rollback is an update back to a prefix that is already there.
        val expected =
            """
            before_install 1.0.0 none elsewhere
            after_install 1.0.0 none prefix
            before_update 2.0.0 1.0.0 elsewhere
            after_update 2.0.0 1.0.0 prefix
            before_update 1.0.0 2.0.0 prefix
            after_update 1.0.0 2.0.0 prefix
            before_uninstall 1.0.0 none prefix
            after_uninstall 1.0.0 none elsewhere

            """.trimIndent()
        assertEquals(expected, readText(log))
    }

    @Test
    fun aFailingBeforeInstallHookInstallsNothing() {
        val log = sandbox.fixture("hooks.log")
        sandbox.writeHookedManifest(log, "before_install" to "echo no >&2; exit 7")
        sandbox.publishTool("1.0.0")

        val err = sandbox.fails("install", "testtool", "--yes")
        assertTrue("before_install hook for testtool" in err, err)
        assertTrue("no" in err, "stderr of the hook is the detail: $err")
        assertFalse(exists(tool))
        assertFalse(exists(sandbox.store.resolve("testtool")))
        assertFalse(exists(log), "the after hook ran for an install that did not happen")
    }

    @Test
    fun aBinaryTheUserPutThereIsNeverOverwritten() {
        sandbox.publishTool("1.0.0")
        // Something already occupies the name ketch wants.
        writeText(tool, "#!/bin/sh\necho 'not ketch'\n")

        val err = sandbox.fails("install", "test:testtool", "--yes")
        assertTrue("not installed by ketch" in err, err)
        assertEquals("#!/bin/sh\necho 'not ketch'\n", readText(tool))
    }
}
