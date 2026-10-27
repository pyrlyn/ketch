package ketch.core.registry

import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.symlink
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.source.DEFAULT_API
import ketch.core.testing.read
import ketch.core.testing.withEnv
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryTest {
    private fun write(
        dir: Path,
        folder: String,
        body: String,
    ) {
        val pkg = Path(dir, folder)
        createDirAll(pkg)
        Path(pkg, PACKAGE_FILE).write(body)
    }

    private fun loadSilently(dir: Path): List<Pair<ketch.core.model.Manifest, Path>> {
        val report = Report.silent()
        return withTempDir { tmp -> load(Ctx(Config.load(tmp, report).copy(registryDir = dir), report)) }
    }

    @Test
    fun theFolderNamesThePackage(): Unit =
        withTempDir { tmp ->
            write(tmp, "ripgrep", "source = \"github:BurntSushi/ripgrep\"\n")
            val found = loadSilently(tmp)
            assertEquals(1, found.size)
            assertEquals("ripgrep", found[0].first.name)
            assertEquals("BurntSushi/ripgrep", found[0].first.source.id)
        }

    @Test
    fun aDeclaredNameMustMatchItsFolder(): Unit =
        withTempDir { tmp ->
            write(tmp, "fzf", "name = \"fzy\"\nsource = \"github:junegunn/fzf\"\n")
            assertTrue(loadSilently(tmp).isEmpty())
        }

    @Test
    fun nonPackageFoldersAndBrokenEntriesAreSkipped(): Unit =
        withTempDir { tmp ->
            createDirAll(Path(tmp, ".github/workflows"))
            Path(tmp, "README.md").write("hi")
            write(tmp, "broken", "source = 12\n")
            write(tmp, "jq", "source = \"github:jqlang/jq\"\n")
            assertEquals(listOf("jq"), loadSilently(tmp).map { it.first.name })
        }

    @Test
    fun aNameTwoPackagesClaimIsReportedOnceAgainstItsFirstOwner(): Unit =
        withTempDir { tmp ->
            // `fd` provides its own name, which is not a collision with itself.
            write(tmp, "fd", "source = \"github:sharkdp/fd\"\nprovides = [\"fd\"]\n")
            write(tmp, "rg", "source = \"github:BurntSushi/ripgrep\"\n")
            assertEquals(emptyList(), collisions(loadSilently(tmp)))
            write(tmp, "zfd", "source = \"github:someone/zfd\"\nprovides = [\"fd\"]\n")
            val found = collisions(loadSilently(tmp))
            assertEquals(1, found.size)
            assertTrue("both `fd` and `zfd`" in found[0], found[0])
        }

    @Test
    fun anEmptyTreeNeverReplacesAWorkingRegistry(): Unit =
        withTempDir { tmp ->
            val report = Report.silent()
            val cfg = Config.load(tmp, report)
            val cx = Ctx(cfg, report)
            write(cfg.registryDir, "jq", "source = \"github:jqlang/jq\"\n")
            val empty = Path(tmp, "empty")
            createDirAll(empty)
            assertFailsWith<KetchError> { swapIn(cx, empty, "someone/registry") }
            assertEquals(1, load(cx).size, "the old registry must still be there")

            val fresh = Path(tmp, "fresh")
            write(fresh, "fd", "source = \"github:sharkdp/fd\"\n")
            write(fresh, "rg", "source = \"github:BurntSushi/ripgrep\"\n")
            assertEquals(2, swapIn(cx, fresh, "someone/registry"))
            assertEquals(listOf("fd", "rg"), load(cx).map { it.first.name })
        }

    @Test
    fun aPackageThatWouldInstallOutsideTheStoreIsRefused(): Unit =
        withTempDir { tmp ->
            write(tmp, "evil", "name = \"evil\"\nsource = \"github:a/b\"\nbin = [{ name = \"../../../.zshrc\" }]\n")
            write(tmp, "typo", "source = \"github:a/b\"\nbinary = \"x\"\n")
            write(tmp, "ok", "source = \"github:a/b\"\n")
            assertEquals(listOf("ok"), loadSilently(tmp).map { it.first.name })
        }

    @Test
    fun checkTreeAcceptsAValidPackageFolder(): Unit =
        withTempDir { tmp ->
            write(tmp, "tool", "source = \"github:a/b\"\n")
            val report = checkTree(tmp)
            assertEquals(emptyList(), report.errors)
            assertEquals(1, report.packages)
        }

    @Test
    fun checkTreeCollectsParseAndValidationErrors(): Unit =
        withTempDir { tmp ->
            write(tmp, "evil", "name = \"evil\"\nsource = \"github:a/b\"\nbin = [{ name = \"../../../.zshrc\" }]\n")
            write(tmp, "broken", "source = 12\n")
            val report = checkTree(tmp)
            assertEquals(0, report.packages)
            assertEquals(2, report.errors.size)
        }

    @Test
    fun checkTreeNamesABrokenFileOnce(): Unit =
        withTempDir { tmp ->
            write(tmp, "broken", "source = 12\n")
            val error = checkTree(tmp).errors.single()
            assertTrue(error.path.endsWith("broken/ketch.toml"), error.path)
            assertTrue(error.path !in error.message, "the report prints the path in front of the message: ${error.message}")
        }

    /** Name-only collision detection reads two `foo` packages as one package claiming its own name and stays silent. */
    @Test
    fun checkTreeTreatsNameCollisionsAsErrorsAgainstTheRoot(): Unit =
        withTempDir { tmp ->
            write(tmp, "foo", "source = \"github:a/foo\"\n")
            write(tmp, "foo.git", "name = \"foo\"\nsource = \"github:b/foo\"\n")
            val report = checkTree(tmp)
            assertEquals(2, report.packages)
            val error = report.errors.single()
            assertEquals(tmp.toString(), error.path, "collisions are reported against the registry root")
            assertTrue("`foo.git`" in error.message, "the message must say which folders collide: ${error.message}")
        }

    @Test
    fun checkTreeRefusesAnEmptyTree(): Unit =
        withTempDir { tmp ->
            Path(tmp, "README.md").write("hi")
            val report = checkTree(tmp)
            assertEquals(0, report.packages)
            assertTrue(PACKAGE_FILE in report.errors.single().message)
        }

    @Test
    fun checkTreeErrorsWhenThePathIsMissing(): Unit =
        withTempDir { tmp ->
            val report = checkTree(Path(tmp, "nope"))
            assertEquals(0, report.packages)
            assertTrue("no such directory" in report.errors.single().message)
        }

    @Test
    fun checkTreeRefusesARegistryPackageThatInstallsFromALocalPath(): Unit =
        withTempDir { tmp ->
            write(tmp, "sneaky", "source = \"local:/etc/passwd\"\n")
            val report = checkTree(tmp)
            assertEquals(0, report.packages)
            assertTrue("local path" in report.errors.single().message, report.errors.toString())
        }

    /** The tree is somebody else's: a link here would have ketch read — and quote in an error — a file from outside it. */
    @Test
    fun checkTreeNeverReadsThroughASymlinkedPackageFile(): Unit =
        withTempDir { tmp ->
            val secret = Path(tmp, "secret.txt")
            secret.write("root:x:0:0:root:/root:/bin/sh\n")
            write(tmp, "good", "source = \"github:a/b\"\n")
            val sneaky = Path(tmp, "sneaky")
            createDirAll(sneaky)
            symlink(secret, Path(sneaky, PACKAGE_FILE))
            val report = checkTree(tmp)
            assertEquals(1, report.packages)
            val error = report.errors.single()
            assertTrue("not a symlink" in error.message, error.message)
            assertTrue(error.path.endsWith("sneaky/ketch.toml"), error.path)
        }

    @Test
    fun theTarballUrlHonoursKetchGithubApi() {
        withEnv(emptyList(), mapOf("KETCH_GITHUB_API" to "https://ghe.example/api/v3")) {
            assertEquals("https://ghe.example/api/v3/repos/acme/registry/tarball", tarballUrl("acme/registry"))
        }
        withEnv(listOf("KETCH_GITHUB_API")) {
            assertEquals("$DEFAULT_API/repos/acme/registry/tarball", tarballUrl("acme/registry"))
        }
    }

    @Test
    fun theRevisionIsReadFromTheTarballWrapper(): Unit =
        withTempDir { tmp ->
            val sha = "0123456789abcdef0123456789abcdef01234567"
            assertEquals(sha, gitRevision(Path(tmp, "pyrlyn-ketch-registry-$sha")))
            assertNull(gitRevision(Path(tmp, "fresh")))
        }

    @Test
    fun aSuccessfulSwapRecordsFetchMetadataBesideTheTree(): Unit =
        withTempDir { tmp ->
            val report = Report.silent()
            val cfg = Config.load(tmp, report)
            val sha = "abcdef0123456789abcdef0123456789abcdef01"
            val fresh = Path(tmp, "owner-registry-$sha")
            write(fresh, "jq", "source = \"github:jqlang/jq\"\n")
            assertEquals(1, swapIn(Ctx(cfg, report), fresh, "someone/registry"))
            val meta = assertNotNull(loadMeta(cfg))
            assertEquals("someone/registry", meta.repo)
            assertEquals(sha, meta.revision)
            assertTrue(meta.fetchedAt > 0)
            assertTrue(cfg.registryMeta.read().startsWith("# Written by `ketch update`. Do not edit."))
            assertTrue(exists(cfg))
        }

    @Test
    fun noMetadataIsNoUpdateYet(): Unit =
        withTempDir { tmp ->
            assertNull(loadMeta(Config.load(tmp, Report.silent())))
        }

    @Test
    fun aFixtureMayBeAFolderWithOneFile(): Unit =
        withTempDir { tmp ->
            val pkg = Path(tmp, "tool")
            createDirAll(pkg)
            val asset = Path(pkg, "tool.tar.gz")
            asset.write("bytes")
            assertEquals(asset, fixturePayload(tmp, "tool"))
        }

    @Test
    fun anAmbiguousFixtureFolderIsRefused(): Unit =
        withTempDir { tmp ->
            val pkg = Path(tmp, "tool")
            createDirAll(pkg)
            Path(pkg, "a.tar.gz").write("a")
            Path(pkg, "b.tar.gz").write("b")
            val err = assertFailsWith<KetchError> { fixturePayload(tmp, "tool") }
            assertTrue("more than one file" in err.message.orEmpty(), err.message)
        }

    /** Only moving the live copy aside succeeds: placing the new tree and the rename back both fail, as across filesystems. */
    @Test
    fun aFailedRenameBackRestoresThePreviousRegistryByCopy(): Unit =
        withTempDir { tmp ->
            val live = Path(tmp, "registry")
            write(live, "jq", "source = \"github:jqlang/jq\"\n")
            val fresh = Path(tmp, "fresh")
            write(fresh, "fd", "source = \"github:sharkdp/fd\"\n")
            val err =
                assertFailsWith<KetchError> {
                    replaceDir(live, fresh) { from, to -> if (to == live) throw IOException("cross-device link") else SystemFileSystem.atomicMove(from, to) }
                }
            assertTrue("cross-device link" in err.message.orEmpty(), err.message)
            assertNotNull(entryKind(Path(Path(live, "jq"), PACKAGE_FILE)))
            assertNull(entryKind(asidePath(live)))
            assertNotNull(entryKind(Path(Path(fresh, "fd"), PACKAGE_FILE)))
            assertTrue(isDir(live))
        }

    @Test
    fun theAgeIsSaidInWholeUnits() {
        val now = ketch.core.model.nowUnix()
        assertEquals("just now", agePhrase(now))
        assertEquals("2m ago", agePhrase(now - 120))
        assertEquals("2h ago", agePhrase(now - 7200))
        assertEquals("2d ago", agePhrase(now - 48 * 3600))
        assertEquals("just now", agePhrase(now + 3600))
    }
}
