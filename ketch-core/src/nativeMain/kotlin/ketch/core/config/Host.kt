package ketch.core.config

import fs.joinPath
import kotlinx.io.files.Path
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
actual val HOST_WINDOWS: Boolean = Platform.osFamily == OsFamily.WINDOWS

internal actual fun joinedPath(
    base: Path,
    vararg parts: String,
): Path = joinPath(base, *parts)
