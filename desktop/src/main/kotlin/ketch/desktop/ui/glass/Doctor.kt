package ketch.desktop.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.doctorSummary
import ketch.desktop.ui.word
import ketch.wire.Check
import ketch.wire.CheckOutcome
import pyrlyn.glass.AutoGrid
import pyrlyn.glass.Badge
import pyrlyn.glass.BadgeKind
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.GlassButton
import pyrlyn.glass.Glyph
import pyrlyn.glass.GlyphIcon
import pyrlyn.glass.ListRow
import pyrlyn.glass.LocalGlass
import pyrlyn.glass.StatCard
import pyrlyn.glass.TextSize
import pyrlyn.glass.body
import pyrlyn.glass.mono

@Composable
fun GlassDoctor(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val glass = LocalGlass.current
    GlassPage(
        "Doctor",
        "doctor",
        navigation,
        toolbar = { GlassButton("Run again", ButtonKind.Primary) { actions.run { runDoctor() } } },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PageContent, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(doctorSummary(state), color = glass.ink2, style = body(TextSize.BODY, FontWeight.Medium), modifier = Modifier.testTag("doctor-summary")) }
            item { Stats(state.checks) }
            items(state.checks, key = { it.name }) { CheckRow(it) }
        }
    }
}

private class Stat(
    val outcome: CheckOutcome,
    val label: String,
    val top: Color,
    val bottom: Color,
    val glyph: Glyph,
)

private val STATS =
    listOf(
        Stat(CheckOutcome.Ok, "Passed", Color(0xFF6FE39E), Color(0xFF1F9D57), Glyph.Check),
        Stat(CheckOutcome.Warn, "Warnings", Color(0xFFF6CF6A), Color(0xFFC98F00), Glyph.Warning),
        Stat(CheckOutcome.Fail, "Failed", Color(0xFFFF8E9A), Color(0xFFC62A44), Glyph.Cross),
    )

@Composable
private fun Stats(checks: List<Check>) {
    AutoGrid(STATS.size, STAT_WIDTH, 16.dp) { index ->
        val stat = STATS[index]
        StatCard("${checks.count { it.outcome == stat.outcome }}", stat.label, stat.top, stat.bottom, stat.glyph)
    }
}

@Composable
private fun CheckRow(check: Check) {
    val glass = LocalGlass.current
    val verdict =
        when (check.outcome) {
            CheckOutcome.Ok -> Verdict(BadgeKind.Success, Glyph.Check, glass.success)
            CheckOutcome.Warn -> Verdict(BadgeKind.Warning, Glyph.Warning, glass.warning)
            CheckOutcome.Fail -> Verdict(BadgeKind.Danger, Glyph.Cross, glass.danger)
        }
    ListRow(
        check.name,
        check.detail,
        Modifier.testTag("check-${check.name}"),
        icon = { GlyphIcon(verdict.glyph, verdict.ink, 22.dp) },
        detail = {
            check.fix?.let { fix -> SelectionContainer { Text("Fix: $fix", color = glass.ink2, style = mono(FIX)) } }
        },
    ) { Badge(check.outcome.word, verdict.kind, Modifier.widthIn(min = BADGE_WIDTH).align(Alignment.CenterVertically)) }
}

private class Verdict(
    val kind: BadgeKind,
    val glyph: Glyph,
    val ink: Color,
)

private const val FIX = 12
private val STAT_WIDTH = 200.dp
private val BADGE_WIDTH = 64.dp
