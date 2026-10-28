// Documentation-style command snapshots for compact, stable output (Rust tests/trycmd.rs and tests/cases/*.trycmd,
// kept as they are). Each ```console block holds `$ ketch …` lines, each followed by what it prints.
@file:OptIn(ExperimentalForeignApi::class)

package ketch.e2e

import fs.listDir
import fs.readText
import ketch.core.VERSION
import ketch.core.selfupdate.VERSION_CHANNEL
import ketch.e2e.support.KETCH_EXE
import ketch.e2e.support.resolve
import ketch.e2e.support.runOnce
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv
import process.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrycmdTest {
    private class Case(
        val file: String,
        val args: List<String>,
        val expected: String,
    )

    private fun cases(dir: Path): List<Case> =
        listDir(dir).filter { it.endsWith(".trycmd") }.sorted().flatMap { name ->
            val cases = mutableListOf<Case>()
            var args: List<String>? = null
            val body = StringBuilder()

            fun flush() {
                args?.let { cases += Case(name, it, body.toString()) }
                args = null
                body.clear()
            }
            for (line in readText(dir.resolve(name)).lines()) {
                when {
                    line.startsWith("```") -> {
                        flush()
                    }

                    line.startsWith("$ ") -> {
                        flush()
                        args =
                            line
                                .removePrefix("$ ")
                                .split(' ')
                                .filter { it.isNotEmpty() }
                                .drop(1)
                    }

                    args != null -> {
                        body.append(line).append('\n')
                    }
                }
            }
            cases
        }

    // `--features tui` adds a global `--tui` flag to every help page in Rust; this port has no tui, so the snapshots
    // are the default CLI contract. The version lives in one place, substituted rather than written into the cases.
    @Test
    fun commandSnapshotsMatchThePublicCliContract() {
        val dir = Path(checkNotNull(getenv("TRYCMD_DIR")?.toKString()) { "TRYCMD_DIR is not set; run the tests through Gradle" })
        val all = cases(dir)
        assertTrue(all.isNotEmpty(), "no cases under $dir")
        for (case in all) {
            val out = Command(KETCH_EXE, case.args).runOnce()
            assertTrue(out.success, "${case.file}: `ketch ${case.args.joinToString(" ")}` failed with $out")
            val expected = case.expected.replace("[VERSION]", "$VERSION · $VERSION_CHANNEL").replace("[EXE]", "")
            assertEquals(expected.trimEnd(), (out.stdout + out.stderr).trimEnd(), "${case.file}: `ketch ${case.args.joinToString(" ")}`")
        }
    }
}
