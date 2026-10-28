package ketch.core.platform

import fs.createDir
import fs.createDirAll
import fs.entryKind
import fs.exactName
import fs.listDir
import ketch.core.error.KetchError
import ketch.core.model.CompletionShell
import ketch.core.model.LinkRole
import ketch.core.testing.program
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun siblingAppendsTheSuffixToTheFileName() {
        val path = Path("store", "tool", "1.0")
        assertEquals(Path("store", "tool", "1.0.old"), sibling(path, ".old"))
        assertEquals(Path("store", "tool", "1.0.incoming"), sibling(path, ".incoming"))
    }

    @Test
    fun siblingOfANamelessPathUsesPkg() {
        assertEquals(Path("pkg.old"), sibling(Path(""), ".old"))
    }

    /**
     * Rust's `sibling_keeps_a_non_utf8_file_name`, for the names a Kotlin `Path` can hold. A `String` cannot carry bytes
     * that are not UTF-8, but it does carry an unpaired surrogate, which is how a Windows name that is not valid
     * Unicode reads; the suffix must not repair or drop it.
     */
    @Test
    fun siblingKeepsAFileNameThatIsNotValidUnicode() {
        val name = "\uDCFF\uD800"
        assertEquals("$name.old", sibling(Path("store", name), ".old").exactName)
    }

    @Test
    fun swapIntoStoreOfTheSamePathDoesNotRetireIt() {
        withTempDir { tmp ->
            val store = Path(tmp, "store")
            createDirAll(store)
            Path(store, "tool").write("working")
            var copied = false
            assertFalse(swapIntoStore(store, store) { _, _ -> copied = true })
            assertFalse(copied)
            assertEquals("working", Path(store, "tool").read())
        }
    }

    @Test
    fun swapIntoStoreRetiresThePreviousTreeAndRestorePutsItBack() {
        withTempDir { tmp ->
            val store = Path(tmp, "store/tool/1.0")
            createDirAll(store)
            Path(store, "tool").write("working")
            val payload = Path(tmp, "payload")
            createDirAll(payload)
            Path(payload, "tool").write("new")
            assertTrue(swapIntoStore(payload, store) { _, _ -> throw KetchError.Msg("rename should stay on one filesystem") })
            assertEquals("new", Path(store, "tool").read())
            assertEquals("working", Path(sibling(store, ".old"), "tool").read())
            restoreRetiredStore(store)
            assertEquals("working", Path(store, "tool").read())
            assertNull(entryKind(sibling(store, ".old")))
            finalizeStoreSwap(store)
        }
    }

    @Test
    fun swapIntoStoreCopiesWhenTheRenameOntoTheStageFails() {
        withTempDir { tmp ->
            val store = Path(tmp, "store/1.0")
            val payload = Path(tmp, "missing-payload")
            // A missing source fails the rename, which is the same branch a cross-filesystem rename takes before
            // `copy` runs.
            val swapped =
                swapIntoStore(payload, store) { from, to ->
                    assertEquals(payload, from)
                    createDir(to)
                    Path(to, "tool").write("copied")
                }
            assertFalse(swapped)
            assertEquals("copied", Path(store, "tool").read())
        }
    }

    @Test
    fun onlyAPidAndTimeStampedAsideOfTheLinkIsOne() {
        for (ours in listOf("tool.exe.old.4242-1700000000000", "Tool.EXE.old.1-2")) {
            assertTrue(isBusyAsideOf("tool.exe", ours), ours)
        }
        val theirs =
            listOf(
                "tool.exe",
                "tool.exe.old",
                "tool.exe.old.",
                "tool.exe.old.backup",
                "tool.exe.old.12-",
                "tool.exe.old.-12",
                "tool.exe.old.1-2-3",
                "tool.exe.old.1-2x",
                "other.exe.old.1-2",
                "xtool.exe.old.1-2",
            )
        for (name in theirs) assertFalse(isBusyAsideOf("tool.exe", name), name)
    }

    /** What `read_dir` lists, in whatever order the file system keeps it. */
    private fun listed(dir: Path) = listDir(dir).map { Path(dir, it) }

    @Test
    fun aWindowsStyleExePairChoosesThePackageNameOverTheHook() {
        withTempDir { tmp ->
            // Written hook-first. Alphabetical order still picks the hook, because `-` sorts before `.` in
            // `rtok-hook.exe` / `rtok.exe`.
            Path(tmp, "rtok-hook.exe").write("hook")
            Path(tmp, "rtok.exe").write("main")
            val found = orderDiscoveredExecutables(listed(tmp), "rtok")
            assertEquals("rtok.exe", found[0].name, "$found")
        }
    }

    @Test
    fun anUnsuffixedPairChoosesThePackageNameOverTheHook() {
        withTempDir { tmp ->
            Path(tmp, "rtok-hook").write("hook")
            Path(tmp, "rtok").write("main")
            val found = orderDiscoveredExecutables(listed(tmp), "rtok")
            assertEquals("rtok", found[0].name, "$found")
        }
    }

    @Test
    fun placeChoosesThePackageNamedBinaryOverASimilarlyNamedHook() {
        withTempDir { tmp ->
            val payload = Path(tmp, "payload")
            val shipped = Path(payload, "bin")
            // The Windows spelling of `rtok` and `rtok-hook`. On every OS a plain sort of these two names selects the
            // hook.
            program(shipped, "rtok-hook.exe")
            program(shipped, "rtok.exe")
            val store = Path(tmp, "store/rtok/1.0")
            val bin = Path(tmp, "bin")
            createDirAll(bin)
            val links = host().place(placement(payload, store, bin, tmp, name = "rtok"))
            val binaries = links.filter { it.role == LinkRole.Binary }
            assertEquals(2, binaries.size, "$links")
            assertEquals("rtok.exe", binaries[0].target.name, "$binaries")
            assertTrue(binaries.any { it.target.name == "rtok-hook.exe" }, "$binaries")
        }
    }

    @Test
    fun extraDestinationChecksNameManAndEachShell() {
        val checks = extraDestinationChecks(host())
        val names = checks.map { it.name }
        for (name in listOf("man", "completions-bash", "completions-zsh", "completions-fish")) {
            assertTrue(name in names, "$names")
        }
        val man = checks.firstOrNull { it.name == "man" }
        assertTrue(man?.detail.orEmpty().contains("MANPATH"), man?.detail.orEmpty())
    }

    @Test
    fun userManRootIsUnderXdgDataHome() {
        assertEquals(Path(dataHome(), "man"), host().userManRoot())
        assertEquals(Path(dataHome(), "bash-completion/completions"), host().completionDir(CompletionShell.Bash))
    }

    @Test
    fun pathIsWithinAcceptsADescendant() {
        val root = Path("/Users/ivan/.ketch/store")
        assertTrue(pathIsWithin(Path("/Users/ivan/.ketch/store/rg/1.0"), root))
        assertFalse(pathIsStrictWithin(root, root))
        assertFalse(pathIsWithin(Path("/Users/ivan/.ketch/other"), root))
    }

    @Test
    fun pathIsWithinFoldsAsciiCaseOnWindows() {
        val root = "C:\\Users\\ivan\\.ketch\\store"
        val folded = "C:\\Users\\IVAN\\.ketch\\store\\rg\\1.0"
        assertTrue(pathIsWithinFor(folded, root, windows = true))
        assertTrue(pathIsStrictWithinFor(folded, root, windows = true))
        assertFalse(pathIsStrictWithinFor("C:\\Users\\IVAN\\.ketch\\store", root, windows = true))
        assertFalse(pathIsWithinFor(folded, root, windows = false))
    }

    // kotlinx-io joins with `/` on Windows too, so a store path ketch builds is spelled with both separators.
    @Test
    fun windowsPathsMatchAcrossSeparatorsAndDriveCase() {
        val root = "C:\\Users\\ivan\\.ketch"
        assertTrue(pathIsWithinFor("c:/Users/ivan/.ketch/store/rg", root, windows = true))
        assertFalse(pathIsWithinFor("D:\\Users\\ivan\\.ketch\\store", root, windows = true))
        assertFalse(pathIsWithinFor("C:\\Users\\ivan\\.ketchup", root, windows = true))
        assertFalse(pathIsWithinFor("x", "", windows = true))
    }
}
