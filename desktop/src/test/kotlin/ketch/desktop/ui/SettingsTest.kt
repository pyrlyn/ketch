@file:OptIn(ExperimentalTestApi::class)

package ketch.desktop.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ketch.desktop.core.FakeCore
import ketch.desktop.core.scenario
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.MemoryKeyValues
import ketch.desktop.store.KetchStore
import ketch.desktop.store.UpdateNotifier
import ketch.wire.Call
import ketch.wire.InstallOptions
import ketch.wire.Outcome
import ketch.wire.Scenario
import ketch.wire.Settings
import ketch.wire.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings section, About, and the routes the tray and the app menu take into the window. */
class SettingsTest {
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
            for (name in listOf("installed", "outdated", "doctor", "search") + more) script(scenario(name))
            script(Scenario("config", "the effective config", Call.Config, emptyList(), Outcome.Ok(Wire.json.encodeToJsonElement(Settings.serializer(), config))))
        }

    private class Refusing : UpdateNotifier {
        override suspend fun requestAuthorization() = false

        override fun post(
            title: String,
            body: String,
        ) = Unit
    }

    private fun ComposeUiTest.show(
        core: FakeCore,
        navigation: Navigation = Navigation(),
        parts: AppParts = AppParts(),
    ) {
        setContent { KetchTheme(Appearance.Light, look = Look.Material) { KetchApp(KetchStore(core), navigation = navigation, parts = parts) } }
        waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Doctor } }
    }

    private fun ComposeUiTest.waitFor(tag: String) = waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun theChoicesChangeThePreferences() =
        runComposeUiTest {
            val parts = AppParts(AppSettings(MemoryKeyValues()))
            show(core(), parts = parts)
            onNodeWithTag("sidebar-settings").performClick()
            onNodeWithTag("interval-360").performClick()
            onNodeWithTag("interval-360").assertIsSelected()
            assertEquals(360, parts.settings.state.value.intervalMinutes)
            onNodeWithTag("prereleases").performClick()
            waitForIdle()
            assertTrue(parts.settings.state.value.prereleases)
        }

    @Test
    fun anInstallTakesPrereleasesWhenTheyAreOn() =
        runComposeUiTest {
            val parts = AppParts(AppSettings(MemoryKeyValues()))
            val core = core("install-ok")
            show(core, parts = parts)
            parts.settings.update { it.copy(prereleases = true) }
            onNodeWithTag("sidebar-discover").performClick()
            waitFor("discover-ugrep")
            onAllNodesWithText("Install")[0].performClick()
            waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Install } }
            assertEquals(InstallOptions(prerelease = true), (core.calls.first { it is Call.Install } as Call.Install).options)
        }

    @Test
    fun aRefusedNoticeStaysOffAndSaysWhy() =
        runComposeUiTest {
            val parts = AppParts(AppSettings(MemoryKeyValues()), notifier = Refusing())
            show(core(), parts = parts)
            onNodeWithTag("sidebar-settings").performClick()
            onNodeWithTag("notices").performClick()
            waitFor("settings-message")
            assertFalse(parts.settings.state.value.notifies)
        }

    @Test
    fun aDevelopmentRunCannotOpenAtLogin() =
        runComposeUiTest {
            show(core())
            onNodeWithTag("sidebar-settings").performClick()
            onNodeWithTag("login").assertIsNotEnabled()
            onNodeWithText("Available in the installed app.").assertIsDisplayed()
        }

    @Test
    fun theRootIsKetchsOwn() =
        runComposeUiTest {
            show(core())
            onNodeWithTag("sidebar-settings").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodesWithText("/home/me/.ketch").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("settings-root").assertTextContains("/home/me/.ketch")
            onNodeWithText("Open config.toml").performClick()
            // The fake's config file does not exist, so nothing is opened and the page says why.
            waitFor("settings-message")
        }

    @Test
    fun aboutShowsBothVersions() =
        runComposeUiTest {
            show(core())
            onNodeWithTag("sidebar-settings").performClick()
            onNodeWithText("About Ketch").performClick()
            onNodeWithTag("about").assertIsDisplayed()
            onNodeWithText("Version development · ketch 0.0.0 · fake").assertIsDisplayed()
            onNodeWithText("Close").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag("about")).fetchSemanticsNodes().isEmpty() }
        }

    @Test
    fun theTraysUpdateAllAsksInTheWindow() =
        runComposeUiTest {
            val navigation = Navigation()
            val core = core()
            show(core, navigation)
            waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Outdated } }
            navigation.show(Section.Updates)
            navigation.confirmingUpdateAll = true
            onNodeWithTag("page-updates").assertIsDisplayed()
            onNodeWithTag("confirm").assertIsDisplayed()
            assertTrue(core.calls.none { it is Call.Upgrade })
        }

    @Test
    fun theAppMenusSettingsOpensTheSection() =
        runComposeUiTest {
            val navigation = Navigation()
            show(core(), navigation)
            navigation.show(Section.Settings)
            onNodeWithTag("page-settings").assertIsDisplayed()
        }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
