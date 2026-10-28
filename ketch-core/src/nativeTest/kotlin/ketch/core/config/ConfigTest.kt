package ketch.core.config

import fs.createDirAll
import fs.entryKind
import ketch.core.error.KetchError
import ketch.core.log.Format
import ketch.core.log.Level
import ketch.core.report.Report
import ketch.core.testing.setEnv
import ketch.core.testing.withEnv
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import ketch.core.tomlfile.parse
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rust `Config::load` with nothing to report to. */
internal fun load(root: Path) = Config.load(root, Report.silent())

class ConfigTest {
    @Test
    fun resolvesRelativeRootsAgainstTheCurrentDirectory() {
        val root = absolutePath(Path("scratch"))
        assertTrue(root.isAbsolute)
        assertEquals(Path(currentDir()!!, "scratch"), root)
    }

    @Test
    fun rejectsUnrecognizedBooleanEnvironmentValues() {
        val key = "KETCH_TEST_BOOLEAN"
        val error = withEnv(emptyList(), mapOf(key to "sometimes")) { assertFailsWith<KetchError.Config> { envBool(key) } }
        assertTrue(key in error.message.orEmpty())
    }

    @Test
    fun anEmptyKetchGithubTokenFallsBackToTheNextTokenVariable() {
        // An ambient GH_TOKEN would not beat GITHUB_TOKEN, but it is kept out of the way all the same.
        val cfg =
            withEnv(listOf("GH_TOKEN"), mapOf("KETCH_GITHUB_TOKEN" to "", "GITHUB_TOKEN" to "ghp_fallback")) {
                withTempDir { load(it) }
            }
        assertEquals("ghp_fallback", cfg.githubToken)
    }

    @Test
    fun anEmptyBooleanEnvironmentVariableIsTreatedAsUnset() {
        val key = "KETCH_TEST_BOOLEAN_EMPTY"
        withEnv(emptyList(), mapOf(key to "")) { assertNull(envBool(key)) }
    }

    @Test
    fun expandTildeAcceptsSlashBackslashAndBareHome() {
        val home = homeDir()!!
        assertEquals(home, expandTilde(Path("~")))
        assertEquals(Path(home, "scratch"), expandTilde(Path("~/scratch")))
        assertEquals(Path(home, ".ketch"), expandTilde(Path("~\\.ketch")))
        assertEquals(Path("/abs"), expandTilde(Path("/abs")))
        // Multi-component PowerShell paths must not collapse into one segment.
        assertEquals(Path(Path(home, ".ketch"), "bin"), expandTilde(Path("~\\.ketch\\bin")))
        assertEquals(Path(Path(home, "scratch"), "nested"), expandTilde(Path("~/scratch\\nested")))
    }

    @Test
    fun aWindowsPathKeepsAQuotedSemicolonInsideItsEntry() {
        assertEquals(listOf("c:\\foo", "c:\\some;dir", "c:\\bar"), splitWindowsPathVar("c:\\foo;c:\\som\"e;di\"r;c:\\bar"))
        assertEquals(listOf("C:\\Program Files\\x", "", "y", ""), splitWindowsPathVar("\"C:\\Program Files\\x\";;y;"))
        assertEquals(emptyList(), splitWindowsPathVar(""))
        assertEquals(emptyList(), splitWindowsPathVar("\"\""))
    }

    @Test
    fun pathLookupKeyFoldsCaseSeparatorsAndTrailingSlash() {
        assertEquals(pathLookupKey("C:\\Users\\u\\.ketch\\bin"), pathLookupKey("C:/Users/u/.ketch/bin/"))
        if (HOST_WINDOWS) assertEquals(pathLookupKey("C:\\Users\\U\\.ketch\\bin"), pathLookupKey("c:\\users\\u\\.ketch\\bin"))
    }

    @Test
    fun binDirOnPathMatchesFoldedWindowsEntries(): Unit =
        withTempDir { dir ->
            val cfg = load(dir)
            val mixed = cfg.binDir.toString().replace('\\', '/')
            val on = withEnv(emptyList(), mapOf("PATH" to listOf("/elsewhere", "$mixed/").joinToString(if (HOST_WINDOWS) ";" else ":"))) { cfg.binDirOnPath() }
            assertTrue(on, "folded PATH entry must count as on PATH")
        }

    @Test
    fun defaultTomlParsesBackToCompiledDefaults() {
        val file = parse<ConfigFile>(Config.defaultToml(), "default")
        assertEquals(false, file.prerelease)
        assertEquals(true, file.allowEmulation)
        assertEquals(false, file.linkApps)
        assertEquals(false, file.requireChecksums)
        assertEquals(true, file.stripQuarantine)
        assertEquals(true, file.autoUpdate)
        assertEquals(true, file.emoji)
        assertEquals(SELF_REPO, file.selfRepo)
        assertEquals(REGISTRY_REPO, file.registry)
        assertEquals(4uL, file.jobs)
        assertEquals(DEFAULT_MAX_DOWNLOAD_MIB, file.maxDownloadMib)
        assertEquals(Level.Info, file.logLevel)
        assertEquals(Format.Text, file.logFormat)
        assertNull(file.appsDir)
        assertNull(file.githubToken)
        assertNull(file.root)
    }

    @Test
    fun aResetFileLoadsBackToTheEffectiveDefaults() {
        val keys =
            listOf(
                "KETCH_ROOT",
                "KETCH_APPS_DIR",
                "KETCH_SELF_REPO",
                "KETCH_REGISTRY",
                "KETCH_GITHUB_TOKEN",
                "GITHUB_TOKEN",
                "GH_TOKEN",
                "KETCH_LOG_LEVEL",
                "KETCH_LOG_FORMAT",
                "KETCH_JOBS",
                "KETCH_PRERELEASE",
                "KETCH_ALLOW_EMULATION",
                "KETCH_LINK_APPS",
                "KETCH_REQUIRE_CHECKSUMS",
                "KETCH_STRIP_QUARANTINE",
                "KETCH_AUTO_UPDATE",
                "KETCH_EMOJI",
                "KETCH_MAX_DOWNLOAD_MIB",
            )
        withEnv(keys) {
            withTempDir { dir ->
                val root = Path(dir, "root")
                createDirAll(root)
                Path(root, "config.toml").write(Config.defaultToml())
                val cfg = load(root)
                assertFalse(cfg.prerelease)
                assertTrue(cfg.allowEmulation)
                assertFalse(cfg.linkApps)
                assertFalse(cfg.requireChecksums)
                assertTrue(cfg.stripQuarantine)
                assertTrue(cfg.autoUpdate)
                assertTrue(cfg.emoji)
                assertEquals(SELF_REPO, cfg.selfRepo)
                assertEquals(REGISTRY_REPO, cfg.registry)
                assertEquals(4, cfg.jobs)
                assertEquals("info", cfg.logLevel.toString())
                assertEquals("text", cfg.logFormat.toString())
                assertEquals(4096uL * 1024uL * 1024uL, cfg.maxDownload)
            }
        }
    }

    @Test
    fun maxDownloadComesFromTheEnvironmentOverTheFile(): Unit =
        withEnv(listOf("KETCH_ROOT", "KETCH_MAX_DOWNLOAD_MIB")) {
            withTempDir { dir ->
                Path(dir, "config.toml").write("max_download_mib = 10\n")
                assertEquals(10uL * 1024uL * 1024uL, load(dir).maxDownload)
                setEnv("KETCH_MAX_DOWNLOAD_MIB", "3")
                assertEquals(3uL * 1024uL * 1024uL, load(dir).maxDownload)
                setEnv("KETCH_MAX_DOWNLOAD_MIB", "lots")
                assertFailsWith<KetchError.Config> { load(dir) }
                setEnv("KETCH_MAX_DOWNLOAD_MIB", "0")
                assertFailsWith<KetchError.Config> { load(dir) }
            }
        }

    @Test
    fun theOldSelfRepoAndRegistryNamesLoadAsTheNewOnes(): Unit =
        withEnv(listOf("KETCH_ROOT", "KETCH_SELF_REPO", "KETCH_REGISTRY")) {
            withTempDir { dir ->
                // A config.toml written by an install from before the move.
                Path(dir, "config.toml").write("self_repo = \"listepo/ketch\"\nregistry = \"github:listepo/ketch-registry\"\n")
                val cfg = load(dir)
                assertEquals(SELF_REPO, cfg.selfRepo)
                assertEquals(REGISTRY_REPO, cfg.registry)
                // The variable wins over the file and is read the same way.
                setEnv("KETCH_SELF_REPO", "Listepo/Ketch")
                assertEquals(SELF_REPO, load(dir).selfRepo)
                // A fork is somebody's choice, not an old name: kept as written.
                setEnv("KETCH_SELF_REPO", "someone/ketch")
                assertEquals("someone/ketch", load(dir).selfRepo)
            }
        }

    @Test
    fun ensureDirsMarksARootItCreates(): Unit =
        withTempDir { dir ->
            val cfg = load(Path(dir, "fresh"))
            cfg.ensureDirs()
            assertTrue(SystemFileSystem.metadataOrNull(cfg.rootMarker())?.isRegularFile == true)
        }

    @Test
    fun ensureDirsDoesNotMarkARootThatAlreadyExisted(): Unit =
        withTempDir { dir ->
            val cfg = load(dir)
            cfg.ensureDirs()
            assertNull(entryKind(cfg.rootMarker()))
        }

    @Test
    fun anUnknownKeyInTheFileIsRefused(): Unit =
        withEnv(listOf("KETCH_ROOT")) {
            withTempDir { dir ->
                Path(dir, "config.toml").write("prerelase = true\n")
                assertFailsWith<KetchError.Parse> { load(dir) }
            }
        }

    @Test
    fun aRootInTheFileIsReportedAsIgnored(): Unit =
        withEnv(listOf("KETCH_ROOT")) {
            withTempDir { dir ->
                Path(dir, "config.toml").write("root = \"/elsewhere\"\n")
                val recorder = ketch.core.report.Recorder()
                val cfg = Config.load(dir, Report(recorder))
                assertEquals(dir, cfg.root)
                val warned =
                    recorder
                        .events()
                        .filterIsInstance<ketch.core.report.Event.Warn>()
                        .single()
                        .detail
                assertTrue("has no effect" in warned, warned)
            }
        }
}
