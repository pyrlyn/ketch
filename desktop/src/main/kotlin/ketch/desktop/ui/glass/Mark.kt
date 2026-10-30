// The ketch mark as a vector, from the SVG the canvas draws.
package ketch.desktop.ui.glass

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pyrlyn.glass.GlassColors

private const val VIEWPORT = 64f
private const val TAG_TURN = -18f
private const val TAG_X = 34f
private const val TAG_Y = 22f

private fun mark(tile: Boolean): ImageVector =
    ImageVector
        .Builder("ketch", 64.dp, 64.dp, VIEWPORT, VIEWPORT)
        .apply {
            if (tile) {
                addPath(addPathNodes("M14 0h36a14 14 0 0 1 14 14v36a14 14 0 0 1-14 14H14A14 14 0 0 1 0 50V14A14 14 0 0 1 14 0z"), fill = SolidColor(GlassColors.Deep))
            }
            val hook: Brush = SolidColor(GlassColors.Mint)
            addPath(addPathNodes("M22 14v28"), stroke = hook, strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round)
            addPath(addPathNodes("M22 42c0 8 6.5 12.5 14 12.5 7 0 12.5-4 12.5-10.5"), stroke = hook, strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round)
            addPath(addPathNodes("M48.5 44v-6.5"), stroke = hook, strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round)
            addPath(addPathNodes("M22 9.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z"), stroke = hook, strokeLineWidth = 3.5f)
            if (tile) {
                group(rotate = TAG_TURN, translationX = TAG_X, translationY = TAG_Y) {
                    addPath(addPathNodes("M3.5 0h15a3.5 3.5 0 0 1 3.5 3.5v7a3.5 3.5 0 0 1-3.5 3.5h-15A3.5 3.5 0 0 1 0 10.5v-7A3.5 3.5 0 0 1 3.5 0z"), fill = SolidColor(GlassColors.Coral))
                    addPath(addPathNodes("M5.5 4.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z"), fill = SolidColor(GlassColors.Deep))
                }
            }
        }.build()

private val MARK_TILE = mark(tile = true)
private val MARK_HOOK = mark(tile = false)

/** The ketch mark; decorative, the name stands beside it. */
@Composable
fun Mark(
    size: Dp,
    tile: Boolean = true,
    modifier: Modifier = Modifier,
) = Image(if (tile) MARK_TILE else MARK_HOOK, contentDescription = null, modifier = modifier.size(size))
