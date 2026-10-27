@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.tomlfile

import fs.EntryKind
import fs.canonicalize
import fs.entryKind
import fs.listDir
import fs.readText
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getcwd
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TomlOwnerTest {
    /**
     * Whether [line] names a TOML library package (`toml`, `toml.ktoml`, `tomlaot`). Whole names only: `tomlfile`,
     * `ketch.toml` and a function called `readsToml` are not the library, and a substring search would fail on each.
     */
    private fun namesATomlLibrary(line: String) = TOML_LIBRARY.containsMatchIn(line)

    @Test
    fun theScanTellsTheLibraryFromOtherUsesOfTheWord() {
        for (line in listOf(
            "import toml.TomlWriter",
            "import toml.ktoml.KtomlReader",
            "import tomlaot.TomlDoc",
            "    val doc = tomlaot.TomlDoc.parse(text)",
            "toml.ktoml.KtomlReader.parse(text)",
        )) {
            assertTrue(namesATomlLibrary(line), line)
        }
        for (line in listOf(
            "import ketch.core.tomlfile.parse",
            "tomlfile.parse(text, what)",
            "val path = Path(root, \"ketch.toml\")",
            "fun readsToml() {}",
            "// a toml file",
            "val registryToml = 1",
            "import tomlish.X",
        )) {
            assertFalse(namesATomlLibrary(line), line)
        }
    }

    /** Tests are left out: they write fixtures, and are not the code the guard protects. */
    @Test
    fun onlyTheOwnerFileNamesTheTomlLibraries() {
        val root = repositoryRoot()
        val sources = mutableListOf<Path>()
        for (module in listDir(root)) {
            val src = Path(root, module, "src")
            if (entryKind(src) != EntryKind.Dir) continue
            listDir(src).filter { it.endsWith("Main") }.forEach { kotlinFiles(Path(src, it), sources) }
        }
        val owner = canonicalize(Path(root, "ketch-core/src/commonMain/kotlin/ketch/core/tomlfile/TomlFile.kt"))
        assertTrue(sources.any { canonicalize(it) == owner }, "the scan misses its own module")
        val offenders =
            sources.filter { canonicalize(it) != owner }.flatMap { path ->
                readText(path).lines().withIndex().filter { namesATomlLibrary(it.value) }.map { (n, line) ->
                    "${path.toString().removePrefix("$root/")}:${n + 1}: ${line.trim()}"
                }
            }
        assertTrue(offenders.isEmpty(), "only TomlFile.kt may use the TOML libraries; go through it instead:\n${offenders.joinToString("\n")}")
    }

    private fun kotlinFiles(
        dir: Path,
        out: MutableList<Path>,
    ) {
        for (name in listDir(dir)) {
            val path = Path(dir, name)
            when {
                entryKind(path) == EntryKind.Dir -> kotlinFiles(path, out)
                name.endsWith(".kt") -> out += path
            }
        }
    }

    /** The test runs in its module's directory; the repository root is the nearest one with the Gradle settings. */
    private fun repositoryRoot(): Path {
        var dir = canonicalize(Path(checkNotNull(getcwd(null, 0u)).toKString()))
        while (entryKind(Path(dir, "settings.gradle.kts")) != EntryKind.File) dir = checkNotNull(dir.parent) { "no settings.gradle.kts above the test" }
        return dir
    }

    private companion object {
        val TOML_LIBRARY = Regex("""(?<![\w.])(toml|tomlaot)\.(?=[A-Za-z])""")
    }
}
