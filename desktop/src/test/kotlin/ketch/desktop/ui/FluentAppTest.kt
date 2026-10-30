@file:OptIn(ExperimentalTestApi::class)

package ketch.desktop.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import ketch.desktop.core.FakeCore
import ketch.desktop.core.scenario
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.MemoryKeyValues
import ketch.desktop.settings.Prefs
import ketch.desktop.store.KetchStore
import ketch.desktop.ui.fluent.LocalWindowChrome
import ketch.desktop.ui.fluent.WindowChrome
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

/** The Windows window on the fake core: the same routes as the other looks, through its own screens. */
class FluentAppTest {
    private val config =
        Settings(
            root = "C:\\Users\\me\\.ketch",
            binDir = "C:\\Users\\me\\.ketch\\bin",
            storeDir = "C:\\Users\\me\\.ketch\\store",
            appsDir = "C:\\Users\\me\\AppData\\Local\\Programs",
            configFile = "C:\\Users\\me\\.ketch\\config.toml",
            logFile = "C:\\Users\\me\\.ketch\\ketch.log",
            registry = "pyrlyn/ketch-registry",
            target = "x86_64-pc-windows-msvc",
            jobs = 4,
            prerelease = false,
            allowEmulation = false,
            linkApps = true,
            requireChecksums = true,
            stripQuarantine = false,
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
        setContent { KetchTheme(Appearance.Light, look = Look.Fluent) { KetchApp(KetchStore(core), navigation = navigation, parts = parts) } }
        waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Doctor } }
    }

    private fun ComposeUiTest.waitFor(tag: String) = waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.waitForText(text: String) = waitUntil(timeoutMillis = TIMEOUT) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.waitForNo(tag: String) = waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    @Test
    fun everySectionOpensItsPage() =
        runComposeUiTest {
            show(core())
            onNodeWithTag("fluent").assertIsDisplayed()
            for (section in Section.entries) {
                onNodeWithTag("sidebar-${section.tag}").performClick()
                onNodeWithTag("page-${section.tag}").assertIsDisplayed()
            }
        }

    @Test
    fun theNavigationCountsUpdatesAndProblems() =
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
            onNodeWithText(HERO_TITLE).assertIsDisplayed()
            onNodeWithTag("discover-query").performTextInput("grep")
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodesWithText(HERO_TITLE).fetchSemanticsNodes().isEmpty() }
            waitFor("discover-ugrep")
        }

    @Test
    fun typingInTheTitleBarLeavesAnyOtherPageForDiscover() =
        runComposeUiTest {
            show(core(), Navigation(Section.Doctor))
            onNodeWithTag("discover-query").performTextInput("grep")
            waitFor("discover-ugrep")
            onNodeWithTag("page-discover").assertIsDisplayed()
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
            onNodeWithText("Update to 14.1.1 available").assertIsDisplayed()
            onNodeWithText("Pinned").assertIsDisplayed()
        }

    @Test
    fun theInstalledFilterNarrowsTheList() =
        runComposeUiTest {
            show(core(), Navigation(Section.Installed))
            waitFor("installed-ripgrep")
            onNodeWithTag("installed-filter").performTextInput("fd")
            waitForNo("installed-ripgrep")
            onNodeWithTag("installed-fd").assertIsDisplayed()
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
    fun theBreadcrumbLeadsBackToTheList() =
        runComposeUiTest {
            show(core(), Navigation(Section.Installed))
            waitFor("installed-ripgrep")
            onNodeWithTag("installed-ripgrep").performClick()
            onNodeWithTag("page-package").assertIsDisplayed()
            // The navigation view says the same word, above it in the tree.
            onAllNodes(hasText("Installed") and hasClickAction()).onLast().performClick()
            onNodeWithTag("page-installed").assertIsDisplayed()
        }

    @Test
    fun aFailedInstallShowsTheErrorUntilDismissed() =
        runComposeUiTest {
            show(core("install-not-found"))
            onNodeWithTag("discover-query").performTextInput("grep")
            waitFor("discover-ugrep")
            onAllNodesWithText("Get")[0].performClick()
            waitFor("error-bar")
            onNodeWithText("No package named nope.").assertIsDisplayed()
            onNodeWithText("Dismiss").performClick()
            waitForNo("error-bar")
        }

    @Test
    fun aHeldLockShowsTheBusyFlyoutWithRetry() =
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
            onNodeWithText("warning").assertIsDisplayed()
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
            onNodeWithTag("material-mica").performClick()
            waitForIdle()
            val chosen = parts.settings.state.value
            assertEquals(listOf("mint", "purple", Prefs.MICA), listOf(chosen.tint, chosen.accent, chosen.material))
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
            waitForNo("about")
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
    fun askingForSearchFocusesTheTitleBar() =
        runComposeUiTest {
            val navigation = Navigation(Section.Discover)
            show(core(), navigation)
            navigation.findSomething()
            waitForIdle()
            onNodeWithTag("discover-query").assertIsFocused()
            assertFalse(navigation.wantsSearch)
        }

    @Test
    fun theTitleBarMovesThroughTheHistory() =
        runComposeUiTest {
            show(core())
            onNodeWithContentDescription("Back").performClick()
            onNodeWithTag("sidebar-doctor").performClick()
            onNodeWithTag("page-doctor").assertIsDisplayed()
            onNodeWithContentDescription("Back").performClick()
            onNodeWithTag("page-discover").assertIsDisplayed()
        }

    @Test
    fun theWindowsButtonsComeFromTheChrome() =
        runComposeUiTest {
            val pressed = mutableListOf<String>()
            val chrome =
                WindowChrome(
                    isMaximized = { false },
                    minimize = { pressed += "minimize" },
                    toggleMaximize = { pressed += "maximize" },
                    close = { pressed += "close" },
                    drag = { Box(it) },
                )
            val core = core()
            setContent {
                CompositionLocalProvider(LocalWindowChrome provides chrome) {
                    KetchTheme(Appearance.Light, look = Look.Fluent) { KetchApp(KetchStore(core), parts = AppParts(AppSettings(MemoryKeyValues()))) }
                }
            }
            waitFor("fluent")
            onNodeWithContentDescription("Minimize").performClick()
            onNodeWithContentDescription("Maximize").performClick()
            onNodeWithContentDescription("Close").performClick()
            assertEquals(listOf("minimize", "maximize", "close"), pressed)
        }

    @Test
    fun theTitleBarLeavesThePressToTheDragAndDoubleClickMaximizes() =
        runComposeUiTest {
            val pressed = mutableListOf<String>()
            val chrome =
                WindowChrome(
                    isMaximized = { false },
                    minimize = {},
                    toggleMaximize = { pressed += "maximize" },
                    close = {},
                    // As WindowDraggableArea listens: it starts on a press nothing has consumed.
                    drag = { modifier ->
                        Box(
                            modifier.testTag("drag").pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    pressed += "drag"
                                }
                            },
                        )
                    },
                )
            val core = core()
            setContent {
                CompositionLocalProvider(LocalWindowChrome provides chrome) {
                    KetchTheme(Appearance.Light, look = Look.Fluent) { KetchApp(KetchStore(core), parts = AppParts(AppSettings(MemoryKeyValues()))) }
                }
            }
            waitFor("fluent")
            onNodeWithTag("drag").performMouseInput { click(Offset(right - FREE_OF_THE_BUTTONS, centerY)) }
            waitForIdle()
            assertEquals(listOf("drag"), pressed)
            mainClock.advanceTimeBy(DOUBLE_CLICK_GAP)
            onNodeWithTag("drag").performMouseInput { doubleClick(Offset(right - FREE_OF_THE_BUTTONS, centerY)) }
            waitForIdle()
            assertEquals(listOf("drag", "drag", "drag", "maximize"), pressed)
        }

    @Test
    fun aWindowWithItsOwnFrameDrawsNoButtons() =
        runComposeUiTest {
            show(core())
            assertTrue(onAllNodesWithContentDescription("Minimize").fetchSemanticsNodes().isEmpty())
        }

    private companion object {
        const val TIMEOUT = 5_000L
        const val FREE_OF_THE_BUTTONS = 200f
        const val DOUBLE_CLICK_GAP = 1_000L
    }
}
