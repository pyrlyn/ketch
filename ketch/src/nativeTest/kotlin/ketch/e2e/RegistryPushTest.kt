// Binary-level tests for `ketch registry`'s offline surface (Rust tests/registry_push.rs). The review step (fetch the
// registry's copy, diff it, confirm) needs a live GitHub token, so its three-way decision is unit-tested in push.
// What only the real binary can prove is everything short of that boundary: the package file is found, parsed and
// validated before any flag is honoured, `--registry` is checked before it can become a URL, and a dry run reports the
// destination and the file body with no token in the environment. Every case strips the token variables so a
// developer's real credentials can never send it to the network.
package ketch.e2e

import fs.TempDir
import fs.createDirAll
import fs.listDir
import ketch.e2e.support.Entry
import ketch.e2e.support.KETCH_EXE
import ketch.e2e.support.Run
import ketch.e2e.support.get
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.resolve
import ketch.e2e.support.runOnce
import ketch.e2e.support.str
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import process.Command
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryPushTest {
    private val temp = TempDir.create("ketch-registry")
    private val root = temp.path.resolve("ketch-root")
    private val registry = temp.path.resolve("registry")

    @AfterTest
    fun cleanUp() = temp.close()

    private fun writePackage(
        dir: Path,
        folder: String,
        body: String,
    ) = writeText(dir.resolve("$folder/ketch.toml"), body)

    /** A project folder holding [body] as its `ketch.toml`, or no file at all when [body] is null. */
    private fun project(
        folder: String,
        body: String?,
    ): Path {
        val dir = temp.path.resolve(folder)
        createDirAll(dir)
        body?.let { writeText(dir.resolve("ketch.toml"), it) }
        return dir
    }

    /** `ketch --root <root> <args>` in this process's environment, as assert_cmd runs it, with every token stripped. */
    private fun ketch(
        vararg args: String,
        cwd: Path? = null,
    ): Run {
        val cmd =
            Command(KETCH_EXE, listOf("--root", root.toString()) + args)
                .env("NO_COLOR", "1")
                // A run that tried the network would reach nothing.
                .env("KETCH_GITHUB_API", "http://127.0.0.1:1")
                .env("KETCH_AUTO_UPDATE", "false")
                .envRemove("KETCH_GITHUB_TOKEN")
                .envRemove("GITHUB_TOKEN")
                .envRemove("GH_TOKEN")
        cwd?.let { cmd.currentDir(it.toString()) }
        return cmd.runOnce()
    }

    private fun Run.succeeds() = also { assertTrue(success, "expected success: $this") }

    private fun Run.failsHere() = also { assertFalse(success, "expected failure: $this") }

    private fun validate(vararg args: String) = ketch("registry", "validate", *args)

    private fun push(
        project: Path,
        vararg args: String,
    ) = ketch("registry", "push", *args, cwd = project)

    @Test
    fun registryHelpListsPushAndValidate() {
        val out = ketch("registry", "--help").succeeds().stdout
        assertTrue("push" in out && "validate" in out && "status" in out, out)
    }

    @Test
    fun registryValidateAcceptsAValidPackageTree() {
        writePackage(registry, "tool", "source = \"github:a/b\"\n")
        val out = validate(registry.toString()).succeeds()
        assertTrue("1 package" in out.stderr, out.stderr)
    }

    @Test
    fun registryValidateRejectsABinNameThatWouldEscape() {
        writePackage(registry, "evil", "name = \"evil\"\nsource = \"github:a/b\"\nbin = [{ name = \"../../../.zshrc\" }]\n")
        val out = validate(registry.toString()).failsHere().stdout
        assertTrue("binary name" in out && "not usable as a file name" in out, out)
    }

    @Test
    fun registryValidateRejectsTwoPackagesClaimingTheSameName() {
        writePackage(registry, "fd", "source = \"github:sharkdp/fd\"\nprovides = [\"fd\"]\n")
        writePackage(registry, "zfd", "source = \"github:someone/zfd\"\nprovides = [\"fd\"]\n")
        val out = validate(registry.toString()).failsHere().stdout
        assertTrue("both `fd` and `zfd`" in out, out)
    }

    @Test
    fun registryValidateJsonReportsFailure() {
        writePackage(registry, "broken", "source = 12\n")
        val value = json(validate("--json", registry.toString()).failsHere().stdout.trim())
        assertEquals("fail", value["status"].str)
        assertTrue(value["errors"].items.isNotEmpty())
    }

    @Test
    fun registryValidateKeepsBidiOutOfTheJsonItPrints() {
        // The declared name reaches the error message, and the message is printed to a terminal by default: JSON
        // escapes control characters, not bidi overrides, so nothing but the filter keeps the screen intact.
        writePackage(registry, "tool", "name = \"‮tool\"\nsource = \"github:a/b\"\n")
        val stdout = validate("--json", registry.toString()).failsHere().stdout
        assertEquals("fail", json(stdout.trim())["status"].str)
        assertFalse('‮' in stdout, "a bidi override reached the terminal: $stdout")
    }

    @Test
    fun registryValidateRejectsAMissingDirectory() {
        val out = validate(temp.path.resolve("nope").toString()).failsHere().stdout
        assertTrue("no such directory" in out, out)
    }

    /** `--json` exists for a CI job, and a wrong path is how one most likely goes wrong: it must still answer in JSON. */
    @Test
    fun registryValidateJsonStillAnswersForAMissingDirectory() {
        val stdout = validate("--json", temp.path.resolve("nope").toString()).failsHere().stdout
        val value = json(stdout.trim())
        assertEquals("fail", value["status"].str)
        assertEquals(JsonPrimitive(0), value["packages"])
        assertTrue(value["errors"][0]["message"].str?.contains("no such directory") == true, stdout)
    }

    @Test
    fun registryPushDryRunNamesTheRegistryFolderAndSendsNothing() {
        // No `name`: the folder supplies it, normalised as the registry would.
        val dir = project("Fancy-Tool", "source = \"github:acme/fancy-tool\"\n# keep me\n")
        val out = push(dir, "--dry-run").succeeds()
        assertEquals("source = \"github:acme/fancy-tool\"\n# keep me\n", out.stdout)
        assertTrue("pyrlyn/ketch-registry:fancy-tool/ketch.toml" in out.stderr, out.stderr)
    }

    @Test
    fun registryPushWithoutATokenExplainsWhatToSetBeforeTouchingTheNetwork() {
        val dir = project("tool", "name = \"tool\"\nsource = \"github:acme/tool\"\n")
        val err = push(dir).failsHere().stderr
        assertTrue("KETCH_GITHUB_TOKEN" in err, err)
    }

    @Test
    fun registryPushRefusesALocalSource() {
        val dir = project("tool", "name = \"tool\"\nsource = \"local:/etc/passwd\"\n")
        val err = push(dir, "--dry-run").failsHere().stderr
        assertTrue("local path" in err, err)
    }

    @Test
    fun registryPushRefusesANameThatDoesNotMatchItsFolder() {
        val dir = project("fzf", "name = \"fzy\"\nsource = \"github:junegunn/fzf\"\n")
        val err = push(dir, "--dry-run").failsHere().stderr
        assertTrue("declares name" in err, err)
    }

    @Test
    fun registryPushRefusesAPackageFileWithAnUnknownKey() {
        val dir = project("tool", "name = \"tool\"\nsource = \"github:acme/tool\"\nbinary = \"tool\"\n")
        val err = push(dir, "--dry-run").failsHere().stderr
        assertTrue("binary" in err, err)
    }

    @Test
    fun registryPushNamesAMissingPackageFile() {
        val err = push(project("tool", null), "--dry-run").failsHere().stderr
        assertTrue("ketch.toml" in err, err)
    }

    @Test
    fun registryPushAcceptsAnExplicitRegistry() {
        val dir = project("tool", "name = \"tool\"\nsource = \"github:acme/tool\"\n")
        val err = push(dir, "--registry", "acme/other-registry", "--dry-run").succeeds().stderr
        assertTrue("acme/other-registry:tool/ketch.toml" in err, err)
    }

    @Test
    fun registryPushHelpListsTheReviewFlags() {
        val out = push(project("tool", null), "--help").succeeds().stdout
        assertTrue("--dry-run" in out && "--yes" in out, out)
    }

    @Test
    fun registryPushRejectsAMalformedRegistryArgument() {
        val dir = project("tool", "name = \"tool\"\nsource = \"github:acme/tool\"\n")
        val err = push(dir, "--registry", "not-a-repo", "--dry-run").failsHere().stderr
        assertTrue("not-a-repo" in err && "expected `owner/repo`" in err, err)
    }

    // The old top-level spelling was replaced by `registry push`; this pins the removal.
    @Test
    fun theRetiredPushSpellingIsRecognizedNoMore() {
        val dir = project("tool", "name = \"tool\"\nsource = \"github:acme/tool\"\n")
        val err = ketch("push", "--dry-run", cwd = dir).failsHere().stderr
        assertTrue("unrecognized subcommand" in err, err)
    }

    private fun writeMeta(fetchedAt: Long) = writeText(root.resolve("registry.meta.toml"), "repo = \"pyrlyn/ketch-registry\"\nrevision = \"abc123\"\nfetched_at = $fetchedAt\n")

    @Test
    fun registryStatusReportsAgeAndSourceWithoutANetwork() {
        writePackage(root.resolve("registry"), "tool", "source = \"github:a/b\"\n")
        writeMeta(1)
        val out = ketch("registry", "status").succeeds().stdout
        assertTrue("revision" in out && "abc123" in out && "fetched" in out && "1" in out, out)
    }

    @Test
    fun registryStatusJsonIsOfflineWhenTheCopyIsMissing() {
        createDirAll(root)
        val value = json(ketch("registry", "status", "--json").succeeds().stdout.trim())
        assertEquals("missing", value["status"].str)
        assertEquals(JsonNull, value["packages"])
    }

    @Test
    fun registryValidateChangedWithoutAFixtureFails() {
        writePackage(registry, "tool", "source = \"github:a/b\"\n")
        val out = validate("--changed", "tool", registry.toString()).failsHere().stdout
        assertTrue("--fixture" in out, out)
    }

    @Test
    fun registryValidateOfflineInstallsAChangedEntryFromAFixture() {
        writePackage(registry, "tool", "source = \"github:a/b\"\nbin = [{ name = \"tool\" }]\n")
        val fixtures = temp.path.resolve("fixtures")
        tarGz(Entry.program("tool", "ok")).writeTo(fixtures.resolve("tool/tool.tar.gz"))

        val out = validate("--fixture", fixtures.toString(), "--changed", "tool", registry.toString()).succeeds()
        assertTrue("1 package" in out.stderr, out.stderr)
        // The root's store/ may exist; the probe must not put a package there.
        val store = root.resolve("store")
        assertTrue(!SystemFileSystem.exists(store) || listDir(store).isEmpty(), "offline-install must not write packages into the caller's root")
    }

    @Test
    fun registryValidateFixtureInstallFailsWhenTheBinaryIsMissing() {
        writePackage(registry, "tool", "source = \"github:a/b\"\nbin = [{ name = \"tool\", path = \"bin/tool\" }]\n")
        val fixtures = temp.path.resolve("fixtures")
        tarGz(Entry.file("README.md", "no binary here")).writeTo(fixtures.resolve("tool/tool.tar.gz"))

        val out = validate("--fixture", fixtures.toString(), "--changed", "tool", registry.toString()).failsHere().stdout
        assertTrue("offline-install failed" in out, out)
    }
}
