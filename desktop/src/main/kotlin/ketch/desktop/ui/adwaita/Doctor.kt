package ketch.desktop.ui.adwaita

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.UNDER_THE_ROOT
import ketch.desktop.ui.copyToClipboard
import ketch.desktop.ui.doctorSummary
import ketch.desktop.ui.isFixed
import ketch.desktop.ui.openFolder
import ketch.desktop.ui.word
import ketch.wire.Check
import ketch.wire.CheckOutcome
import ketch.wire.Settings
import org.jetbrains.compose.resources.stringResource
import pyrlyn.adwaita.ActionRow
import pyrlyn.adwaita.Banner
import pyrlyn.adwaita.BannerKind
import pyrlyn.adwaita.BoxedList
import pyrlyn.adwaita.IconGradient
import pyrlyn.adwaita.IconSphere
import pyrlyn.adwaita.LocalAdwaita
import pyrlyn.adwaita.LocalAdwaitaType
import pyrlyn.adwaita.PageHead
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillSize
import pyrlyn.adwaita.PillStyle
import pyrlyn.adwaita.PreferencesGroup
import pyrlyn.adwaita.RowLine
import pyrlyn.adwaita.RowMono
import pyrlyn.adwaita.RowText
import pyrlyn.adwaita.RowTitle
import pyrlyn.adwaita.Status
import pyrlyn.adwaita.StatusPill
import pyrlyn.adwaita.Terminal
import pyrlyn.adwaita.TerminalLine
import pyrlyn.glass.Glyph
import pyrlyn.glass.GlyphIcon

@Composable
fun AdwaitaDoctor(
    state: StoreState,
    actions: Actions,
    config: Settings?,
) {
    val summary = doctorSummary(state)
    val report = state.checks.map { "${it.outcome.word.lowercase().padEnd(WORD_WIDTH)} ${it.name}: ${it.detail}" }
    AdwaitaPage("doctor") {
        PageHead(stringResource(Res.string.section_doctor)) { PillButton(stringResource(Res.string.action_run_again), glyph = Glyph.Refresh) { actions.run { runDoctor() } } }
        if (state.problemCount > 0) {
            Banner(
                summary,
                BannerKind.Warning,
                Modifier.testTag("doctor-summary"),
                leading = { GlyphIcon(Glyph.Warning, LocalAdwaita.current.warning, BANNER_GLYPH) },
                action = { if (state.checks.any { it.fixable }) FixButton("fix-all", actions) },
            )
        } else {
            Text(summary, color = LocalAdwaita.current.ink2, style = LocalAdwaitaType.current.caption, modifier = Modifier.testTag("doctor-summary"))
        }
        PreferencesGroup(stringResource(Res.string.doctor_checks)) {
            BoxedList {
                state.checks.forEachIndexed { index, check ->
                    if (index > 0) RowLine()
                    CheckRow(check, state, actions, config)
                }
            }
        }
        if (report.isNotEmpty()) {
            PreferencesGroup(stringResource(Res.string.doctor_output), suffix = { PillButton(stringResource(Res.string.action_copy), size = PillSize.Small, glyph = Glyph.Copy) { copyToClipboard(report.joinToString("\n")) } }) {
                SelectionContainer { Terminal(report.map(::TerminalLine)) }
            }
        }
    }
}

@Composable
private fun CheckRow(
    check: Check,
    state: StoreState,
    actions: Actions,
    config: Settings?,
) {
    val fixed = check.isFixed(state)
    val root = config?.root?.takeIf { check.name in UNDER_THE_ROOT && check.outcome != CheckOutcome.Ok }
    ActionRow(
        Modifier.testTag("check-${check.name}"),
        leading = { Sphere(check.outcome, fixed) },
        trailing = {
            if (check.fixable) FixButton("fix-${check.name}", actions)
            root?.let { PillButton(stringResource(Res.string.action_open_folder), Modifier.testTag("folder-${check.name}"), size = PillSize.Small) { openFolder(it) } }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RowTitle(check.name)
            StatusPill(if (fixed) stringResource(Res.string.doctor_fixed) else check.outcome.word, check.outcome.status(fixed))
        }
        RowText(check.detail)
        check.fix?.let { RowMono(stringResource(Res.string.doctor_fix_hint, it)) }
    }
}

@Composable
private fun Sphere(
    outcome: CheckOutcome,
    fixed: Boolean,
) {
    when {
        fixed || outcome == CheckOutcome.Ok -> IconSphere(Glyph.Check, IconGradient.Mint, small = true)
        outcome == CheckOutcome.Warn -> IconSphere(Glyph.Warning, IconGradient.Orange, small = true)
        else -> IconSphere(Glyph.Cross, IconGradient.Coral, small = true)
    }
}

private fun CheckOutcome.status(fixed: Boolean) =
    when {
        fixed || this == CheckOutcome.Ok -> Status.Ok
        this == CheckOutcome.Warn -> Status.Warning
        else -> Status.Danger
    }

/** A repair can take seconds: the button has to say the press landed. */
@Composable
private fun FixButton(
    tag: String,
    actions: Actions,
) {
    var fixing by remember { mutableStateOf(false) }
    PillButton(if (fixing) stringResource(Res.string.action_fixing) else stringResource(Res.string.action_fix), Modifier.testTag(tag), PillStyle.Accent, PillSize.Small, enabled = !fixing) {
        fixing = true
        actions.run {
            fixDoctor()
            fixing = false
        }
    }
}

private const val WORD_WIDTH = 4
private val BANNER_GLYPH = 20.dp
