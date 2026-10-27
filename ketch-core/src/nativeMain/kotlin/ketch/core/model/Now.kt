@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.model

import platform.posix.time

/** Seconds since the Unix epoch, 0 on a clock before it. */
fun nowUnix(): Long = time(null).coerceAtLeast(0)
