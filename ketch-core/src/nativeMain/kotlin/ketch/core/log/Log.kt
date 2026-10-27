@file:OptIn(ExperimentalAtomicApi::class)

package ketch.core.log

import ketch.core.VERSION
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.model.nowUnix
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.posix.getpid
import platform.posix.sched_yield
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The log file. Every status line ketch writes also goes here, minus the colour and plus a timestamp: a terminal
 * scrolls away and a failed install is usually reported hours later. Nothing here may fail loudly; a package manager
 * that refuses to install because it could not open its log is broken.
 */
object Log {
    /** Rotate at this size. One old file is kept, so the logs cost at most twice this much. */
    private const val MAX_BYTES = 5L * 1024 * 1024

    internal class LogSink(
        val file: Sink,
        val path: Path,
        val level: Level,
        val format: Format,
        val pid: Int,
    )

    private val busy = AtomicBoolean(false)
    private var sink: LogSink? = null

    private inline fun <T> locked(body: () -> T): T {
        while (!busy.compareAndSet(expectedValue = false, newValue = true)) sched_yield()
        try {
            return body()
        } finally {
            busy.store(false)
        }
    }

    /** `--verbose` raises the sink to debug, so debug detail lands in the log even at a configured info. */
    internal fun fileLevel(
        configured: Level,
        verbose: Boolean,
    ): Level = if (configured != Level.Off && verbose && configured < Level.Debug) Level.Debug else configured

    /**
     * Open the log for this run. The failure is returned for the host to warn about, not to log: the log is what
     * failed. [args] are the command line, recorded in the first line so a log entry names the run it came from.
     */
    fun init(
        cfg: Config,
        verbose: Boolean,
        args: List<String> = emptyList(),
    ) {
        val level = fileLevel(cfg.logLevel, verbose)
        if (level == Level.Off) {
            // A host calls `init` once per operation: turning the log off must also drop the sink an earlier
            // operation opened, or it keeps writing.
            set(null)
            return
        }
        val opened =
            try {
                open(cfg.logFile, level, cfg.logFormat)
            } catch (e: kotlinx.io.IOException) {
                throw KetchError.Msg("could not open ${cfg.logFile}: ${e.message}", e)
            }
        set(opened)
        record(Level.Info, "ketch $VERSION · ${args.joinToString(" ")}")
    }

    /** Where this run is logging, once [init] has succeeded. */
    fun path(): Path? = locked { sink?.path }

    /** Write one record. Never fails. */
    fun record(
        level: Level,
        message: String,
    ) = locked {
        val s = sink ?: return@locked
        if (!level.passes(s.level)) return@locked
        val line =
            when (s.format) {
                Format.Text -> {
                    "${timestamp(nowUnix())} [${s.pid}] ${level.label.padEnd(LABEL_WIDTH)} ${escape(message)}\n"
                }

                // In key order, as Rust's serde_json writes a map.
                Format.Json -> {
                    buildJsonObject {
                        put("level", level.toString())
                        put("msg", message)
                        put("pid", s.pid)
                        put("time", timestamp(nowUnix()))
                    }.toString() + "\n"
                }
            }
        // A log that cannot be written is not a reason to fail the command it was recording, and reporting it
        // here would recurse straight back in.
        runCatching {
            s.file.writeString(line)
            s.file.flush()
        }
    }

    /** Rust `{:<5}`: the labels line up. */
    private const val LABEL_WIDTH = 5

    /** Swap the sink, returning the one it replaces, still open; null closes nothing. */
    internal fun swap(next: LogSink?): LogSink? =
        locked {
            val previous = sink
            sink = next
            previous
        }

    internal fun set(next: LogSink?) {
        val previous = swap(next)
        if (previous != null && previous !== next) runCatching { previous.file.close() }
    }

    internal fun open(
        path: Path,
        level: Level,
        format: Format,
    ): LogSink {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        rotate(path)
        val file = SystemFileSystem.sink(path, append = true).buffered()
        return LogSink(file, path, level, format, getpid())
    }

    private fun rotate(path: Path) {
        val full = (SystemFileSystem.metadataOrNull(path)?.size ?: 0) >= MAX_BYTES
        if (full) runCatching { SystemFileSystem.atomicMove(path, rotated(path)) }
    }

    /** Rust `with_extension("log.1")`: `ketch.log` becomes `ketch.log.1`. */
    private fun rotated(path: Path): Path {
        val name = path.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        return Path(path.parent ?: Path("."), "$stem.log.1")
    }
}
