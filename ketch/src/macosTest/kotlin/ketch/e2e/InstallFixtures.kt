// The fixtures of Rust tests/install.rs, shared by the classes its tests are split across: a tool shaped like a real
// release tarball, an app shaped like a real release zip, and a manifest whose hooks log the order they ran in.
package ketch.e2e

import fs.isDir
import fs.listDir
import ketch.e2e.support.Archive
import ketch.e2e.support.CHANGELOG
import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.hostArch
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.said
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import ketch.e2e.support.zip
import kotlinx.io.files.Path
import kotlin.test.assertFalse

/** Every icon the ui can put in front of a status line. */
private val ICONS = listOf("📦", "⏫", "🧹", "⏬", "🔗", "⏪", "🔍", "🩺", "✅", "❗", "❌", "💡")

/** A command-line tool, shaped like a real release tarball: a version-stamped wrapper directory with the binary under `bin/`. */
internal fun toolArchive(version: String): Archive =
    tarGz(
        Entry.program("testtool-$version/bin/testtool", "testtool $version"),
        Entry.file("testtool-$version/README.md", "# testtool\n"),
        Entry.file("testtool-$version/CHANGELOG.md", CHANGELOG),
    )

/** A macOS app, shaped like a real release zip: the bundle alone at the root. */
internal fun appArchive(version: String): Archive =
    zip(
        Entry.file("TestApp.app/Contents/Info.plist", "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict></dict></plist>"),
        Entry.program("TestApp.app/Contents/MacOS/TestApp", "app $version"),
    )

internal fun darwinTool(version: String) = "testtool-$version-${hostArch()}-apple-darwin.tar.gz"

/** Publish `testtool` at one version, with a decoy for another platform so asset selection does real work. */
internal fun Sandbox.publishTool(version: String) {
    val native = asset(darwinTool(version), toolArchive(version))
    val linux = asset("testtool-$version-${hostArch()}-unknown-linux-gnu.tar.gz", toolArchive("linux-decoy"))
    publish("testtool", listOf(Release(version, listOf(linux, native)).withNotes("published notes for $version")))
}

/** Publish `testtool` at [version] with no checksum, as a release whose checksums were removed would look. */
internal fun Sandbox.publishUnverifiedTool(version: String) = publish("testtool", listOf(Release(version, listOf(asset(darwinTool(version), toolArchive(version)).withoutDigest()))))

/** Installed at 1.0.0 with 2.0.0 published since: the state every `install`-offers-the-update case starts from. */
internal fun Sandbox.installedWithAnUpdate() {
    publishTool("1.0.0")
    ok("install", "test:testtool", "--yes")
    publishTool("2.0.0")
}

internal fun Sandbox.installedVersion() = said(bin.resolve("testtool"))

/** Every file name anywhere under [dir]. */
internal fun namesUnder(dir: Path): List<String> = listDir(dir).flatMap { name -> listOf(name) + dir.resolve(name).let { if (isDir(it)) namesUnder(it) else emptyList() } }

internal fun assertNoIcon(
    what: String,
    text: String,
) = ICONS.forEach { assertFalse(it in text, "$what carries $it: $text") }

/**
 * A user manifest whose every hook appends its name, the versions it saw and where it ran to one file, so a run of
 * commands leaves an order to assert on. [replace] swaps one hook's command for another.
 */
internal fun Sandbox.writeHookedManifest(
    log: Path,
    replace: Pair<String, String>? = null,
) {
    val line =
        "if [ \"\$PWD\" -ef \"\$KETCH_PREFIX\" ]; then cwd=prefix; else cwd=elsewhere; fi; " +
            "echo \"\$KETCH_HOOK \$KETCH_VERSION \${KETCH_PREVIOUS_VERSION:-none} \$cwd\" >> '$log'"
    val hooks =
        listOf("before_install", "after_install", "before_update", "after_update", "before_uninstall", "after_uninstall").joinToString("") { key ->
            // Literal strings, so the shell's own quoting passes through untouched.
            "$key = '''${if (replace?.first == key) replace.second else line}'''\n"
        }
    writeText(root.resolve("manifests/testtool.toml"), "name = \"testtool\"\nsource = \"test:testtool\"\n\n[hooks]\n$hooks")
}
