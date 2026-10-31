// The one place that knows how a screen is compared with its reference, so the library can be replaced alone.
@file:OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)

package ketch.desktop.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performMouseInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import io.github.takahirom.roborazzi.captureRoboImage
import java.io.File

/** Each system draws text with its own fonts, so each keeps its own references. */
private val system =
    System.getProperty("os.name").let {
        when {
            it.startsWith("Mac") -> "macos"
            it.startsWith("Windows") -> "windows"
            else -> "linux"
        }
    }

private val references = File("e2e/references/$system")

// Identical on one machine; this leaves room for a font rasterizer's update, which moves a few edge pixels of text.
private const val CHANGED_SHARE = 0.002f
private const val SETTLE_MILLIS = 500L
private const val MAX_LOOKS = 20

private val options =
    RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(outputDirectoryPath = "build/e2e", changeThreshold = CHANGED_SHARE),
    )

/** Compares the whole window, dialogs included, with the reference called [name], or records it when the build asks for that. */
fun DesktopComposeUiTest.assertScreen(name: String) = still().captureRoboImage(references.resolve("$name.png").path, options)

/**
 * The window once two frames in a row agree. A click leaves the pointer over a row, which lights up or not depending on
 * when the next frame came, and a fade outlives waitForIdle on a busy machine.
 */
private fun DesktopComposeUiTest.still(): ImageBitmap {
    onAllNodes(isRoot())[0].performMouseInput { moveTo(Offset.Zero) }
    var last = frame()
    repeat(MAX_LOOKS) {
        val next = frame()
        if (next.toPixelMap().buffer.contentEquals(last.toPixelMap().buffer)) return next
        last = next
    }
    error("the window never stopped moving")
}

private fun DesktopComposeUiTest.frame(): ImageBitmap {
    mainClock.advanceTimeBy(SETTLE_MILLIS)
    waitForIdle()
    return captureToImage()
}
