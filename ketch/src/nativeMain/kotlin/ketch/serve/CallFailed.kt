package ketch.serve

import ketch.core.changelog.sanitize
import ketch.core.error.KetchError
import ketch.wire.KetchError as WireError

/**
 * A call that failed, with the error the app is told (`ketch-ffi`'s `error.rs`). The core's errors have two dozen cases
 * shaped for a terminal; the app needs only the few it acts on differently, and every other one keeps the CLI's
 * wording.
 */
class CallFailed(
    val error: WireError,
    cause: Throwable? = null,
) : Exception(error.display(), cause)

/** How the error reads in one line, as the Rust error's `Display` writes it. */
fun WireError.display(): String =
    when (this) {
        is WireError.Busy -> "another ketch process holds the lock"
        WireError.Cancelled -> "cancelled"
        is WireError.NotFound -> "`$name` not found"
        is WireError.Network -> message
        is WireError.Verification -> message
        is WireError.Other -> message
    }

internal fun KetchError.toWire(): WireError =
    when (this) {
        is KetchError.Busy -> {
            WireError.Busy(pid)
        }

        is KetchError.Cancelled -> {
            WireError.Cancelled
        }

        is KetchError.NotInstalled -> {
            WireError.NotFound(name)
        }

        is KetchError.NoRelease -> {
            WireError.NotFound(name)
        }

        is KetchError.Http, is KetchError.Network -> {
            WireError.Network(describe())
        }

        is KetchError.ChecksumMismatch, is KetchError.ChecksumMissing, is KetchError.ChecksumDropped -> {
            WireError.Verification(describe())
        }

        else -> {
            WireError.Other(describe())
        }
    }

/**
 * Any failure as the app is told it. Rust's core has one error type; here a library under the core can still throw its
 * own exception, and the app must hear about it rather than lose the call.
 */
internal fun Throwable.toWire(): WireError =
    when (this) {
        is CallFailed -> error
        is KetchError -> toWire()
        else -> WireError.Other(sanitize(message ?: toString()))
    }

/** The headline, its detail lines and its hint, as the CLI prints them under one another. */
private fun KetchError.describe() = sanitize((listOf(message) + details() + listOfNotNull(hint())).joinToString("\n"))
