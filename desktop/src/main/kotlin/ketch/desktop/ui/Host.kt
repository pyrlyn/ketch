// What the screens ask of the system: paths as a person reads them, its file manager and the clipboard.
package ketch.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.IOException

/** [path] with the home directory as `~`. */
fun tilde(
    path: String,
    home: String = System.getProperty("user.home"),
) = if (path == home || path.startsWith("$home/") || path.startsWith("$home\\")) "~${path.removePrefix(home)}" else path

/** The last part of a path the core wrote, which is the host's own form: a Windows path has backslashes. */
fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

/** Asks the file system once for a row, so a list does not stat on every frame. */
@Composable
fun pathExists(path: String) = remember(path) { File(path).exists() }

/**
 * Shows [path] selected in the file manager. Asked only of a path that exists, so the person's click always does
 * something. AWT has no such action on Windows, so Explorer is asked directly, with no shell in between.
 */
fun reveal(path: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
        Desktop.getDesktop().browseFileDirectory(File(path))
    } else if (System.getProperty("os.name").startsWith("Windows")) {
        try {
            ProcessBuilder("explorer.exe", "/select,", path).start()
        } catch (e: IOException) {
            System.err.println("ketch: could not show $path: ${e.message}")
        }
    }
}

/** Opens the folder [path] in the file manager. */
fun openFolder(path: String) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(File(path))
    } catch (e: IOException) {
        System.err.println("ketch: could not open $path: ${e.message}")
    }
}

fun copyToClipboard(text: String) {
    if (!GraphicsEnvironment.isHeadless()) Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
