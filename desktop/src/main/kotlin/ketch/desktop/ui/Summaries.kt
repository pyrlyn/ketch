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
import ketch.desktop.strings.*
import ketch.desktop.ui.screens.PackageFacts
import ketch.wire.ChangelogSource
import ketch.wire.Check
import ketch.wire.CheckOutcome
import ketch.wire.Package
import ketch.wire.RegistryPackage
import ketch.wire.Upgrade
import pyrlyn.glass.Glyph

/** The sections' order in both looks' sidebars; Settings stands apart at the foot. */
val LIBRARY = listOf(Section.Discover, Section.Installed, Section.Updates, Section.Activity, Section.Doctor)

val HERO_TITLE get() = tr(Res.string.discover_hero_title)
val HERO_TEXT get() = tr(Res.string.discover_hero_text)
val VERIFIED_NOTE get() = tr(Res.string.updates_verified_note)

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
    if (held != null) held.heldBy?.let { tr(Res.string.package_held_by, tilde(it)) } else linkedTo(pkg.binaries)
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

/** What install would do about a checksum, in words: [policy] is the core's name for it. */
fun checksumWords(policy: String) =
    when (policy) {
        "published-digest" -> tr(Res.string.package_checksum_published)
        "require" -> tr(Res.string.package_checksum_required)
        "trust-on-first-use" -> tr(Res.string.package_checksum_first_use)
        else -> tr(Res.string.package_checksum_none)
    }

fun firstLine(body: String): String? {
    val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val item = lines.firstOrNull { it.startsWith("- ") || it.startsWith("* ") }
    return (item?.drop(2) ?: lines.firstOrNull())?.replace("`", "")?.trim()
}

fun doctorSummary(state: StoreState) =
    when {
        state.checks.isEmpty() -> tr(Res.string.doctor_no_checks)
        state.problemCount == 0 -> tr(Res.string.doctor_no_problems)
        else -> plural(state.problemCount, Res.plurals.problems_found)
    }

val CheckOutcome.word
    get() =
        when (this) {
            CheckOutcome.Ok -> tr(Res.string.doctor_ok)
            CheckOutcome.Warn -> tr(Res.string.doctor_warning)
            CheckOutcome.Fail -> tr(Res.string.doctor_failed)
        }

/** What a package's trust level says, and whether it says the download was checked. */
fun trustSeal(trust: String) =
    when (trust) {
        "checksum" -> tr(Res.string.discover_sha256_verified) to true
        "signed" -> tr(Res.string.package_seal_signed) to true
        else -> tr(Res.string.package_seal_first_use) to false
    }

/** Where a changelog entry came from. */
fun changelogOrigin(source: ChangelogSource) = if (source is ChangelogSource.File) fileName(source.path) else tr(Res.string.package_changelog_origin)

/** The names and values of a package's Details panel. */
fun packageDetails(facts: PackageFacts): List<Pair<String, String>> {
    val installed = facts.installed
    return listOfNotNull(
        installed?.let { tr(Res.string.package_installed) to it.version },
        facts.latest?.let { tr(Res.string.package_latest) to it },
        installed?.let { tr(Res.string.package_source) to it.source },
        facts.repo?.let { tr(Res.string.package_repository) to it },
        installed?.let { linkedTo(it.binaries) }?.let { tr(Res.string.package_linked_to) to it },
        installed?.retained?.takeIf { it.isNotEmpty() }?.let { tr(Res.string.package_kept) to it.joinToString(", ") },
    )
}

/** Presets are stored by their English names; a swatch says the name in the person's language. */
private val COLOR_NAMES =
    mapOf(
        "sky" to Res.string.color_sky,
        "mint" to Res.string.color_mint,
        "sand" to Res.string.color_sand,
        "rose" to Res.string.color_rose,
        "lilac" to Res.string.color_lilac,
        "smoke" to Res.string.color_smoke,
        "navy" to Res.string.color_navy,
        "blue" to Res.string.color_blue,
        "purple" to Res.string.color_purple,
        "pink" to Res.string.color_pink,
        "red" to Res.string.color_red,
        "orange" to Res.string.color_orange,
        "yellow" to Res.string.color_yellow,
        "green" to Res.string.color_green,
        "graphite" to Res.string.color_graphite,
    )

fun colorName(stored: String) = COLOR_NAMES[stored]?.let { tr(it) } ?: stored

/** Which packages a filter keeps. Few manifests name their kind, and one that does not is taken for a tool. */
enum class Kind(
    val title: String,
    val admits: (RegistryPackage) -> Boolean,
) {
    All(tr(Res.string.discover_filter_all), { true }),
    Tools(tr(Res.string.discover_filter_tools), { !it.isApp }),
    Apps(tr(Res.string.discover_filter_apps), { it.isApp }),
}

fun emptyWords(
    query: String,
    source: Source?,
    kind: Kind,
) = when {
    query.isNotBlank() -> tr(Res.string.discover_no_match, query.trim())
    kind == Kind.Apps -> tr(Res.string.discover_registry_no_apps)
    source == Source.Releases -> tr(Res.string.discover_type_repo)
    else -> tr(Res.string.discover_registry_empty)
}

/** Checks about files in ketch's own folder, which is where to look when they fail. */
val UNDER_THE_ROOT = setOf("state", "lock", "log", "packages")

/** A repair of PATH reaches the next shell, not this process, so the check that follows it is only a warning. */
fun Check.isFixed(state: StoreState) = name in state.fixed && outcome != CheckOutcome.Fail
