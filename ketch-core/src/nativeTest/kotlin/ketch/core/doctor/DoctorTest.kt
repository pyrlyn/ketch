package ketch.core.doctor

import fs.canonicalize
import fs.createDir
import fs.createDirAll
import fs.joinPath
import ketch.core.config.Config
import ketch.core.config.HOST_WINDOWS
import ketch.core.model.nowUnix
import ketch.core.platform.CheckStatus
import ketch.core.platform.DoctorCheck
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoctorTest {
    private fun scratchConfig(root: Path) = Config.load(root, Report.silent())

    private fun joinPaths(vararg dirs: Path) = dirs.joinToString(if (HOST_WINDOWS) ";" else ":")

    @Test
    fun orphanStoreDirsAreNamesNotInState(): Unit =
        withTempDir { tmp ->
            createDir(joinPath(tmp, "ghost"))
            createDir(joinPath(tmp, "ripgrep"))
            joinPath(tmp, "file").write("x")
            assertEquals(listOf("ghost"), orphanStoreDirs(tmp, setOf("ripgrep")))
        }

    @Test
    fun registryCheckReportsAgeFromMetaWithoutANetwork(): Unit =
        withTempDir { tmp ->
            val cfg = scratchConfig(tmp)
            val pkg = joinPath(cfg.registryDir, "jq")
            createDirAll(pkg)
            joinPath(pkg, "ketch.toml").write("source = \"github:jqlang/jq\"\n")
            val fetchedAt = (nowUnix() - 7200).coerceAtLeast(0)
            cfg.registryMeta.write("repo = \"${cfg.registry}\"\nfetched_at = $fetchedAt\n")
            val check = registryCheck(Ctx(cfg, Report.silent()))
            assertEquals(CheckStatus.Ok, check.status)
            assertTrue("1 package" in check.detail, check.detail)
            assertTrue("h ago" in check.detail, check.detail)
        }

    @Test
    fun aScratchRootHasEveryCheckAndNoFailureFromTheStore(): Unit =
        withTempDir { tmp ->
            val cfg = scratchConfig(tmp)
            val checks = checks(Ctx(cfg, Report.silent()))
            assertEquals("version", checks[0].name)
            val packages = assertNotNull(checks.find { it.name == "packages" })
            assertEquals(CheckStatus.Ok, packages.status)
            assertEquals("0 installed", packages.detail)
        }

    @Test
    fun failedCountsOnlyFailures() {
        val checks =
            listOf(
                DoctorCheck.ok("version", "ketch"),
                DoctorCheck.warn("links", "1 broken", "ketch link x"),
                DoctorCheck.fail("packages", "1 missing", "ketch install --force x"),
            )
        assertEquals(1, failed(checks))
    }

    @Test
    fun pathBinaryCheckIsSilentWhenTheStoreLinkWins(): Unit =
        withTempDir { tmp ->
            val cfg = scratchConfig(tmp)
            createDirAll(cfg.binDir)
            storeKetchLink(cfg).write("store")
            assertNull(pathBinaryCheckFrom(cfg, joinPaths(cfg.binDir)))
        }

    @Test
    fun sameBinaryFoldsWindowsDriveLetterCase(): Unit =
        withTempDir { tmp ->
            val file = joinPath(tmp, "ketch.bin")
            file.write("x")
            val canon = canonicalize(file).toString()
            val first = canon.first()
            val swapped = if (first.isUpperCase()) first.lowercaseChar() else first.uppercaseChar()
            val flipped = Path(swapped + canon.drop(1))
            assertTrue(sameBinary(Path(canon), flipped), "case-only difference must still be the same binary: $canon vs $flipped")
        }

    @Test
    fun pathBinaryCheckWarnsWhenAnEarlierKetchShadowsTheStore(): Unit =
        withTempDir { tmp ->
            val cfg = scratchConfig(tmp)
            createDirAll(cfg.binDir)
            storeKetchLink(cfg).write("store")
            val shadowDir = joinPath(tmp, "cargo-bin")
            createDirAll(shadowDir)
            joinPath(shadowDir, if (HOST_WINDOWS) "ketch.exe" else "ketch").write("stale")
            val check = assertNotNull(pathBinaryCheckFrom(cfg, joinPaths(shadowDir, cfg.binDir)), "shadowed")
            assertEquals(CheckStatus.Warn, check.status)
            assertTrue("PATH runs" in check.detail, check.detail)
            assertTrue("cargo/bin" in check.fix.orEmpty(), check.fix)
        }

    @Test
    fun leftoverCaskIsSilentWhenKetchIsStillInstalled() {
        assertNull(leftoverCask(Path("/opt/homebrew/Caskroom/ketch"), true))
        assertNull(leftoverCask(null, false))
    }

    @Test
    fun leftoverCaskWarnsWhenThePackagesAreGone() {
        val check = assertNotNull(leftoverCask(Path("/opt/homebrew/Caskroom/ketch"), false))
        assertEquals("cask", check.name)
        assertEquals(CheckStatus.Warn, check.status)
        assertTrue("Caskroom" in check.detail)
    }

    @Test
    fun aMissingLockFileIsNotACheck(): Unit =
        withTempDir { tmp ->
            assertNull(lockCheckAt(joinPath(tmp, ".lock")) { true })
        }

    @Test
    fun aStaleLockFileIsAWarning(): Unit =
        withTempDir { tmp ->
            val path = joinPath(tmp, ".lock")
            path.write("12345\n")
            val check = assertNotNull(lockCheckAt(path) { false })
            assertEquals(CheckStatus.Warn, check.status)
            assertTrue("stale" in check.detail)
            assertTrue("12345" in check.detail)
        }

    @Test
    fun aHeldLockFileNamesThePid(): Unit =
        withTempDir { tmp ->
            val path = joinPath(tmp, ".lock")
            path.write("9")
            val check = assertNotNull(lockCheckAt(path) { it == 9L })
            assertTrue("pid 9" in check.detail)
            assertFalse("stale" in check.detail)
        }
}
