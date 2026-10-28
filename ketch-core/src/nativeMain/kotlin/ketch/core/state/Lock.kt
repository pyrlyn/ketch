@file:OptIn(ExperimentalAtomicApi::class)

package ketch.core.state

import fs.FsException
import fs.createDirAll
import fs.createNew
import fs.hardLink
import fs.modifiedAgo
import fs.readText
import fs.removeFile
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.text.rustTrim
import kotlinx.io.files.Path
import platform.posix.getpid
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Duration.Companion.seconds

/**
 * Exclusive access to the install tree, released by [close].
 *
 * Two `ketch install` runs writing the same `state.json` would each save a view that omits the other's package,
 * silently losing an install. Two layers enforce it: a lock file excludes other ketch processes, and [HELD] excludes
 * other threads of this one. A long-running host (the desktop app's `ketch serve`) calls the core concurrently from
 * one process, so a lock file naming our own pid proves nothing about who inside the process holds it: only [HELD]
 * does. Nothing nests a second acquire inside a held one, so a second acquire in this process is always a different
 * operation and fails as [KetchError.Busy].
 */
class Lock private constructor(
    private val path: Path,
) : AutoCloseable {
    /** Whether this lock wrote the lock file, and so must delete it. */
    private var wroteFile = false
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        // Only while it still names us: a file another process wrote is that process's to remove.
        if (wroteFile && readPid(path) == myPid()) runCatching { removeFile(path) }
        // After the file is gone, so a waiting thread never finds the claim released while the file still names us.
        releaseClaim(path)
    }

    companion object {
        /** Take the lock, or fail with [KetchError.Busy] without waiting. */
        fun acquire(ctx: Ctx) = acquirePath(ctx.cfg.lockFile, ctx.report)

        /** [acquire] for a lock file anywhere; [report] hears about a stale one cleared. */
        fun acquirePath(
            path: Path,
            report: Report,
        ): Lock {
            val parent = path.parent ?: Path(".")
            io(parent) { createDirAll(parent) }
            val me = myPid()
            claim(path, me)
            // From here `lock` releases the in-process claim on every way out but success.
            val lock = Lock(path)
            var taken = false
            try {
                taken = (0 until ATTEMPTS).any { io(path) { attempt(path, me, report) } }
            } finally {
                if (!taken) lock.close()
            }
            if (!taken) throw KetchError.Busy(null, path)
            lock.wroteFile = true
            return lock
        }

        /** One try: true when the lock is ours, false to try again, [KetchError.Busy] when someone else holds it. */
        private fun attempt(
            path: Path,
            me: Long,
            report: Report,
        ): Boolean {
            if (createExclusive(path, me)) return true
            return when (val holder = judge(path, me)) {
                // Released between the two calls: try again.
                null -> {
                    false
                }

                is Holder.Live -> {
                    throw KetchError.Busy(holder.pid, path)
                }

                is Holder.Stale -> {
                    val why = holder.pid?.let { "pid $it is gone" } ?: "unreadable"
                    report.debug("clearing stale lock $path ($why)")
                    when (val reclaimed = reclaim(path, me)) {
                        Reclaim.Taken -> true
                        Reclaim.Again -> false
                        is Reclaim.Busy -> throw KetchError.Busy(reclaimed.pid, path)
                    }
                }
            }
        }
    }
}

private const val ATTEMPTS = 3

/** An empty or unreadable lock or reclaim file younger than this belongs to a process still writing its pid, not to one that crashed. */
internal val UNREADABLE_GRACE = 10.seconds

/** Lock files currently held by this process. */
private val HELD = AtomicReference(emptySet<Path>())

private val STAGED = AtomicLong(0)

private fun myPid() = getpid().toLong()

/** Claim [path] for this process, or fail as [KetchError.Busy] naming us when another of our operations has it. */
private fun claim(
    path: Path,
    me: Long,
) {
    while (true) {
        val held = HELD.load()
        if (path in held) throw KetchError.Busy(me, path)
        if (HELD.compareAndSet(held, held + path)) return
    }
}

/** Drop this process's claim on [path]; what a crashed process leaves is the file without the claim. */
internal fun releaseClaim(path: Path) {
    while (true) {
        val held = HELD.load()
        if (HELD.compareAndSet(held, held - path)) return
    }
}

/** Who a lock file names, as far as this process can tell. */
private sealed interface Holder {
    val pid: Long?

    data class Live(
        override val pid: Long?,
    ) : Holder

    data class Stale(
        override val pid: Long?,
    ) : Holder
}

/** The pid a lock file names; null when it is missing, unreadable or not a pid. */
private fun readPid(path: Path): Long? =
    runCatching { readText(path) }
        .getOrNull()
        ?.rustTrim()
        ?.toUIntOrNull()
        ?.toLong()

/** Null when the file is gone. */
private fun judge(
    path: Path,
    me: Long,
): Holder? {
    val text =
        try {
            readText(path)
        } catch (e: FsException) {
            if (e.kind == FsException.Kind.NotFound) return null
            ""
        }
    val pid = text.rustTrim().toUIntOrNull()?.toLong()
    return when {
        // Our own pid with no in-process claim (the caller holds that claim) is a leftover: a crashed run whose pid
        // was reused.
        pid != null && pid != me && processAlive(pid) -> {
            Holder.Live(pid)
        }

        pid != null -> {
            Holder.Stale(pid)
        }

        // No age either means the file is gone.
        else -> {
            runCatching { modifiedAgo(path) }.getOrNull()?.let { age ->
                if (age < UNREADABLE_GRACE) Holder.Live(null) else Holder.Stale(null)
            }
        }
    }
}

/**
 * Create [path] holding [me], failing rather than replacing it; false when it already exists.
 *
 * The pid is written to a private file first and hard-linked into place, so the file is never seen empty: with an
 * exclusive create and a write after it, a second process can read the empty file in between, call it stale and
 * take the live lock away.
 */
private fun createExclusive(
    path: Path,
    me: Long,
): Boolean {
    val staged = withExtension(path, "new.$me.${STAGED.fetchAndIncrement()}")
    val pid = me.toString().encodeToByteArray()
    // A leftover from a crashed run with the same pid would make the exclusive create below fail.
    runCatching { removeFile(staged) }
    createNew(staged, pid)
    val linked = runCatching { hardLink(staged, path) }
    runCatching { removeFile(staged) }
    val failure = linked.exceptionOrNull()
    return when {
        failure == null -> true

        failure !is FsException -> throw failure

        failure.kind == FsException.Kind.AlreadyExists -> false

        // A file system without hard links (FAT, some network shares). The pid then lands with the file, and
        // UNREADABLE_GRACE covers a reader that still finds it empty.
        else -> createNewOnce(path, pid)
    }
}

/** False when [path] already exists. */
private fun createNewOnce(
    path: Path,
    bytes: ByteArray,
): Boolean =
    try {
        createNew(path, bytes)
        true
    } catch (e: FsException) {
        if (e.kind != FsException.Kind.AlreadyExists) throw e
        false
    }

internal sealed interface Reclaim {
    data object Taken : Reclaim

    data class Busy(
        val pid: Long?,
    ) : Reclaim

    data object Again : Reclaim
}

/**
 * Replace a stale lock file with ours, as the one process allowed to.
 *
 * Renaming or deleting it on the strength of an earlier read is a race: by then another process may have reclaimed
 * it and written a fresh lock, which would be thrown away, and both would run. So the right to reclaim is a second
 * file, taken the same exclusive way. Only its holder removes the lock file, and judges it again first; nothing else
 * can replace a lock file that exists, so what it judged is what it removes.
 */
internal fun reclaim(
    path: Path,
    me: Long,
): Reclaim {
    val right = withExtension(path, "reclaim")
    if (!createExclusive(right, me)) {
        // Held for microseconds, so one that is old as well as orphaned was left by a crash mid-reclaim. Clearing it
        // is still a race with another process clearing it, which is why this attempt stops here.
        val old = runCatching { modifiedAgo(right) }.getOrNull()?.let { it >= UNREADABLE_GRACE } == true
        if (old && judge(right, me) is Holder.Stale) runCatching { removeFile(right) }
        return Reclaim.Busy(null)
    }
    try {
        return when (val holder = judge(path, me)) {
            null -> Reclaim.Again
            is Holder.Live -> Reclaim.Busy(holder.pid)
            is Holder.Stale -> replaceStale(path, me)
        }
    } finally {
        runCatching { removeFile(right) }
    }
}

private fun replaceStale(
    path: Path,
    me: Long,
): Reclaim {
    try {
        removeFile(path)
    } catch (e: FsException) {
        if (e.kind == FsException.Kind.NotFound) return Reclaim.Again
        throw e
    }
    return if (createExclusive(path, me)) Reclaim.Taken else Reclaim.Again
}

/** Rust `Path::with_extension`: [ext] in place of the last extension, a leading dot not counting as one. */
private fun withExtension(
    path: Path,
    ext: String,
): Path {
    val name = path.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return path.parent?.let { Path(it, "$stem.$ext") } ?: Path("$stem.$ext")
}

/**
 * Is that pid still running? Only consulted when a lock file already exists, so running a program costs nothing on
 * the normal path. Asked of a program (`ps`, `tasklist`) whose answer is unambiguous; unsure means "assume held" —
 * never steal on doubt.
 */
expect fun processAlive(pid: Long): Boolean

private inline fun <T> io(
    path: Path,
    body: () -> T,
): T =
    try {
        body()
    } catch (e: FsException) {
        throw KetchError.fsKit(path, e)
    }
