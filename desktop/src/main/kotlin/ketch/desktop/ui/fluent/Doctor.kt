package ketch.desktop.ui.fluent

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.doctorSummary
import ketch.desktop.ui.openFolder
import ketch.desktop.ui.word
import ketch.wire.Check
import ketch.wire.CheckOutcome
import ketch.wire.Settings
import pyrlyn.fluent.BadgeKind
import pyrlyn.fluent.ButtonStyle
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.IconGradient
import pyrlyn.fluent.ListRow
import pyrlyn.fluent.LocalFluent
import pyrlyn.fluent.LocalFluentType
import pyrlyn.fluent.PageHead
import pyrlyn.fluent.PageTitle
import pyrlyn.fluent.RowGroup
import pyrlyn.fluent.RowMeta
import pyrlyn.fluent.RowTitle
import pyrlyn.fluent.StatCard
import pyrlyn.fluent.StatusBadge
import pyrlyn.glass.AutoGrid
import pyrlyn.glass.Glyph

@Composable
fun FluentDoctor(
    state: StoreState,
    actions: Actions,
    config: Settings?,
) {
    val c = LocalFluent.current
    FluentPage("doctor") {
        PageHead(
            title = { PageTitle("Doctor") },
            actions = {
                Text(doctorSummary(state), color = c.ink2, style = LocalFluentType.current.small, modifier = Modifier.testTag("doctor-summary"))
                FluentButton("Run again") { actions.run { runDoctor() } }
            },
        )
        AutoGrid(STATS.size, STAT_WIDTH, GRID_GAP) { index ->
            val stat = STATS[index]
            val count = state.checks.count { it.outcome == stat.outcome }
            StatCard("$count", if (count == 1) stat.one else stat.many, stat.glyph, stat.gradient)
        }
        RowGroup { for (check in state.checks) CheckRow(check, state, actions, config) }
    }
}

private class Stat(
    val outcome: CheckOutcome,
    val one: String,
    val many: String,
    val glyph: Glyph,
    val gradient: IconGradient,
)

private val STATS =
    listOf(
        Stat(CheckOutcome.Ok, "check passed", "checks passed", Glyph.Check, IconGradient.Green),
        Stat(CheckOutcome.Warn, "warning", "warnings", Glyph.Warning, IconGradient.Amber),
        Stat(CheckOutcome.Fail, "failed", "failed", Glyph.Cross, IconGradient.Red),
    )

@Composable
private fun CheckRow(
    check: Check,
    state: StoreState,
    actions: Actions,
    config: Settings?,
) {
    // A repair of PATH reaches the next shell, not this process, so the check that follows it is only a warning.
    val fixed = check.name in state.fixed && check.outcome != CheckOutcome.Fail
    val kind =
        when {
            fixed || check.outcome == CheckOutcome.Ok -> BadgeKind.Success
            check.outcome == CheckOutcome.Warn -> BadgeKind.Warning
            else -> BadgeKind.Danger
        }
    val word = if (fixed) "Fixed" else check.outcome.word
    val root = config?.root?.takeIf { check.name in UNDER_THE_ROOT && check.outcome != CheckOutcome.Ok }
    ListRow(Modifier.testTag("check-${check.name}"), actions = {
        if (check.fixable) FixButton(check.name, actions)
        root?.let { FluentButton("Open folder", modifier = Modifier.testTag("folder-${check.name}")) { openFolder(it) } }
    }, leading = { StatusBadge(word, kind, Modifier.widthIn(min = BADGE_WIDTH)) }) {
        RowTitle(check.name)
        RowMeta(check.detail)
        check.fix?.let { fix -> SelectionContainer { Text("Fix: $fix", color = LocalFluent.current.ink2, style = LocalFluentType.current.mono) } }
    }
}

/** A repair goes through PowerShell and takes seconds: the button has to say the press landed. */
@Composable
private fun FixButton(
    name: String,
    actions: Actions,
) {
    var fixing by remember { mutableStateOf(false) }
    FluentButton(if (fixing) "Fixing…" else "Fix", ButtonStyle.Accent, Modifier.testTag("fix-$name"), enabled = !fixing) {
        fixing = true
        actions.run {
            fixDoctor()
            fixing = false
        }
    }
}

/** Checks about files in ketch's own folder, which is where to look when they fail. */
private val UNDER_THE_ROOT = setOf("state", "lock", "log", "packages")

private val STAT_WIDTH = 200.dp
private val GRID_GAP = 12.dp
private val BADGE_WIDTH = 72.dp
