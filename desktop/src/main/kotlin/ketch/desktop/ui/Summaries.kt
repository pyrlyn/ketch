// What both looks say of the same facts, so the macOS and Windows windows never disagree.
package ketch.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import ketch.desktop.store.StoreState
import ketch.desktop.ui.screens.PackageFacts
import ketch.wire.ChangelogSource
import ketch.wire.CheckOutcome
import ketch.wire.Package
import ketch.wire.Upgrade
import pyrlyn.glass.Glyph

/** The sections' order in both looks' sidebars; Settings stands apart at the foot. */
val LIBRARY = listOf(Section.Discover, Section.Installed, Section.Updates, Section.Activity, Section.Doctor)

const val HERO_TITLE = "Catch releases straight from GitHub."
const val HERO_TEXT = "No taps, no formulae, no build step. ketch takes the asset a project already ships, checks its published checksum and links it onto your PATH."
const val VERIFIED_NOTE = "Every update is verified the same way as an install, and the previous version stays on disk for ketch rollback."

val Section.glyph
    get() =
        when (this) {
            Section.Discover -> Glyph.Compass
            Section.Installed -> Glyph.Box
            Section.Updates -> Glyph.Refresh
            Section.Activity -> Glyph.Pulse
            Section.Doctor -> Glyph.Shield
            Section.Settings -> Glyph.Gear
        }

/** The search field's focus, taken when the person asks for search with the keyboard. */
@Composable
fun searchFocus(navigation: Navigation): FocusRequester {
    val focus = remember { FocusRequester() }
    LaunchedEffect(navigation.wantsSearch) {
        if (navigation.wantsSearch) {
            focus.requestFocus()
            navigation.wantsSearch = false
        }
    }
    return focus
}

/** `~/.ketch/bin/uv, uvx`: the first link in full, the others by name, as they share its directory. */
fun linkedTo(
    binaries: List<String>,
    home: String = System.getProperty("user.home"),
): String? = binaries.firstOrNull()?.let { first -> (listOf(tilde(first, home)) + binaries.drop(1).map(::fileName)).joinToString(", ") }

/** Where an installed package lives, for its row: what holds it there, or else its links. */
fun whereTo(
    pkg: Package,
    state: StoreState,
) = state.held.firstOrNull { it.name == pkg.name }.let { held ->
    if (held != null) held.heldBy?.let { "held by ${tilde(it)}" } else linkedTo(pkg.binaries)
}

/** What [upgrade] brings, for its card, once the core has said. */
@Composable
fun changelogNote(
    upgrade: Upgrade,
    actions: Actions,
): String? {
    var note by remember(upgrade.name, upgrade.latest) { mutableStateOf<String?>(null) }
    // Quiet: a card without its line is still a card, and a changelog the core lacks is no error to announce.
    LaunchedEffect(upgrade.name, upgrade.latest) {
        note =
            actions.store
                .changelog(upgrade.name, upgrade.installed, upgrade.latest, quiet = true)
                ?.firstOrNull()
                ?.let { firstLine(it.body) }
    }
    return note
}

/** The line of a changelog that says what changed, as plain text: its first list item, or else its first line of prose. */
fun firstLine(body: String): String? {
    val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val item = lines.firstOrNull { it.startsWith("- ") || it.startsWith("* ") }
    return (item?.drop(2) ?: lines.firstOrNull())?.replace("`", "")?.trim()
}

fun doctorSummary(state: StoreState) =
    when {
        state.checks.isEmpty() -> "No checks have run yet"
        state.problemCount == 0 -> "No problems found"
        else -> plural(state.problemCount, "problem") + " found"
    }

val CheckOutcome.word
    get() =
        when (this) {
            CheckOutcome.Ok -> "OK"
            CheckOutcome.Warn -> "Warning"
            CheckOutcome.Fail -> "Failed"
        }

/** What a package's trust level says, and whether it says the download was checked. */
fun trustSeal(trust: String) =
    when (trust) {
        "checksum" -> "SHA-256 verified" to true
        "signed" -> "Signed" to true
        else -> "First use" to false
    }

/** Where a changelog entry came from. */
fun changelogOrigin(source: ChangelogSource) = if (source is ChangelogSource.File) fileName(source.path) else "release notes"

/** The names and values of a package's Details panel. */
fun packageDetails(facts: PackageFacts): List<Pair<String, String>> {
    val installed = facts.installed
    return listOfNotNull(
        installed?.let { "Installed" to it.version },
        facts.latest?.let { "Latest" to it },
        installed?.let { "Source" to it.source },
        facts.repo?.let { "Repository" to it },
        installed?.let { linkedTo(it.binaries) }?.let { "Linked to" to it },
        installed?.retained?.takeIf { it.isNotEmpty() }?.let { "Kept" to it.joinToString(", ") },
    )
}
