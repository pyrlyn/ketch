package ketch.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Rectangle

/** An edge or corner of a window, by the way a pull on it moves each side: -1 the left or top, 1 the right or bottom. */
internal enum class Edge(
    val horizontal: Int,
    val vertical: Int,
    val cursor: Int,
    val alignment: Alignment,
) {
    Left(-1, 0, Cursor.W_RESIZE_CURSOR, Alignment.CenterStart),
    Right(1, 0, Cursor.E_RESIZE_CURSOR, Alignment.CenterEnd),
    Top(0, -1, Cursor.N_RESIZE_CURSOR, Alignment.TopCenter),
    Bottom(0, 1, Cursor.S_RESIZE_CURSOR, Alignment.BottomCenter),
    TopLeft(-1, -1, Cursor.NW_RESIZE_CURSOR, Alignment.TopStart),
    TopRight(1, -1, Cursor.NE_RESIZE_CURSOR, Alignment.TopEnd),
    BottomLeft(-1, 1, Cursor.SW_RESIZE_CURSOR, Alignment.BottomStart),
    BottomRight(1, 1, Cursor.SE_RESIZE_CURSOR, Alignment.BottomEnd),
    ;

    /** [start] pulled by ([dx], [dy]), no smaller than [least], with the far sides staying where they were. */
    fun resized(
        start: Rectangle,
        dx: Int,
        dy: Int,
        least: Dimension,
    ): Rectangle {
        val width = (start.width + dx * horizontal).coerceAtLeast(least.width)
        val height = (start.height + dy * vertical).coerceAtLeast(least.height)
        val x = if (horizontal < 0) start.x + start.width - width else start.x
        val y = if (vertical < 0) start.y + start.height - height else start.y
        return Rectangle(x, y, width, height)
    }
}

/**
 * AWT gives a window without the system's frame no edges to pull, so the look lays its own over its border. The pull is
 * read from the screen, not the pointer's place in the window: the window moves under it as it grows to the left or up.
 */
@Composable
internal fun ResizeEdges(window: ComposeWindow) {
    Box(Modifier.fillMaxSize()) {
        for (edge in Edge.entries) EdgeHandle(edge, window)
    }
}

@Composable
private fun BoxScope.EdgeHandle(
    edge: Edge,
    window: ComposeWindow,
) {
    val shape =
        when {
            edge.horizontal != 0 && edge.vertical != 0 -> Modifier.size(CORNER)
            edge.horizontal != 0 -> Modifier.width(THICKNESS).fillMaxHeight()
            else -> Modifier.height(THICKNESS).fillMaxWidth()
        }
    Box(
        Modifier
            .align(edge.alignment)
            .then(shape)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(edge.cursor)))
            .pointerInput(edge) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    val from = MouseInfo.getPointerInfo().location
                    val start = window.bounds
                    do {
                        val event = awaitPointerEvent()
                        val now = MouseInfo.getPointerInfo().location
                        window.bounds = edge.resized(start, now.x - from.x, now.y - from.y, window.minimumSize)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    )
}

private val THICKNESS = 5.dp
private val CORNER = 12.dp
