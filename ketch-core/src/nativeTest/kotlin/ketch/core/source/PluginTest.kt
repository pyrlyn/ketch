package ketch.core.source

import fs.setMode
import ketch.core.error.KetchError
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PluginTest {
    private fun writePlugin(
        dir: Path,
        scheme: String,
        body: String,
    ): Path {
        val path = Path(dir, "$PLUGIN_PREFIX$scheme")
        path.write(body)
        setMode(path, "755".toInt(8))
        return path
    }

    /** A plugin is just an executable; the smallest honest one is a shell case. */
    private fun fakePlugin(
        dir: Path,
        scheme: String,
        protocol: Int,
    ) = writePlugin(
        dir,
        scheme,
        """
        #!/bin/sh
        case "${'$'}1" in
          capabilities) echo '{"protocol":$protocol,"scheme":"$scheme","search":true}' ;;
          releases) echo '[{"tag":"v1.0.0","version":"1.0.0","assets":[]},
                           {"tag":"v2.0.0-rc1","version":"2.0.0-rc1","prerelease":true,"assets":[]},
                           {"tag":"v3.0.0","version":"3.0.0","draft":true,"assets":[]}]' ;;
          *) exit 1 ;;
        esac
        """.trimIndent() + "\n",
    )

    @Test
    fun aPluginThatNeverFinishesIsKilled(): Unit =
        withTempDir { dir ->
            val path = writePlugin(dir, "never", "#!/bin/sh\nsleep 60\n")
            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<KetchError.Plugin>("a hung plugin must not be waited on forever") {
                runPlugin(path, listOf("capabilities"), 100.milliseconds)
            }
            assertTrue(started.elapsedNow() < 5.seconds)
        }

    @Test
    fun aPluginThatOrphansAChildHoldingStdoutIsStoppedWithinTheDeadline(): Unit =
        withTempDir { dir ->
            // sh waits for `&` jobs when the script exits; exec replaces the shell so it cannot. The sleeper keeps the
            // inherited stdout pipe.
            val path =
                writePlugin(
                    dir,
                    "orphan",
                    """
                    #!/bin/sh
                    case "${'$'}1" in
                      capabilities)
                        sleep 5 &
                        exec /usr/bin/printf '%s\n' '{"protocol":$PROTOCOL_VERSION,"scheme":"orphan"}'
                        ;;
                      *) exit 1 ;;
                    esac
                    """.trimIndent() + "\n",
                )
            val started = TimeSource.Monotonic.markNow()
            val outcome = runCatching { runPlugin(path, listOf("capabilities"), 2.seconds) }
            val elapsed = started.elapsedNow()
            assertTrue(elapsed < 4.seconds, "must return within the deadline, not after the grandchild's sleep (took $elapsed)")
            assertTrue(outcome.isSuccess, "stdout should be read once the grandchild is stopped: $outcome")
        }

    @Test
    fun aPluginCannotWriteWithoutEnd(): Unit =
        withTempDir { dir ->
            val path = writePlugin(dir, "flood", "#!/bin/sh\nexec yes xxxxxxxxxxxxxxx\n")
            val err = assertFailsWith<KetchError.Plugin> { runPlugin(path, listOf("capabilities"), 10.seconds) }
            assertTrue(err.detail.startsWith("wrote more than $PLUGIN_MAX_OUTPUT bytes"), err.detail)
        }

    @Test
    fun probesAPluginAndFiltersWhatItReturns(): Unit =
        withTempDir { dir ->
            val plugin = PluginSource.probe(fakePlugin(dir, "demo", PROTOCOL_VERSION), Report.silent())
            assertEquals("demo", plugin.scheme())

            // Drafts always go; prereleases only when asked for, unless they are all the project has shipped.
            val stable = plugin.listReleases("x/y", ListOpts())
            assertEquals(listOf("v1.0.0"), stable.map { it.tag }, "draft and prerelease must be dropped")
            assertEquals(2, plugin.listReleases("x/y", ListOpts(includePrerelease = true)).size)

            // Unsupported subcommands surface as errors, not as empty results.
            assertFailsWith<KetchError> { plugin.describe("x/y") }
        }

    @Test
    fun aPluginWithOnlyPrereleasesIsStillInstallable(): Unit =
        withTempDir { dir ->
            val path =
                writePlugin(
                    dir,
                    "pre",
                    """
                    #!/bin/sh
                    case "${'$'}1" in
                      capabilities) echo '{"protocol":$PROTOCOL_VERSION,"scheme":"pre"}' ;;
                      releases) echo '[{"tag":"v2.0.0-rc1","version":"2.0.0-rc1","prerelease":true,"assets":[]},
                                       {"tag":"v3.0.0","version":"3.0.0","draft":true,"assets":[]}]' ;;
                      *) exit 1 ;;
                    esac
                    """.trimIndent() + "\n",
                )
            val releases = PluginSource.probe(path, Report.silent()).listReleases("x/y", ListOpts())
            assertEquals(listOf("v2.0.0-rc1"), releases.map { it.tag })
            assertTrue(releases[0].prerelease)
        }

    @Test
    fun refusesAProtocolItDoesNotSpeak(): Unit =
        withTempDir { dir ->
            val err = assertFailsWith<KetchError.Plugin> { PluginSource.probe(fakePlugin(dir, "future", PROTOCOL_VERSION + 1), Report.silent()) }
            assertTrue("speaks protocol ${PROTOCOL_VERSION + 1}" in err.detail, err.detail)
        }

    @Test
    fun aSchemeThatCannotBeTypedIsRefused(): Unit =
        withTempDir { dir ->
            val path = writePlugin(dir, "odd", "#!/bin/sh\necho '{\"protocol\":$PROTOCOL_VERSION,\"scheme\":\"a/b\"}'\n")
            val err = assertFailsWith<KetchError.Plugin> { PluginSource.probe(path, Report.silent()) }
            assertTrue("unusable scheme" in err.detail, err.detail)
        }

    @Test
    fun aPluginThatHangsIsStopped(): Unit =
        withTempDir { dir ->
            val path = writePlugin(dir, "slow", "#!/bin/sh\ncase \"\$1\" in\n  capabilities) sleep 60 ;;\nesac\n")
            // Stderr after SIGKILL is best-effort; the contract under test is that a hung plugin is stopped.
            val err = assertFailsWith<KetchError.Plugin> { runPlugin(path, listOf("capabilities"), 100.milliseconds) }
            assertTrue("did not answer" in err.detail && "stopped" in err.detail, err.detail)
        }

    @Test
    fun aPluginFailureCarriesItsStderrIntoTheDetails() {
        val path = Path("plugins/ketch-source-demo")
        val oversize = pluginFail(path, "wrote more than 8388608 bytes to stdout", "oversize stderr\n".encodeToByteArray())
        assertTrue(oversize.details().any { "oversize stderr" in it }, oversize.toString())
        val utf8 = pluginFail(path, "wrote output that is not UTF-8: invalid utf-8", "utf8 stderr\n".encodeToByteArray())
        assertTrue(utf8.details().any { "utf8 stderr" in it }, utf8.toString())
    }

    @Test
    fun httpsAndLoopbackHttpAreAllowedAndAnythingElseIsNot() {
        assertTrue(allowedAssetUrl("https://example.com/a.tar.gz"))
        assertTrue(allowedAssetUrl("http://127.0.0.1:9/a.bin"))
        assertTrue(allowedAssetUrl("http://localhost/a.bin"))
        assertFalse(allowedAssetUrl("http://evil.example/a.bin"))
        assertFalse(allowedAssetUrl("http://localhost.evil.example/a.bin"))
        assertFalse(allowedAssetUrl("file:///etc/passwd"))
        assertFalse(allowedAssetUrl("ftp://127.0.0.1/a.bin"))
    }
}
