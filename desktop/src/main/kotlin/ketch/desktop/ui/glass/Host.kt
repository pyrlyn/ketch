// What the screens ask of the Mac: paths as a person reads them, Finder and the clipboard.
package ketch.desktop.ui.glass

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/** [path] with the home directory as `~`. */
fun tilde(
    path: String,
    home: String = System.getProperty("user.home"),
) = if (path == home || path.startsWith("$home/")) "~${path.removePrefix(home)}" else path

/** Shows [path] selected in Finder. Asked only of a path that exists, so the person's click always does something. */
fun reveal(path: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
        Desktop.getDesktop().browseFileDirectory(File(path))
    }
}

fun copyToClipboard(text: String) {
    if (!GraphicsEnvironment.isHeadless()) Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
