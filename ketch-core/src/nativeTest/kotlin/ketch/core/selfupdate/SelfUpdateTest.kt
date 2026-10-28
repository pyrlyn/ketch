package ketch.core.selfupdate

import fs.EntryKind
import fs.FsException
import fs.canonicalize
import fs.createDirAll
import fs.entryKind
import fs.readLink
import ketch.core.VERSION
import ketch.core.config.Config
import ketch.core.config.HOST_WINDOWS
import ketch.core.model.InstalledPackage
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageRef
import ketch.core.model.Release
import ketch.core.model.TargetSpec
import ketch.core.model.Version
import ketch.core.model.VersionSpec
import ketch.core.model.host
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.source.pick
import ketch.core.state.State
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import platform.posix.EACCES
import platform.posix.ENOENT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun installedKetch(prefix: Path) =
    InstalledPackage(
        name = SELF_NAME,
        version = Version.parse("1.0.0"),
        source = PackageRef.github("pyrlyn/ketch"),
        tag = "v1.0.0",
        target = TargetSpec.host(),
        assetName = "a.tar.gz",
        sha256 = "0".repeat(64),
        checksumVerified = true,
        installedAt = 0,
        prefix = prefix,
        origin = ManifestOrigin.Inferred,
    )

class SelfUpdateTest {
    @Test
    fun displayVersionMarksThePreviewChannel() {
        assertEquals("$VERSION · $VERSION_CHANNEL", displayVersion())
        assertEquals("preview", VERSION_CHANNEL)
    }

    @Test
    fun preWidensTheSelfReleaseToTheNewestCliPrereleaseOnly() {
        fun release(
            tag: String,
            prerelease: Boolean,
        ) = Release(Version.parse(tag), tag, prerelease = prerelease)
        val listing = listOf(release("desktop-v9999.0.0", false), release("desktop-appcast", true), release("v2.0.0-rc.1", true), release("v1.0.0", false))
        val pick = { pre: Boolean -> runCatching { pick("o/ketch", listing, VersionSpec.Latest, releaseOpts(pre)).tag }.getOrNull() }
        assertEquals("v1.0.0", pick(false))
        assertEquals("v2.0.0-rc.1", pick(true))
    }

    @Test
    fun aProbeThatNeverAnswersReportsThatItWasStopped() {
        val err = probeStopped("ketch", 60.seconds, 42).message
        assertTrue("pid 42" in err, err)
        assertTrue("was stopped" in err, err)
        assertTrue("previous binary was kept" in err, err)
    }

    @Test
    fun asideNamesCoverTheSwapAndTheFlatInstall() {
        assertEquals(listOf("ketch.old"), asideCandidates(Path("bin/ketch")).map { it.name })
        // What Windows leaves (T13): the swap appends, the flat install replaces the extension.
        assertEquals(listOf("ketch.exe.old", "ketch.old"), asideCandidates(Path("bin/ketch.exe")).map { it.name })
    }

    @Test
    fun aPreviousSwapBackupIsRemovedAndAMissingOneIsFine(): Unit =
        withTempDir { tmp ->
            val backup = swapBackup(Path(tmp, bootstrapBinaryName()))
            backup.write("old")
            sweepAside(backup, Report.silent())
            assertNull(entryKind(backup))
            sweepAside(backup, Report.silent())
        }

    @Test
    fun doctorNotesAStaleAsideAndStaysQuietWithoutOne(): Unit =
        withTempDir { tmp ->
            assertNull(staleAsideFrom(tmp, null))
            val backup = swapBackup(Path(tmp, bootstrapBinaryName()))
            backup.write("old")
            val check = assertNotNull(staleAsideFrom(tmp, null))
            assertEquals("backup", check.name)
            assertTrue(backup.toString() in check.detail, check.detail)
            assertNotNull(check.fix)
        }

    @Test
    fun aTransientLockIsRetriedUntilItClears() {
        var calls = 0
        val pauses = mutableListOf<Duration>()
        val result =
            ioRetry({
                calls += 1
                if (calls < 3) throw FsException(null, FsException.Kind.PermissionDenied, null, "busy") else "done"
            }) { pauses += it }
        assertEquals("done", result)
        assertEquals(3, calls)
        assertEquals(listOf(100.milliseconds, 150.milliseconds), pauses)
    }

    @Test
    fun aNonLockErrorIsThrownWithoutAPause() {
        var calls = 0
        var pauses = 0
        val err =
            assertFailsWith<FsException> {
                ioRetry({
                    calls += 1
                    throw FsException.ofErrno(null, ENOENT)
                }) { pauses += 1 }
            }
        assertEquals(FsException.Kind.NotFound, err.kind)
        assertEquals(1, calls)
        assertEquals(0, pauses)
    }

    @Test
    fun aLockThatNeverClearsStopsAfterThePauses() {
        var calls = 0
        val pauses = mutableListOf<Duration>()
        val err =
            assertFailsWith<FsException> {
                ioRetry({
                    calls += 1
                    throw FsException.ofErrno(null, SHARING_VIOLATION)
                }) { pauses += it }
            }
        assertEquals(SHARING_VIOLATION, err.errno)
        assertEquals(1 + SWAP_PAUSES.size, calls)
        assertEquals(SWAP_PAUSES, pauses)
    }

    @Test
    fun windowsSharingViolationsCountAsATransientLock() {
        assertTrue(isTransientLock(FsException.ofErrno(null, ACCESS_DENIED)))
        assertTrue(isTransientLock(FsException.ofErrno(null, SHARING_VIOLATION)))
        assertTrue(!isTransientLock(FsException.ofErrno(null, ENOENT)))
    }

    @Test
    fun aBootstrapLinkDirIsRecordedAndPlaced(): Unit =
        withTempDir { tmp ->
            val cfg = Config.load(Path(tmp, "root"), Report.silent())
            createDirAll(cfg.binDir)
            val bin = Path(cfg.binDir, bootstrapBinaryName())
            bin.write("ketch")
            val state = State.load(cfg)
            state.insert(installedKetch(Path(cfg.storeDir, SELF_NAME)))
            val bootstrap = Path(tmp, "bootstrap")
            recordBootstrapLink(Ctx(cfg, Report.silent()), state, bootstrap)

            val link = Path(canonicalize(bootstrap), bootstrapBinaryName())
            if (HOST_WINDOWS) {
                assertEquals(EntryKind.File, entryKind(link))
            } else {
                assertEquals(EntryKind.Symlink, entryKind(link), "the bootstrap path must follow the bin-dir binary")
                assertEquals(canonicalize(bin), canonicalize(assertNotNull(readLink(link))))
            }
            assertEquals("ketch", link.read())
            val pkg = assertNotNull(state.get(SELF_NAME))
            assertEquals(1, pkg.links.size)
            assertEquals(link, pkg.links[0].link)
            assertEquals(canonicalize(bin), canonicalize(pkg.links[0].target))
        }

    @Test
    fun aLinkDirThatIsTheBinDirIsLeftAlone(): Unit =
        withTempDir { tmp ->
            val cfg = Config.load(Path(tmp, "root"), Report.silent())
            createDirAll(cfg.binDir)
            val bin = Path(cfg.binDir, bootstrapBinaryName())
            bin.write("ketch")
            val state = State.load(cfg)
            state.insert(installedKetch(Path(cfg.storeDir, SELF_NAME)))
            recordBootstrapLink(Ctx(cfg, Report.silent()), state, cfg.binDir)

            assertEquals(EntryKind.File, entryKind(bin), "the installed binary must still be the binary, not a link to itself")
            assertTrue(assertNotNull(state.get(SELF_NAME)).links.isEmpty())
        }

    private companion object {
        /** Windows' `ERROR_ACCESS_DENIED` and `ERROR_SHARING_VIOLATION`, as raw codes. */
        const val ACCESS_DENIED = 5
        const val SHARING_VIOLATION = 32
    }
}
