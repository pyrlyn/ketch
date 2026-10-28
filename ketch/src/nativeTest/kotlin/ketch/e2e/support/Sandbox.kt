// Scaffolding for the end-to-end tests (Rust tests/support/mod.rs): a throwaway ketch root, fixture archives that
// stand in for real release assets, and a source plugin that serves them.
//
// Everything here is offline on purpose. The plugin protocol already lets a source hand ketch an asset it fetched
// itself, so a short shell script plays the part of a release host: no network, no fixtures checked into the tree,
// and no test that depends on somebody else's tag still existing.
@file:OptIn(ExperimentalForeignApi::class)

package ketch.e2e.support

import fs.TempDir
import fs.copyFile
import fs.createDirAll
import fs.listDir
import fs.setMode
import fs.symlink
import fs.textOrEmpty
import ketch.cmd.PRETTY
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import platform.posix.getenv
import process.Command
import process.ExitStatus
import process.envVars
import process.runBounded
import kotlin.time.Duration.Companion.seconds

/** mise's directory for ketch under its current name, then under the name it had before it moved from listepo to pyrlyn. */
val MISE_TOOL_DIRS = listOf("github-pyrlyn-ketch", "github-listepo-ketch")

private val RUN_TIMEOUT = 120.seconds
private const val MAX_OUTPUT = 16L shl 20
private const val EXECUTABLE = 0x1ed

/** The `ketch` under test: the linked debug executable the build names in `KETCH_EXE`. */
val KETCH_EXE: String get() = checkNotNull(getenv("KETCH_EXE")?.toKString()) { "KETCH_EXE is not set; run the tests through Gradle" }

/** What a finished `ketch` printed and how it ended (Rust `std::process::Output`). */
class Run(
    val status: ExitStatus,
    val stdout: String,
    val stderr: String,
) {
    val success get() = status.success

    /** The exit code, or null when a signal ended it. */
    val code get() = (status as? ExitStatus.Exited)?.code

    override fun toString() = "$status\n--- stdout ---\n$stdout\n--- stderr ---\n$stderr"
}

/** Run [command] under the suite's deadline and caps. */
fun Command.runOnce(): Run {
    val out = runBounded(RUN_TIMEOUT, MAX_OUTPUT)
    return Run(out.status, out.stdout.decodeToString(), out.stderr.decodeToString())
}

/** Write [text] to [path], creating its parent. */
fun writeText(
    path: Path,
    text: String,
) = writeBytes(path, text.encodeToByteArray())

fun writeBytes(
    path: Path,
    bytes: ByteArray,
) {
    path.parent?.let(::createDirAll)
    SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
}

fun makeExecutable(path: Path) = setMode(path, EXECUTABLE)

/** Single-quote a path for a generated shell script; temp dirs contain no quotes, but the script should not assume so. */
fun shellQuote(path: Path) = "'${path.toString().replace("'", "'\\''")}'"

/** A ketch root, applications directory and plugin, all inside one temp dir removed on [close]. */
class Sandbox : AutoCloseable {
    private val tmp = TempDir.create("ketch-e2e")

    init {
        for (name in listOf("root", "Applications", "home", "homebrew", "assets", "root/plugins")) createDirAll(child(name))
        // The root is made here rather than by ketch, so mark it as the installers do when they create one;
        // `self uninstall` then empties it.
        writeText(root.resolve(".ketch-root"), "")
        writePlugin()
    }

    override fun close() = tmp.close()

    /** Numbers the files that hold a run's input. */
    internal var inputs = 0

    internal fun child(name: String) = Path(tmp.path, name)

    val root get() = child("root")
    val apps get() = child("Applications")
    val bin get() = root.resolve("bin")
    val store get() = root.resolve("store")

    /** A home of its own, so a test that edits shell startup files cannot reach the real one. */
    val home get() = child("home")

    /** A Homebrew prefix of its own: a test that removes a cask cannot reach the real Homebrew. */
    val homebrew get() = child("homebrew")

    /** mise's data dir for this sandbox; every run points `MISE_DATA_DIR` here. */
    val miseData get() = child("mise")

    /** Where fixture assets and the JSON the plugin serves both live. */
    private val assets get() = child("assets")

    /** A path beside the root, for local-install fixtures that must live outside it. */
    fun fixture(name: String) = child(name)

    /**
     * Put a ketch cask in the Homebrew prefix, with a `brew` that records how it was called and removes the cask.
     * Returns the file it writes to: the point is to prove ketch hands the cask back to Homebrew.
     */
    fun installCask(): Path {
        val cask = homebrew.resolve("Caskroom/ketch")
        createDirAll(cask)
        val log = homebrew.resolve("brew-args")
        val brew = homebrew.resolve("bin/brew")
        writeText(brew, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> ${shellQuote(log)}\nrm -rf ${shellQuote(cask)}\n")
        makeExecutable(brew)
        return log
    }

    /** Copy the ketch under test to where `mise use -g github:pyrlyn/ketch` would have put release 0.4.7. */
    fun installWithMise() = installWithMiseAs(MISE_TOOL_DIRS[0])

    fun installWithMiseAs(tool: String): Path {
        val exe = miseData.resolve("installs/$tool/0.4.7/ketch")
        createDirAll(exe.parent!!)
        copyFile(Path(KETCH_EXE), exe)
        makeExecutable(exe)
        return exe
    }

    /** The tool directory `mise unuse` removes: the one `installWithMise*` created, else the current name's. */
    fun miseToolDir(): Path {
        val installs = miseData.resolve("installs")
        return MISE_TOOL_DIRS.map { installs.resolve(it) }.firstOrNull { SystemFileSystem.exists(it) } ?: installs.resolve(MISE_TOOL_DIRS[0])
    }

    /**
     * A `mise` that records its arguments and removes the tool directory, as the real one does, while the ketch that
     * called it is still running. Returns the directory to put on PATH and the file the arguments go to.
     */
    fun fakeMise(): Pair<Path, Path> {
        val dir = child("mise-bin")
        val log = dir.resolve("mise-args")
        val mise = dir.resolve("mise")
        writeText(mise, "#!/bin/sh\nprintf '%s' \"$*\" > ${shellQuote(log)}\nrm -rf ${shellQuote(miseToolDir())} || exit 1\n")
        makeExecutable(mise)
        return dir to log
    }

    /** A directory holding the ketch under test as `ketch`, for a script that runs it by that name from PATH. */
    fun ketchOnPathDir(): Path {
        val dir = child("ketch-bin")
        if (!SystemFileSystem.exists(dir)) {
            createDirAll(dir)
            symlink(Path(KETCH_EXE), dir.resolve("ketch"))
        }
        return dir
    }

    /** Run [program], a ketch copied somewhere else, against this sandbox with [extra] in front of PATH. */
    fun ketchFrom(
        program: Path,
        args: List<String>,
        extra: Path,
    ) = command(program.toString(), args, joinPath(listOf(extra.toString(), bin.toString()) + inheritedPath())).runOnce()

    /** Where the run's log lands. */
    fun log() = textOrEmpty(root.resolve("logs/ketch.log"))

    /** The install record, `state.json`, as written. */
    fun state() = textOrEmpty(root.resolve("state.json"))

    /** Rewrite [name]'s record in `state.json` through [edit], the way a tree that drifted from its record would look. */
    fun editPackage(
        name: String,
        edit: (JsonObject) -> JsonObject,
    ) {
        val path = root.resolve("state.json")
        val state = json(textOrEmpty(path)) as JsonObject
        val packages = state.getValue("packages") as JsonObject
        val edited = JsonObject(state + ("packages" to JsonObject(packages + (name to edit(packages.getValue(name) as JsonObject)))))
        writeText(path, PRETTY.encodeToString(JsonElement.serializer(), edited))
    }

    /** Write a registry package, as `ketch update` would have fetched it. */
    fun registryPackage(
        name: String,
        body: String,
    ) = writeText(root.resolve("registry/$name/ketch.toml"), body)

    /** Stop serving [id]'s releases, so its source fails the way one that cannot be reached does. */
    fun unpublish(id: String) = SystemFileSystem.delete(assets.resolve("$id.releases.json"))

    /** Write `config.toml` for this root, for settings with no flag. */
    fun configure(toml: String) = writeText(root.resolve("config.toml"), toml)

    /** Publish the releases the test plugin will serve for [id]. */
    fun publish(
        id: String,
        releases: List<Release>,
    ) = writeText(assets.resolve("$id.releases.json"), releases.joinToString(",", "[", "]") { it.toJson() })

    /** Build a release asset on disk and describe it for the plugin. */
    fun asset(
        name: String,
        archive: Archive,
    ): Asset {
        val path = assets.resolve(name)
        archive.writeTo(path)
        return Asset(name, path, sha256File(path))
    }

    /** Serve a file byte for byte: a checked-in fixture whose exact bytes a signature covers. */
    fun fileAsset(
        name: String,
        source: Path,
    ): Asset {
        val path = assets.resolve(name)
        copyFile(source, path)
        return Asset(name, path, sha256File(path))
    }

    /** Every name directly in [dir], sorted; empty when it does not exist. */
    fun names(dir: Path) = if (SystemFileSystem.exists(dir)) listDir(dir).sorted() else emptyList()

    /**
     * The command for one run. The environment is set per invocation: every KETCH_* variable of the developer's shell
     * or the CI job is stripped, so none leaks into the sandbox; ROOT, APPS_DIR and AUTO_UPDATE=false are the only
     * ones set, as auto-update would fetch the real registry.
     */
    internal fun command(
        program: String,
        args: List<String>,
        path: String,
    ): Command {
        val cmd = Command(program, args)
        envVars().map { it.first }.filter { it.startsWith("KETCH_") }.forEach(cmd::envRemove)
        return cmd
            .env("KETCH_ROOT", root.toString())
            .env("KETCH_APPS_DIR", apps.toString())
            .env("KETCH_AUTO_UPDATE", "false")
            .env("NO_COLOR", "1")
            .env("PATH", path)
            // Shell setup writes into $HOME; pointing it at the sandbox keeps the suite from editing a real .zshrc.
            .env("HOME", home.toString())
            .env("SHELL", "/bin/zsh")
            // Homebrew's and mise's own answers to where they live, so detection looks inside the sandbox only.
            .env("HOMEBREW_PREFIX", homebrew.toString())
            .env("MISE_DATA_DIR", miseData.toString())
            .envRemove("ZDOTDIR")
            .envRemove("XDG_CONFIG_HOME")
            // A token in the ambient environment (CI always has one) must not reach a test: nothing may touch the network.
            .envRemove("GITHUB_TOKEN")
            .envRemove("GH_TOKEN")
    }

    internal fun pathWithBin() = joinPath(listOf(bin.toString()) + inheritedPath())

    private fun writePlugin() {
        val caps = """{"protocol":1,"scheme":"test","download":true,"search":false}"""
        val script =
            """
            |#!/bin/sh
            |set -eu
            |DB=${shellQuote(assets)}
            |case "$1" in
            |capabilities) printf '%s' '$caps' ;;
            |describe) printf 'null' ;;
            |releases) cat "${'$'}DB/${'$'}2.releases.json" ;;
            |search) printf '[]' ;;
            |download) cp "${'$'}2" "${'$'}3" ;;
            |*) echo "unsupported subcommand: ${'$'}1" >&2; exit 1 ;;
            |esac
            |
            """.trimMargin()
        val plugin = root.resolve("plugins/ketch-source-test")
        writeText(plugin, script)
        makeExecutable(plugin)
    }
}

fun Path.resolve(child: String) = Path(this, child)

internal fun inheritedPath() = getenv("PATH")?.toKString()?.split(':')?.filter { it.isNotEmpty() } ?: emptyList()

internal fun joinPath(dirs: List<String>) = dirs.joinToString(":")
