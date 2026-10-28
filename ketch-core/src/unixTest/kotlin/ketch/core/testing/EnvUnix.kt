@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.testing

import platform.posix.setenv
import platform.posix.unsetenv

actual fun setEnv(
    key: String,
    value: String,
) {
    check(setenv(key, value, 1) == 0) { "setenv $key" }
}

actual fun unsetEnv(key: String) {
    unsetenv(key)
}
