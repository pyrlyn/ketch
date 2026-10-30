@file:OptIn(ExperimentalTestApi::class)

package ketch.desktop.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import ketch.desktop.core.FakeCore
import ketch.desktop.core.scenario
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.MemoryKeyValues
import ketch.desktop.settings.Prefs
import ketch.desktop.store.KetchStore
import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.Outcome
import ketch.wire.Question
import ketch.wire.Scenario
import ketch.wire.Settings
import ketch.wire.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The macOS window on the fake core: the same routes as the other look, through its own screens. */
class GlassAppTest {
    private val config =
        Settings(
            root = "/home/me/.ketch",
            binDir = "/home/me/.ketch/bin",
            storeDir = "/home/me/.ketch/store",
            appsDir = "/home/me/Applications",
            configFile = "/home/me/.ketch/config.toml",
            logFile = "/home/me/.ketch/ketch.log",
            registry = "pyrlyn/ketch-registry",
            target = "aarch64-apple-darwin",
            jobs = 4,
            prerelease = false,
            allowEmulation = false,
            linkApps = true,
            requireChecksums = true,
            stripQuarantine = true,
            autoUpdate = false,
            githubToken = false,
            logLevel = "info",
        )

    private fun core(vararg more: String) =
        FakeCore().apply {
            for (name in listOf("installed", "outdated", "doctor", "search", "changelog-range") + more) script(scenario(name))
            script(Scenario("config", "the effective config", Call.Config, emptyList(), Outcome.Ok(Wire.json.encodeToJsonElement(Settings.serializer(), config))))
        }

    private fun ComposeUiTest.show(
        core: FakeCore,
        navigation: Navigation = Navigation(Section.Discover),
        parts: AppParts = AppParts(AppSettings(MemoryKeyValues())),
    ) {
        setContent { KetchTheme(Appearance.Light, look = Look.Glass) { KetchApp(KetchStore(core), navigation = navigation, parts = parts) } }
        waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Doctor } }
    }

    private fun ComposeUiTest.waitFor(tag: String) = waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.waitForText(text: String) = waitUntil(timeoutMillis = TIMEOUT) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun everySectionOpensItsPage() =
        runComposeUiTest {
            show(core())
            onNodeWithTag("glass").assertIsDisplayed()
            for (section in Section.entries) {
                onNodeWithTag("sidebar-${section.tag}").performClick()
                onNodeWithTag("page-${section.tag}").assertIsDisplayed()
            }
        }

    @Test
    fun theSidebarCountsUpdatesAndProblems() =
        runComposeUiTest {
            show(core())

            fun said(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
            waitUntil(timeoutMillis = TIMEOUT) { said("sidebar-doctor") != null }
            assertEquals(listOf("Updates, 1 update"), said("sidebar-updates"))
            assertEquals(listOf("Doctor, 2 problems"), said("sidebar-doctor"))
        }

    @Test
    fun discoverOpensWithTheHeroAndHidesItForASearch() =
        runComposeUiTest {
            show(core())
            onNodeWithTag("page-discover").assertIsDisplayed()
            onNodeWithText("Catch releases straight from GitHub.").assertIsDisplayed()
            onNodeWithTag("discover-query").performTextInput("grep")
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodesWithText("Catch releases straight from GitHub.").fetchSemanticsNodes().isEmpty() }
            waitFor("discover-ugrep")
        }

    @Test
    fun theSourcesNarrowDiscover() =
        runComposeUiTest {
            show(core())
            onNodeWithTag("discover-query").performTextInput("grep")
            waitFor("discover-github:phiresky/ripgrep-all")
            onNodeWithTag("sidebar-registry").performClick()
            waitForIdle()
            assertTrue(onAllNodes(hasTestTag("discover-github:phiresky/ripgrep-all")).fetchSemanticsNodes().isEmpty())
            onNodeWithTag("sidebar-releases").performClick()
            waitFor("discover-github:phiresky/ripgrep-all")
            assertTrue(onAllNodes(hasTestTag("discover-ugrep")).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun getAsksTheCoreToInstall() =
        runComposeUiTest {
            val core = core("install-binary-choice")
            show(core)
            onNodeWithTag("discover-query").performTextInput("grep")
            waitFor("discover-ugrep")
            onAllNodesWithText("Get")[0].performClick()
            waitFor("question-choose-binary")
            onNodeWithText("uvx").performClick()
            onNodeWithText("Link").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { core.answers.isNotEmpty() }
            val (question, answer) = core.answers.single()
            assertTrue(question is Question.ChooseBinary)
            assertEquals(Answer.Index(1), answer)
        }

    @Test
    fun theInstalledListShowsEachPackageAndItsUpdate() =
        runComposeUiTest {
            show(core(), Navigation(Section.Installed))
            waitFor("installed-ripgrep")
            onNodeWithTag("installed-fd").assertIsDisplayed()
            onNodeWithText("Update to 14.1.1").assertIsDisplayed()
            onNodeWithText("Pinned").assertIsDisplayed()
        }

    @Test
    fun updateAllAsksFirstThenUpgradesWhatIsNotHeld() =
        runComposeUiTest {
            val core = core("upgrade-nothing-to-do")
            show(core, Navigation(Section.Updates))
            waitFor("update-ripgrep")
            onNodeWithTag("held-fd").assertIsDisplayed()
            onNodeWithTag("update-all").performClick()
            onNodeWithTag("confirm").assertIsDisplayed()
            assertTrue(core.calls.none { it is Call.Upgrade })
            onNodeWithTag("confirm-yes").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Upgrade } }
            assertEquals(Call.Upgrade(emptyList()), core.calls.first { it is Call.Upgrade })
        }

    @Test
    fun anUpdateCardQuotesTheChangelogAndUpdatesOnePackage() =
        runComposeUiTest {
            val core = core("upgrade-nothing-to-do")
            show(core, Navigation(Section.Updates))
            waitForText("Fix a regression")
            onNodeWithText("Update").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Upgrade } }
            assertEquals(Call.Upgrade(listOf("ripgrep")), core.calls.first { it is Call.Upgrade })
        }

    @Test
    fun aPackagePageShowsItsTabsAndUninstallsAfterAsking() =
        runComposeUiTest {
            val core = core("uninstall-ok")
            show(core, Navigation(Section.Installed))
            waitFor("installed-ripgrep")
            onNodeWithTag("installed-ripgrep").performClick()
            onNodeWithTag("page-package").assertIsDisplayed()
            waitForText("Fix a regression")
            onNodeWithText("Files").performClick()
            onNodeWithTag("package-files").assertIsDisplayed()
            onNodeWithText("Overview").performClick()
            onNodeWithTag("package-overview").assertIsDisplayed()
            onNodeWithText("Uninstall").performClick()
            onNodeWithText("Uninstall ripgrep?").assertIsDisplayed()
            onNodeWithTag("confirm-yes").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Uninstall } }
            assertEquals(Call.Uninstall(listOf("ripgrep")), core.calls.first { it is Call.Uninstall })
            onNodeWithTag("page-installed").assertIsDisplayed()
        }

    @Test
    fun aFailedInstallShowsTheErrorBarUntilDismissed() =
        runComposeUiTest {
            show(core("install-not-found"))
            onNodeWithTag("discover-query").performTextInput("grep")
            waitFor("discover-ugrep")
            onAllNodesWithText("Get")[0].performClick()
            waitFor("error-bar")
            onNodeWithText("No package named nope.").assertIsDisplayed()
            onNodeWithText("Dismiss").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag("error-bar")).fetchSemanticsNodes().isEmpty() }
        }

    @Test
    fun aHeldLockShowsTheBusyBarWithRetry() =
        runComposeUiTest {
            show(core("install-busy"))
            onNodeWithTag("discover-query").performTextInput("grep")
            waitFor("discover-ugrep")
            onAllNodesWithText("Get")[0].performClick()
            waitFor("busy-bar")
            onNodeWithText("Retry").assertIsDisplayed()
        }

    @Test
    fun doctorCountsItsChecksAndNamesTheFix() =
        runComposeUiTest {
            show(core(), Navigation(Section.Doctor))
            waitForText("2 problems found")
            onNodeWithText("Fix: ketch doctor --fix").assertIsDisplayed()
            onNodeWithText("Warnings").assertIsDisplayed()
        }

    @Test
    fun theGeneralChoicesChangeThePreferences() =
        runComposeUiTest {
            val parts = AppParts(AppSettings(MemoryKeyValues()))
            show(core(), Navigation(Section.Settings), parts)
            onNodeWithTag("interval").performClick()
            onNodeWithTag("interval-360").performClick()
            waitForIdle()
            assertEquals(360, parts.settings.state.value.intervalMinutes)
            onNodeWithTag("prereleases").performClick()
            waitForIdle()
            assertTrue(parts.settings.state.value.prereleases)
            onNodeWithTag("settings-root").assertIsDisplayed()
        }

    @Test
    fun theAppearanceChoicesApplyAndReset() =
        runComposeUiTest {
            val parts = AppParts(AppSettings(MemoryKeyValues()))
            show(core(), Navigation(Section.Settings), parts)
            onNodeWithTag("settings-appearance").performClick()
            onNodeWithTag("swatch-mint").performClick()
            onNodeWithTag("swatch-mint").assertIsSelected()
            onNodeWithTag("swatch-purple").performClick()
            onNodeWithText("Clear").performClick()
            waitForIdle()
            val chosen = parts.settings.state.value
            assertEquals(listOf("mint", "purple", Prefs.CLEAR_GLASS), listOf(chosen.tint, chosen.accent, chosen.glass))
            onNodeWithTag("appearance-reset").performClick()
            waitForIdle()
            assertEquals(Prefs().standardLook(), parts.settings.state.value)
        }

    @Test
    fun aboutOpensFromTheNavigation() =
        runComposeUiTest {
            val navigation = Navigation(Section.Discover)
            show(core(), navigation)
            navigation.showingAbout = true
            waitFor("about")
            onNodeWithText("Close").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag("about")).fetchSemanticsNodes().isEmpty() }
        }

    @Test
    fun everyControlHasAName() =
        runComposeUiTest {
            show(core())
            waitFor("discover-query")
            for (section in Section.entries) {
                onNodeWithTag("sidebar-${section.tag}").performClick()
                waitForIdle()
                val unnamed =
                    onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
                        val c = node.config
                        c.getOrNull(SemanticsProperties.Text).isNullOrEmpty() &&
                            c.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() &&
                            c.getOrNull(SemanticsProperties.EditableText) == null
                    }
                assertTrue(unnamed.isEmpty(), "${section.title}: ${unnamed.map { it.config }}")
            }
        }

    @Test
    fun askingForSearchFocusesTheFieldOfTheScreen() =
        runComposeUiTest {
            val navigation = Navigation(Section.Discover)
            show(core(), navigation)
            onNodeWithTag("sidebar-activity").performClick()
            waitFor("activity-filter")
            navigation.findSomething()
            waitForIdle()
            onNodeWithTag("activity-filter").assertIsFocused()
            assertFalse(navigation.wantsSearch)
        }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
