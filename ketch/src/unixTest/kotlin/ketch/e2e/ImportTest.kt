// `ketch import` against recorded catalogues and a stand-in GitHub, in a throwaway root (Rust tests/import.rs). One
// local HTTP server plays every remote: the package catalogue (a Homebrew cask on macOS, an AUR package on Linux,
// each host importing the source that has builds for it), the GitHub releases API and the asset download. winget
// packages carry Windows builds only, so winget is covered by the converter's unit tests rather than here.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package ketch.e2e

import fs.bytesIfExists
import fs.readText
import ketch.core.hash.Sha256
import ketch.e2e.support.Entry
import ketch.e2e.support.Remote
import ketch.e2e.support.Run
import ketch.e2e.support.Sandbox
import ketch.e2e.support.hostArch
import ketch.e2e.support.ketchOverrides
import ketch.e2e.support.resolve
import ketch.e2e.support.runOnce
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import process.Command
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val REPO = "acme/demo"

class ImportTest {
    private val sandbox = Sandbox()
    private val remote = Remote()

    @AfterTest
    fun cleanUp() {
        remote.close()
        sandbox.close()
    }

    private val isMac get() = Platform.osFamily == OsFamily.MACOSX

    /** The variables that point every remote at the one server. */
    private fun remoteEnv() =
        mapOf(
            "KETCH_IMPORT_BREW" to "${remote.base}/brew",
            "KETCH_IMPORT_ARCH" to "${remote.base}/arch",
            "KETCH_IMPORT_ARCH_GITLAB" to "${remote.base}/gitlab",
            "KETCH_IMPORT_AUR" to "${remote.base}/aur",
            "KETCH_IMPORT_WINGET_API" to "${remote.base}/winget-api",
            "KETCH_IMPORT_WINGET_RAW" to "${remote.base}/winget-raw",
            "KETCH_GITHUB_API" to remote.githubApi,
        )

    private fun hostTokens() = "${if (isMac) "darwin" else "linux"}-${hostArch()}"

    /** One `demo` release: the archive, served for download, and its tag in the releases API. Returns the file name and SHA-256. */
    private fun publish(version: String): Pair<String, String> {
        val file = "demo-$version-${hostTokens()}.tar.gz"
        val path = sandbox.fixture(file)
        tarGz(Entry.program("demo-$version/demo", "demo $version")).writeTo(path)
        val bytes = checkNotNull(bytesIfExists(path))
        val sha = Sha256.hex(bytes)
        remote.serve("/dl/$file", bytes)
        val release =
            buildJsonObject {
                put("tag_name", "v$version")
                put("name", "v$version")
                put("prerelease", false)
                put("draft", false)
                putJsonArray("assets") {
                    add(
                        buildJsonObject {
                            put("name", file)
                            put("browser_download_url", "${remote.base}/dl/$file")
                            put("size", 1)
                            put("digest", "sha256:$sha")
                        },
                    )
                }
            }
        remote.serve("/gh/repos/$REPO/releases/tags/v$version", release.toString())
        return file to sha
    }

    /** The catalogue entry for this host's source, describing [version]. */
    private fun catalogue(
        version: String,
        file: String,
        sha: String?,
    ) {
        val url = "https://github.com/$REPO/releases/download/v$version/$file"
        if (isMac) {
            val cask =
                buildJsonObject {
                    put("token", "demo")
                    put("version", version)
                    put("url", url)
                    put("sha256", sha ?: "no_check")
                    putJsonArray("artifacts") { add(buildJsonObject { put("binary", buildJsonArray { add(JsonPrimitive("demo")) }) }) }
                    putJsonObject("variations") {}
                }
            remote.serve("/brew/cask/demo.json", cask.toString())
        } else {
            remote.serve(
                "/aur/rpc/v5/info?arg[]=demo&arg[]=demo-bin",
                """{"resultcount":1,"results":[{"Name":"demo-bin","PackageBase":"demo-bin"}],"type":"multiinfo","version":5}""",
            )
            val sums = if (sha != null) "\tsha256sums = $sha\n" else "\tsha256sums = SKIP\n"
            remote.serve(
                "/aur/cgit/aur.git/plain/.SRCINFO?h=demo-bin",
                "pkgbase = demo-bin\n\tpkgver = $version\n\tpkgrel = 1\n\turl = https://github.com/$REPO\n\tarch = x86_64\n\tarch = aarch64\n" +
                    "\tsource = $url\n$sums\npkgname = demo-bin\n",
            )
        }
    }

    private fun run(vararg extra: String) = sandbox.ketchOverrides(listOf("import", if (isMac) "brew" else "linux", "demo") + extra, remoteEnv())

    private fun ok(out: Run): String {
        val text = out.stdout + out.stderr
        assertTrue(out.success, "import failed: $text")
        return text
    }

    private fun manifest() = sandbox.root.resolve("manifests/demo.toml")

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    private fun installedSays() = Command(sandbox.bin.resolve("demo").toString()).runOnce().stdout.trim()

    @Test
    fun anImportWritesAMinimalManifestAndInstallsThePackage() {
        val (file, sha) = publish("1.0.0")
        catalogue("1.0.0", file, sha)

        ok(run())

        val written = readText(manifest())
        assertTrue(written.startsWith("# Written by `ketch import"), written)
        assertTrue("source = \"github:$REPO\"" in written, written)
        assertFalse("sha256" in written, "checksums stay out of the file")
        assertEquals("demo 1.0.0", installedSays())
        assertTrue(sha in sandbox.state(), "the catalogue's checksum was used")
    }

    @Test
    fun aSecondRunWithNothingNewChangesNothing() {
        val (file, sha) = publish("1.0.0")
        catalogue("1.0.0", file, sha)
        ok(run())
        val before = readText(manifest())
        val state = sandbox.state()

        val text = ok(run())

        assertTrue("Everything is up to date" in text, text)
        assertEquals(before, readText(manifest()))
        assertEquals(state, sandbox.state(), "nothing was reinstalled")
    }

    @Test
    fun aNewUpstreamVersionUpdatesTheManifestAndTheInstall() {
        val (file, sha) = publish("1.0.0")
        catalogue("1.0.0", file, sha)
        ok(run())

        publish("1.1.0").let { (f, s) -> catalogue("1.1.0", f, s) }
        val text = ok(run())

        assertFalse("Everything is up to date" in text, text)
        assertEquals("demo 1.1.0", installedSays())
        assertTrue("v1.1.0" in sandbox.state())
    }

    @Test
    fun aPackageOffGithubReleasesIsRefusedAndNothingIsWritten() {
        val (file, sha) = publish("1.0.0")
        catalogue("1.0.0", file, sha)
        // The same catalogue entry, but downloading from a vendor's own host.
        for ((target, body) in remote.routes()) {
            remote.serve(target, body.decodeToString().replace("https://github.com/$REPO/releases/download/v1.0.0/", "https://downloads.acme.example/"))
        }

        val out = run()

        assertFalse(out.success)
        assertEquals("demo can't be converted: it is not distributed through GitHub Releases, and that is not supported yet.", out.stderr.trim())
        assertFalse(exists(manifest()))
        assertFalse("demo" in sandbox.state())
    }

    @Test
    fun aNameNoCatalogueKnowsFailsWithoutWriting() {
        remote.clear()
        val out = run()
        assertFalse(out.success)
        assertTrue("demo" in out.stderr, out.stderr)
        assertFalse(exists(manifest()))
    }

    @Test
    fun aDryRunPrintsTheManifestAndTouchesNothing() {
        val (file, sha) = publish("1.0.0")
        catalogue("1.0.0", file, sha)

        val out = run("--dry-run")

        ok(out)
        assertTrue("source = \"github:$REPO\"" in out.stdout, out.stdout)
        assertFalse(exists(manifest()))
        assertFalse(exists(sandbox.bin.resolve("demo")))
    }

    @Test
    fun aManifestWrittenByHandIsLeftAlone() {
        val (file, sha) = publish("1.0.0")
        catalogue("1.0.0", file, sha)
        val own = "name = \"demo\"\nsource = \"github:me/my-demo\"\n"
        writeText(manifest(), own)

        val out = run()

        assertFalse(out.success)
        assertTrue("was not written by `ketch import`" in out.stderr, out.stderr)
        assertEquals(own, readText(manifest()))
    }

    @Test
    fun aDefinitionWithoutAChecksumStillInstallsWithAWarning() {
        val (file, _) = publish("1.0.0")
        catalogue("1.0.0", file, null)

        val text = ok(run())

        assertTrue("records no checksum" in text, text)
        assertEquals("demo 1.0.0", installedSays())
    }
}
