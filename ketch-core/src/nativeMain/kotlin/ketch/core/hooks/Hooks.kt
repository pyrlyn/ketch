// Lifecycle hooks: the commands a manifest's `[hooks]` table runs around an install, an update and an uninstall.
//
// Kept apart from install because this is the one place ketch runs a command a manifest wrote. That turns a manifest
// into a program, so hooks are honoured only from the user's own manifest directory: a registry entry or a built-in
// manifest is someone else's file, and installing a package must never mean running their shell. `allowed` is that
// rule; install applies it before anything is placed or removed.
package ketch.core.hooks

import ketch.core.error.KetchError
import ketch.core.model.Hooks
import ketch.core.model.ManifestOrigin
import ketch.core.report.Report
import ketch.core.text.rustLines
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import process.Command
import process.ProcessException
import process.runBounded
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a hook may run. Long enough for a real setup step, short enough that a hook waiting on something that
 * never comes does not hold the ketch lock for the rest of the day.
 */
private val HOOK_TIMEOUT = 600.seconds

/** A source plugin's cap (Rust `PLUGIN_MAX_OUTPUT`), since a hook gets the same guards. */
private const val MAX_OUTPUT = 8L shl 20

/** Which moment in a package's life a hook runs at; [key] is the manifest key, which is also what `KETCH_HOOK` carries. */
enum class Event(
    val key: String,
    private val command: (Hooks) -> String?,
) {
    BeforeInstall("before_install", Hooks::beforeInstall),
    AfterInstall("after_install", Hooks::afterInstall),
    BeforeUpdate("before_update", Hooks::beforeUpdate),
    AfterUpdate("after_update", Hooks::afterUpdate),
    BeforeUninstall("before_uninstall", Hooks::beforeUninstall),
    AfterUninstall("after_uninstall", Hooks::afterUninstall),
    ;

    internal fun command(hooks: Hooks) = command.invoke(hooks)
}

/** What a hook is told about the package it runs for, as `KETCH_*` variables. */
class Context(
    val name: String,
    val version: String,
    /** The version being replaced; only update events have one. */
    val previous: String?,
    /** The package's store prefix. Missing before an install and after an uninstall, and the hook's working directory whenever it exists. */
    val prefix: Path,
    val binDir: Path,
    val root: Path,
    /** Where the hook's announcement, its output and an `after_*` failure go. */
    val report: Report,
)

/** Whether a manifest of this origin may run hooks at all. */
fun allowed(origin: ManifestOrigin) = origin is ManifestOrigin.User

/** The origin that would be refused, in the words the user needs to fix it. */
fun refusal(
    origin: ManifestOrigin,
    name: String,
): KetchError =
    KetchError.Msg(
        "`$name` has hooks in a ${origin.tier} manifest (${origin.location()}); hooks run only from a manifest in " +
            "your own manifest directory — copy it there to opt in",
    )

/** Run the hook for [event], if the manifest has one. No hook is not an error; a hook that exits non-zero is, and its stderr is the error's detail. */
fun run(
    hooks: Hooks,
    event: Event,
    ctx: Context,
) = runWith(hooks, event, ctx, HOOK_TIMEOUT)

internal fun runWith(
    hooks: Hooks,
    event: Event,
    ctx: Context,
    timeout: Duration,
) {
    val script = event.command(hooks) ?: return
    val label = "${event.key} hook for ${ctx.name}"
    ctx.report.step("running", label)
    val cmd =
        shell(script)
            .env("KETCH_HOOK", event.key)
            .env("KETCH_PACKAGE", ctx.name)
            .env("KETCH_VERSION", ctx.version)
            .env("KETCH_PREFIX", ctx.prefix.toString())
            .env("KETCH_BIN_DIR", ctx.binDir.toString())
            .env("KETCH_ROOT", ctx.root.toString())
    ctx.previous?.let { cmd.env("KETCH_PREVIOUS_VERSION", it) }
    if (SystemFileSystem.metadataOrNull(ctx.prefix)?.isDirectory == true) cmd.currentDir(ctx.prefix.toString())
    // The same guards a source plugin gets: no stdin, bounded output, and a deadline after which the whole process
    // tree is killed. Output is captured rather than inherited because a progress bar may own the terminal, and the
    // log should have it whether or not the user saw it.
    val out =
        try {
            cmd.runBounded(timeout, MAX_OUTPUT)
        } catch (e: ProcessException) {
            throw KetchError.Command(label, e.detail, e.stderr.decodeToString(), e)
        }
    rustLines(out.stdout.decodeToString()).forEach { ctx.report.debug("${event.key}: $it") }
    if (!out.status.success) throw KetchError.Command(label, out.status.toString(), out.stderr.decodeToString())
}

/** [run] for the `after_*` events: the operation has already happened and state records it, so a hook failing now is reported, never propagated. */
fun runOrWarn(
    hooks: Hooks,
    event: Event,
    ctx: Context,
) {
    try {
        run(hooks, event, ctx)
    } catch (e: KetchError) {
        ctx.report.warn(e.message.orEmpty())
        e.details().forEach(ctx.report::warn)
    }
}

/** The platform shell, so a hook is one line of what the user already types. Windows (`cmd /C`) comes with T13. */
private fun shell(script: String) = Command("sh", listOf("-c", script))
