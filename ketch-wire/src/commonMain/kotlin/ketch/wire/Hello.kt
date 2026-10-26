package ketch.wire

import kotlinx.serialization.Serializable

/** The first line `ketch serve` writes, so the desktop app can refuse a binary that speaks another protocol. */
@Serializable
data class Hello(
    val protocol: Int,
    val version: String,
)

/** Bumped whenever a record or message changes shape. */
const val PROTOCOL = 1
