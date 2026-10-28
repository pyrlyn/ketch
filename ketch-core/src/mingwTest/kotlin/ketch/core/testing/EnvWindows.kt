@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.testing

import kotlinx.cinterop.wcstr
import platform.posix._wputenv_s

// _wputenv_s, not SetEnvironmentVariableW: it changes the C runtime's copy, which ketch reads through _wgetenv, and
// the process environment children inherit.
actual fun setEnv(
    key: String,
    value: String,
) {
    check(_wputenv_s(key.wcstr, value.wcstr) == 0) { "_wputenv_s $key" }
}

/** An empty value removes the variable. */
actual fun unsetEnv(key: String) {
    _wputenv_s(key.wcstr, "".wcstr)
}
