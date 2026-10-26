package ketch.core.cancel

import ketch.core.error.KetchError
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Cooperative cancellation for core operations. A host that outlives one command (the desktop app's
 * `ketch serve`) needs a way to stop an install it started. The token is polled, never signalled: the pipeline
 * checks it between packages and between download chunks, so a cancelled run unwinds through the same cleanup as
 * any other error and leaves no temp directory, partial store folder or state entry.
 *
 * One instance is shared: the host keeps it and hands the same object to the operation.
 */
@OptIn(ExperimentalAtomicApi::class)
class Cancel {
    private val flag = AtomicBoolean(false)

    /** Ask the operation holding this token to stop at its next check. */
    fun cancel() = flag.store(true)

    val isCancelled: Boolean get() = flag.load()

    /** Throws [KetchError.Cancelled] once cancelled, for use at a check point. */
    fun check() {
        if (isCancelled) throw KetchError.Cancelled()
    }
}
