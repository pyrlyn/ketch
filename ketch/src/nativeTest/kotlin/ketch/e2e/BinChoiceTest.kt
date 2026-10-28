// Which binary a package links when its release ships several sharing the package's name and no manifest names one
// (Rust tests/bin_choice.rs, B64). Stdin is never a terminal here, so these are the paths without a prompt; the order
// the prompt sits in is proved by the bin_choice unit tests.
package ketch.e2e

import fs.readText
import ketch.core.config.HOST_WINDOWS
import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.binProgram
import ketch.e2e.support.fails
import ketch.e2e.support.ketch
import ketch.e2e.support.nativeArchive
import ketch.e2e.support.nativeName
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.said
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinChoiceTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    /** Publish one `rtok` release whose payload holds [programs], each printing its own name and the version. */
    private fun publish(
        version: String,
        vararg programs: String,
    ) {
        val entries = programs.map { Entry.program("rtok-$version/$it", "$it $version") } + Entry.file("rtok-$version/README.md", "# rtok\n")
        val asset = sandbox.asset(nativeName("rtok", version), nativeArchive(entries))
        sandbox.publish("rtok", listOf(Release(version, listOf(asset))))
    }

    /** Where the program called [name] lands in the bin dir. */
    private fun linked(name: String) = sandbox.binProgram(name)

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    private fun setBinChoice(choice: String) = sandbox.editPackage("rtok") { JsonObject(it + ("bin_choice" to JsonPrimitive(choice))) }

    /** A user manifest whose one `bin` entry globs `rtok*`, with [extra] added to the entry. */
    private fun globManifest(extra: String) = writeText(sandbox.root.resolve("manifests/rtok.toml"), "name   = \"rtok\"\nsource = \"test:rtok\"\nbin = [{ path = \"rtok*\"$extra }]\n")

    @Test
    fun theBinaryNamedLikeThePackageIsLinkedAndItsHelperIsNot() {
        publish("1.0.0", "rtok", "rtok-hook")
        sandbox.ok("install", "test:rtok@1.0.0", "--yes")
        assertEquals("rtok 1.0.0", said(linked("rtok")))
        assertFalse(exists(linked("rtok-hook")), "the helper must not be linked beside the command it serves")
    }

    @Test
    fun withoutATerminalAnAmbiguousReleaseFailsAndNamesTheCandidates() {
        publish("1.0.0", "rtok-cli", "rtok-hook")
        val err = sandbox.fails("install", "test:rtok@1.0.0")
        assertTrue("rtok-cli" in err, err)
        assertTrue("rtok-hook" in err, err)
        assertTrue("bin = [{ name = " in err, err)
        assertFalse(exists(linked("rtok-cli")))
        assertFalse(exists(linked("rtok-hook")))
    }

    @Test
    fun aRememberedChoiceIsReusedOnUpgrade() {
        publish("1.0.0", "rtok-cli")
        sandbox.ok("install", "test:rtok@1.0.0", "--yes")
        assertEquals("rtok-cli 1.0.0", said(linked("rtok-cli")))

        // 2.0.0 adds a second binary sharing the name, and nothing says which.
        publish("2.0.0", "rtok-cli", "rtok-hook")
        val err = sandbox.ketch("upgrade", "--yes").stderr
        assertTrue("rtok-hook" in err, err)
        assertEquals("rtok-cli 1.0.0", said(linked("rtok-cli")))

        // What a pick at a terminal would have left behind.
        setBinChoice("rtok-cli")
        sandbox.ok("upgrade", "--yes")

        assertEquals("rtok-cli 2.0.0", said(linked("rtok-cli")))
        assertFalse(exists(linked("rtok-hook")))
        assertTrue("\"bin_choice\": \"rtok-cli" in sandbox.state(), sandbox.state())
    }

    @Test
    fun aUserManifestGetsTheChosenBinaryWrittenIntoIt() {
        publish("1.0.0", "rtok", "rtok-hook", "other-tool")
        val file = sandbox.root.resolve("manifests/rtok.toml")
        val original = "# my own rtok\nname   = \"rtok\"\nsource = \"test:rtok\"\n"
        writeText(file, original)

        sandbox.ok("install", "rtok", "--yes")

        // Windows would link a bare name as `.exe`, so a script keeps its own. A bare name equal to the package leads;
        // a `.cmd` does not, since only `.exe` is folded away, so on Windows the two stay alphabetical.
        val (first, second) = if (HOST_WINDOWS) "other-tool.cmd" to "rtok.cmd" else "rtok" to "other-tool"
        val written = readText(file)
        assertEquals("${original}bin = [{ name = \"$first\" }, { name = \"$second\" }]\n", written, "everything but the new entry must be left as it was")
        assertEquals("rtok 1.0.0", said(linked("rtok")))
        assertEquals("other-tool 1.0.0", said(linked("other-tool")))
        assertFalse(exists(linked("rtok-hook")))

        // The file now names its binaries, so a reinstall leaves it alone.
        sandbox.ok("install", "rtok", "--force", "--yes")
        assertEquals(written, readText(file))
        assertEquals("rtok 1.0.0", said(linked("rtok")))
    }

    @Test
    fun onlyTheLosingFamilyMembersAreDroppedAndOtherBinariesStayLinked() {
        publish("1.0.0", "rtok", "rtok-hook", "other-tool")
        sandbox.ok("install", "test:rtok@1.0.0", "--yes")
        assertEquals("rtok 1.0.0", said(linked("rtok")))
        assertEquals("other-tool 1.0.0", said(linked("other-tool")))
        assertFalse(exists(linked("rtok-hook")))
    }

    @Test
    fun theBinFlagChoosesWithoutATerminalAndIsRemembered() {
        publish("1.0.0", "rtok-cli", "rtok-hook")
        sandbox.ok("install", "test:rtok@1.0.0", "--bin", "rtok-cli")

        assertEquals("rtok-cli 1.0.0", said(linked("rtok-cli")))
        assertFalse(exists(linked("rtok-hook")))
        assertTrue("\"bin_choice\": \"rtok-cli" in sandbox.state(), sandbox.state())

        publish("2.0.0", "rtok-cli", "rtok-hook")
        sandbox.ok("upgrade", "--yes")
        assertEquals("rtok-cli 2.0.0", said(linked("rtok-cli")))
        assertFalse(exists(linked("rtok-hook")))
    }

    @Test
    fun theBinFlagWinsOverTheBinaryNamedLikeThePackage() {
        publish("1.0.0", "rtok", "rtok-hook")
        sandbox.ok("install", "test:rtok@1.0.0", "--bin", "rtok-hook")
        assertEquals("rtok-hook 1.0.0", said(linked("rtok-hook")))
        assertFalse(exists(linked("rtok")))
    }

    @Test
    fun aBinFlagNamingNoBinaryFailsAndListsWhatThereIs() {
        publish("1.0.0", "rtok-cli", "rtok-hook")
        var err = sandbox.fails("install", "test:rtok@1.0.0", "--bin", "rtok-typo")
        assertTrue("--bin `rtok-typo`" in err, err)
        assertTrue("rtok-cli" in err, err)
        assertFalse(exists(linked("rtok-cli")))

        // Nothing to choose between is no excuse for a name that matches nothing.
        publish("2.0.0", "rtok")
        err = sandbox.fails("install", "test:rtok@2.0.0", "--bin", "rtok-typo")
        assertTrue("--bin `rtok-typo`" in err, err)
        assertFalse(exists(linked("rtok")))
    }

    @Test
    fun syncRepeatsTheChoiceTheLockfileRecordedWithoutATerminal() {
        publish("1.0.0", "rtok-cli", "rtok-hook")
        val lock = sandbox.home.resolve("ketch.lock")

        sandbox.ok("install", "test:rtok@1.0.0", "--bin", "rtok-cli")
        sandbox.ok("lock", "--file", lock.toString())
        sandbox.ok("uninstall", "rtok", "--yes")
        val text = readText(lock)
        assertTrue("bin = \"rtok-cli" in text, text)

        // Without the recorded choice a machine with no state has nothing to go by.
        val bare = sandbox.home.resolve("bare.lock")
        writeText(bare, text.lines().filter { it.isNotEmpty() && !it.startsWith("bin = ") }.joinToString("") { "$it\n" })
        val err = sandbox.fails("sync", "--file", bare.toString())
        assertTrue("rtok-hook" in err, err)

        sandbox.ok("sync", "--file", lock.toString())
        assertEquals("rtok-cli 1.0.0", said(linked("rtok-cli")))
        assertFalse(exists(linked("rtok-hook")))
    }

    @Test
    fun aBinGlobMatchingSeveralFilesWithNoNameRefusesAndListsThemSorted() {
        publish("1.0.0", "rtok-hook", "rtok")
        globManifest("")

        val err = sandbox.fails("install", "rtok", "--yes")

        assertTrue("`rtok*` matches 2 files" in err, err)
        // Sorted and relative to the payload, so the message is the same on every OS.
        val listed = err.lines().filter { it.startsWith("  rtok") }
        assertEquals(2, listed.size, err)
        assertEquals(listed.sorted(), listed, err)
        assertTrue("set the entry's `name`" in err, err)
        assertFalse(exists(linked("rtok")))
        assertFalse(exists(linked("rtok-hook")))
    }

    @Test
    fun aBinGlobMatchingSeveralFilesLinksTheOneNamedLikeTheEntry() {
        publish("1.0.0", "rtok-hook", "rtok")
        // Windows links a bare name as `.exe`, so the script's entry keeps its suffix; the stem still names the file.
        globManifest(", name = \"${if (HOST_WINDOWS) "rtok.cmd" else "rtok"}\"")
        sandbox.ok("install", "rtok", "--yes")
        assertEquals("rtok 1.0.0", said(linked("rtok")))
        assertFalse(exists(linked("rtok-hook")))
    }
}
