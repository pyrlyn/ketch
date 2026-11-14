@file:OptIn(ExperimentalTestApi::class, kotlin.io.path.ExperimentalPathApi::class)

package ketch.desktop.e2e

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import ketch.desktop.core.ServeCore
import ketch.desktop.store.KetchStore
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.Appearance
import ketch.desktop.ui.KetchApp
import ketch.desktop.ui.KetchTheme
import ketch.desktop.ui.Look
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Section
import ketch.desktop.ui.lookFor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole app against the real `ketch serve` and a stand-in GitHub: a package is found, installed, updated and removed
 * through the window, on disk as well as on screen, and every screen on the way is compared with this system's reference.
 */
class EndToEndTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    // A fixed place: the Settings screen shows these paths, and a reference must not depend on the checkout.
    private val root = File(if (windows) "C:\\ketch-e2e" else "/tmp/ketch-e2e").apply { deleteRecursively() }
    private val github = StandInGitHub(File("e2e/data"), root.resolve("github"))
    private val native = lookFor(null, System.getProperty("os.name"), false)

    @AfterTest
    fun stop() {
        github.close()
        root.deleteRecursively()
    }

    private fun environment() =
        root.resolve("home").also { it.mkdirs() }.let { home ->
            mapOf(
                "HOME" to home.path,
                "USERPROFILE" to home.path,
                "KETCH_HOME" to home.resolve(".ketch").path,
                "KETCH_ROOT" to root.resolve("ketch").path,
                "SHELL" to "/bin/sh",
                "KETCH_GITHUB_API" to github.api,
            )
        }

    /** The app never fetches the registry itself: a person has run `ketch update` before it lists anything. */
    private fun fetchTheRegistry(exe: Path) {
        val process =
            ProcessBuilder(exe.toString(), "update")
                .apply { environment().putAll(this@EndToEndTest.environment()) }
                .inheritIO()
                .start()
        check(process.waitFor() == 0) { "ketch update failed" }
    }

    private fun tool(name: String) = root.resolve("ketch/bin/$name${if (windows) ".cmd" else ""}")

    private fun said(name: String) =
        ProcessBuilder(tool(name).path)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()

    private inner class Run(
        val test: DesktopComposeUiTest,
        val store: KetchStore,
        val navigation: Navigation,
        val prefix: String,
    ) {
        val changed = mutableListOf<String>()

        fun show(section: Section) = test.runOnIdle { navigation.show(section) }

        fun waitFor(tag: String) = test.waitUntil(timeoutMillis = TIMEOUT) { test.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

        fun settled() {
            test.waitUntil(timeoutMillis = TIMEOUT) { !store.state.value.isRunning }
            test.waitForIdle()
        }

        // Every changed screen is worth seeing at once, and the walk needs nothing from a picture.
        fun screen(name: String) {
            runCatching { test.assertScreen("$prefix-$name") }.onFailure { changed += "$name: ${it.message}" }
        }

        fun search(text: String) {
            // Adwaita keeps the field behind a toggle, which stays open between searches.
            if (test.onAllNodes(hasTestTag("discover-query")).fetchSemanticsNodes().isEmpty()) {
                test.onNodeWithTag("search").performClick()
                waitFor("discover-query")
            }
            test.onNodeWithTag("discover-query").performTextClearance()
            test.onNodeWithTag("discover-query").performTextInput(text)
        }

        fun install(name: String) {
            search(name)
            waitFor("discover-$name")
            test.onNode(hasAnyAncestor(hasTestTag("discover-$name")) and hasClickAction() and (hasText("Get") or hasText("Install"))).performClick()
            test.waitUntil(timeoutMillis = TIMEOUT) {
                store.state.value.installed
                    .any { it.name == name } || store.state.value.error != null
            }
            assertEquals(null, store.state.value.error)
            settled()
            // A search shows only the latest version the core has cached, and the listing that caches it races the one
            // the page starts itself; asking in this order leaves one answer to draw.
            runBlocking {
                store.refresh()
                store.search(name)
            }
            test.waitForIdle()
        }

        fun discoverAndInstall() {
            waitFor("page-discover")
            test.waitUntil(timeoutMillis = TIMEOUT) {
                store.state.value.search.known
                    .isNotEmpty()
            }
            settled()
            screen("discover")
            install("ripgrep")
            assertEquals("rg 14.1.1", said("rg"))
            screen("search")
            install("fd")
            assertEquals("fd 10.1.0", said("fd"))
        }

        fun lookAtWhatIsInstalled() {
            show(Section.Installed)
            waitFor("installed-ripgrep")
            screen("installed")
        }

        fun update() {
            github.publish("sharkdp/fd", "v10.2.0")
            // A person waits out the core's memory of the latest versions; a test forgets it.
            root.resolve("ketch/cache/latest.json").delete()
            runBlocking { store.refresh() }
            waitFor("installed-fd")
            test.onNodeWithTag("installed-fd").performClick()
            waitFor("page-package")
            test.waitUntil(timeoutMillis = TIMEOUT) { test.onAllNodes(hasText("Faster directory walking", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            screen("package")
            show(Section.Updates)
            waitFor("update-fd")
            screen("updates")
            test.onNodeWithTag("update-all").performClick()
            waitFor("confirm")
            screen("confirm-update")
            test.onNodeWithTag("confirm-yes").performClick()
            test.waitUntil(timeoutMillis = TIMEOUT) {
                store.state.value.outdated
                    .isEmpty()
            }
            settled()
            assertEquals("fd 10.2.0", said("fd"))
        }

        fun uninstall() {
            show(Section.Installed)
            waitFor("installed-ripgrep")
            test.onNodeWithTag("installed-ripgrep").performClick()
            waitFor("page-package")
            test.onNodeWithText("Uninstall", substring = true).performClick()
            waitFor("confirm")
            test.onNodeWithTag("confirm-yes").performClick()
            test.waitUntil(timeoutMillis = TIMEOUT) {
                store.state.value.installed
                    .none { it.name == "ripgrep" }
            }
            settled()
            assertFalse(tool("rg").exists())
            assertTrue(tool("fd").exists())
        }

        fun visitTheRest() {
            for (section in listOf(Section.Activity, Section.Doctor, Section.Settings)) {
                show(section)
                waitFor("page-${section.tag}")
                settled()
                screen(section.tag)
            }
        }
    }

    private fun walk(
        look: Look,
        appearance: Appearance,
    ) = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        github.hold("sharkdp/fd", "v10.2.0")
        val exe = Path.of(checkNotNull(System.getenv("KETCH_EXE")) { "run this through :desktop:e2e" })
        fetchTheRegistry(exe)
        val core = runBlocking { ServeCore.start(exe, root.resolve("ketch").toPath(), environment()) }
        core.use {
            val store = KetchStore(core) { CLOCK }
            val navigation = Navigation(Section.Discover)
            setContent { KetchTheme(appearance, look = look) { KetchApp(store, navigation = navigation, parts = AppParts()) } }
            val run = Run(this, store, navigation, "${look.name}-${appearance.name}".lowercase())

            run.discoverAndInstall()
            run.lookAtWhatIsInstalled()
            run.update()
            run.uninstall()
            run.visitTheRest()
            assertEquals(emptyList(), run.changed)
        }
        assertEquals(emptyList(), github.unanswered.toList())
    }

    @Test
    fun theSystemsLookInLight() = walk(native, Appearance.Light)

    @Test
    fun theSystemsLookInDark() = walk(native, Appearance.Dark)

    @Test
    fun materialInLight() = walk(Look.Material, Appearance.Light)

    @Test
    fun materialInDark() = walk(Look.Material, Appearance.Dark)

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 800
        const val TIMEOUT = 30_000L
        val CLOCK: Instant = Instant.parse("2026-01-15T09:30:00Z")
    }
}
