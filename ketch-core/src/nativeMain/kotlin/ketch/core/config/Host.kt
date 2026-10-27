package ketch.core.config

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
internal actual val HOST_WINDOWS: Boolean = Platform.osFamily == OsFamily.WINDOWS
