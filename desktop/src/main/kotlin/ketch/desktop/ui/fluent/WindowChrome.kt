package ketch.desktop.ui.fluent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import pyrlyn.fluent.WindowMaterial

/** What the title bar does in place of the system's frame. A window with a frame of its own, and a test, has none. */
class WindowChrome(
    val isMaximized: () -> Boolean,
    val minimize: () -> Unit,
    val toggleMaximize: () -> Unit,
    val close: () -> Unit,
    val drag: @Composable (Modifier) -> Unit,
    /** Puts a system material behind the window; false when the window has to draw its own wall. */
    val wear: (WindowMaterial, dark: Boolean) -> Boolean = { _, _ -> false },
)

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }
