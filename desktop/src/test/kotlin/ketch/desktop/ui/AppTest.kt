@file:OptIn(ExperimentalTestApi::class)

package ketch.desktop.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ketch.desktop.core.FakeCore
import ketch.desktop.core.scenario
import ketch.desktop.store.KetchStore
import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.Question
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The window on the fake core: each screen opens, and each button reaches the core with what it says. */
class AppTest {
    private fun core(vararg more: String) =
        FakeCore().apply {
            for (name in listOf("installed", "outdated", "doctor", "search", "changelog-range") + more) script(scenario(name))
        }

    private fun ComposeUiTest.show(core: FakeCore) {
        setContent { KetchTheme(Appearance.Light) { KetchApp(KetchStore(core)) } }
        waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Doctor } }
    }

    private fun ComposeUiTest.waitFor(tag: String) = waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun everySectionOpensItsPage() =
        runComposeUiTest {
            show(core())
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
            // fd is held by a ketch.lock, so only ripgrep counts; a warning is a problem as much as a failure.
            assertEquals(listOf("Updates, 1 update"), said("sidebar-updates"))
            assertEquals(listOf("Doctor, 2 problems"), said("sidebar-doctor"))
        }

    @Test
    fun theInstalledListShowsEachPackageAndItsUpdate() =
        runComposeUiTest {
            show(core())
            waitFor("installed-ripgrep")
            onNodeWithTag("installed-fd").assertIsDisplayed()
            onNodeWithText("Update to 14.1.1").assertIsDisplayed()
            onNodeWithText("Held").assertIsDisplayed()
        }

    @Test
    fun updateAllAsksFirstThenUpgradesWhatIsNotHeld() =
        runComposeUiTest {
            val core = core("upgrade-nothing-to-do")
            show(core)
            onNodeWithTag("sidebar-updates").performClick()
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
    fun aPackagePageShowsItsChangelogAndUninstallsAfterAsking() =
        runComposeUiTest {
            val core = core("uninstall-ok")
            show(core)
            waitFor("installed-ripgrep")
            onNodeWithTag("installed-ripgrep").performClick()
            onNodeWithTag("page-package").assertIsDisplayed()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodesWithText("Fix a regression", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Uninstall").performClick()
            onNodeWithText("Uninstall ripgrep?").assertIsDisplayed()
            onNodeWithTag("confirm-yes").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { core.calls.any { it is Call.Uninstall } }
            assertEquals(Call.Uninstall(listOf("ripgrep")), core.calls.first { it is Call.Uninstall })
            onNodeWithTag("page-installed").assertIsDisplayed()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag("installed-ripgrep")).fetchSemanticsNodes().isEmpty() }
        }

    @Test
    fun cancellingTheUninstallQuestionLeavesThePackage() =
        runComposeUiTest {
            val core = core("uninstall-ok")
            show(core)
            waitFor("installed-ripgrep")
            onAllNodesWithText("Uninstall")[0].performClick()
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertTrue(core.calls.none { it is Call.Uninstall })
            onNodeWithTag("installed-ripgrep").assertIsDisplayed()
        }

    @Test
    fun theBinaryChoiceReachesTheCore() =
        runComposeUiTest {
            val core = core("install-binary-choice")
            show(core)
            onNodeWithTag("sidebar-discover").performClick()
            waitFor("discover-ugrep")
            onAllNodesWithText("Install")[0].performClick()
            waitFor("question-choose-binary")
            onNodeWithText("uvx").performClick()
            onNodeWithText("Link").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { core.answers.isNotEmpty() }
            val (question, answer) = core.answers.single()
            assertTrue(question is Question.ChooseBinary)
            assertEquals(Answer.Index(1), answer)
        }

    @Test
    fun aFailedInstallShowsTheErrorBarUntilDismissed() =
        runComposeUiTest {
            show(core("install-not-found"))
            onNodeWithTag("sidebar-discover").performClick()
            waitFor("discover-ugrep")
            onAllNodesWithText("Install")[0].performClick()
            waitFor("error-bar")
            onNodeWithText("No package named nope.").assertIsDisplayed()
            onNodeWithText("Dismiss").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasTestTag("error-bar")).fetchSemanticsNodes().isEmpty() }
        }

    @Test
    fun aHeldLockShowsTheBusyBarWithRetry() =
        runComposeUiTest {
            show(core("install-busy"))
            onNodeWithTag("sidebar-discover").performClick()
            waitFor("discover-ugrep")
            onAllNodesWithText("Install")[0].performClick()
            waitFor("busy-bar")
            onNodeWithText("Retry").assertIsDisplayed()
        }

    @Test
    fun doctorSummarisesItsChecks() =
        runComposeUiTest {
            show(core())
            onNodeWithTag("sidebar-doctor").performClick()
            onNodeWithTag("doctor-summary").assertIsDisplayed()
            onNodeWithText("2 problems found").assertIsDisplayed()
            onNodeWithText("ketch doctor --fix").assertIsDisplayed()
        }

    @Test
    fun everyControlHasAName() =
        runComposeUiTest {
            val core = core()
            show(core)
            waitFor("installed-ripgrep")
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

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
