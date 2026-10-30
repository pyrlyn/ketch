// The tray icon and its menu, as the macOS menu-bar panel has them: the update count with Update all, a row per pending
// update, the running operation, then Open, Check for updates, Settings and Quit. The menu is a plain model so a test
// can read it; Main.kt hands it to Compose's Tray.
package ketch.desktop.ui

import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

sealed interface TrayAction {
    data class Update(
        val name: String,
    ) : TrayAction

    data object UpdateAll : TrayAction

    data object Cancel : TrayAction

    data object Open : TrayAction

    data object Check : TrayAction

    data object Settings : TrayAction

    data object Quit : TrayAction
}

sealed interface TrayEntry {
    /** A row; one with no action is a line of text. */
    data class Item(
        val label: String,
        val action: TrayAction? = null,
        val enabled: Boolean = action != null,
    ) : TrayEntry

    data object Separator : TrayEntry
}

/** More rows than this would push the menu rows off a small screen; the window lists them all. */
const val TRAY_UPDATE_ROWS = 5

fun trayMenu(state: StoreState): List<TrayEntry> =
    buildList {
        val idle = !state.isRunning
        add(TrayEntry.Item("${plural(state.pendingUpgradeCount, Res.plurals.updates)} · ${plural(state.installed.size, Res.plurals.installed_count)}"))
        state.updates.take(TRAY_UPDATE_ROWS).forEach {
            add(TrayEntry.Item(tr(Res.string.tray_update_row, it.name, it.installed, it.latest), TrayAction.Update(it.name), idle))
        }
        // The confirmation is the window's; the menu only asks for it.
        add(TrayEntry.Item(tr(Res.string.action_update_all_ellipsis), TrayAction.UpdateAll, idle && state.pendingUpgradeCount > 0))
        state.activity?.let { activity ->
            add(TrayEntry.Separator)
            add(TrayEntry.Item("${activity.title} · ${(activity.fraction * PERCENT).roundToInt()}%"))
            add(TrayEntry.Item(tr(Res.string.action_cancel), TrayAction.Cancel, !activity.isCancelling))
        }
        add(TrayEntry.Separator)
        add(TrayEntry.Item(tr(Res.string.tray_open), TrayAction.Open))
        add(TrayEntry.Item(tr(Res.string.settings_check_updates), TrayAction.Check, idle))
        add(TrayEntry.Item(tr(Res.string.tray_settings), TrayAction.Settings))
        add(TrayEntry.Separator)
        add(TrayEntry.Item(tr(Res.string.tray_quit), TrayAction.Quit))
    }

/** A bare icon says nothing to a screen reader or a hovering pointer. */
fun trayTooltip(state: StoreState): String {
    val parts = mutableListOf("Ketch")
    if (state.pendingUpgradeCount > 0) parts += plural(state.pendingUpgradeCount, Res.plurals.updates_available)
    if (state.isRunning) parts += tr(Res.string.tray_working)
    return parts.joinToString(", ")
}

/** The accent square with the pending count on it, or a box outline when there is nothing pending. */
fun trayImage(
    count: Int,
    accent: Color,
    size: Int = ICON_SIZE,
): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    val inset = size / INSET_DIVISOR
    val side = size - 2 * inset
    val corner = size / CORNER_DIVISOR
    g.color = accent
    if (count > 0) {
        g.fillRoundRect(inset, inset, side, side, corner, corner)
        val text = if (count > MAX_SHOWN) "$MAX_SHOWN+" else count.toString()
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (side * if (text.length > 1) SMALL_TEXT else LARGE_TEXT).roundToInt())
        val metrics = g.fontMetrics
        g.color = Color.WHITE
        g.drawString(text, (size - metrics.stringWidth(text)) / 2, (size - metrics.height) / 2 + metrics.ascent)
    } else {
        g.stroke = java.awt.BasicStroke(size / STROKE_DIVISOR)
        g.drawRoundRect(inset, inset, side, side, corner, corner)
        g.drawLine(inset, size / 2, size - inset, size / 2)
    }
    g.dispose()
    return image
}

private const val PERCENT = 100
private const val ICON_SIZE = 32
private const val INSET_DIVISOR = 8
private const val CORNER_DIVISOR = 4
private const val STROKE_DIVISOR = 12f
private const val MAX_SHOWN = 9
private const val SMALL_TEXT = 0.5f
private const val LARGE_TEXT = 0.7f
