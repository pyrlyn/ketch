package ketch.desktop.settings

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open at login writes only into the home it is given, never the real one. */
class LoginItemTest {
    private val home: Path = Files.createTempDirectory("ketch-login")

    @AfterTest
    fun clean() = home.toFile().deleteRecursively().let { }

    @Test
    fun aDevelopmentRunHasNoLoginItem() {
        val item = LoginItem.forThisSystem(app = null, os = "Mac OS X", home = home, env = emptyMap())
        assertIs<Unavailable>(item)
        assertFalse(item.isAvailable)
        assertNotNull(item.set(true))
    }

    @Test
    fun macOSGetsALaunchAgent() {
        val item = LoginItem.forThisSystem(app = "/Applications/Ketch & Co.app/Contents/MacOS/Ketch", os = "Mac OS X", home = home, env = emptyMap())
        assertFalse(item.isEnabled())
        assertNull(item.set(true))
        assertTrue(item.isEnabled())
        val plist = home.resolve("Library/LaunchAgents/io.github.pyrlyn.ketch.plist").readText()
        assertTrue("<string>/Applications/Ketch &amp; Co.app/Contents/MacOS/Ketch</string>" in plist, plist)
        assertTrue("<key>RunAtLoad</key>" in plist)
        assertNull(item.set(false))
        assertFalse(item.isEnabled())
    }

    @Test
    fun linuxGetsAnAutostartEntryUnderXdgConfigHome() {
        val config = home.resolve("xdg")
        val item = LoginItem.forThisSystem(app = "/opt/ketch/bin/Ketch", os = "Linux", home = home, env = mapOf("XDG_CONFIG_HOME" to config.toString()))
        assertNull(item.set(true))
        val entry = config.resolve("autostart/ketch.desktop").readText()
        assertTrue("Exec=\"/opt/ketch/bin/Ketch\"\n" in entry, entry)
    }

    @Test
    fun linuxFallsBackToDotConfig() {
        assertNull(LoginItem.forThisSystem(app = "/opt/Ketch", os = "Linux", home = home, env = emptyMap()).set(true))
        assertTrue(Files.isRegularFile(home.resolve(".config/autostart/ketch.desktop")))
    }

    @Test
    fun anExecPathIsQuotedAndEscaped() {
        // A quoted argument escapes " ` $ \ itself; the key's string escape then doubles every backslash, and % starts a field code.
        assertEquals("\"/a b/100%%\"", Autostart.exec("/a b/100%"))
        assertEquals("\"/x\\\\\$y\\\\\"z\"", Autostart.exec("/x\$y\"z"))
        assertEquals("\"/a\\\\\\\\b\"", Autostart.exec("/a\\b"))
    }

    @Test
    fun windowsWritesTheRunKeyThroughReg() {
        val calls = mutableListOf<List<String>>()
        var present = false
        val item =
            RunKey(Path.of("C:\\Program Files\\Ketch\\Ketch.exe")) { args ->
                calls += args
                when (args.first()) {
                    "add" -> present = true
                    "delete" -> present = false
                }
                if (args.first() == "query" && !present) 1 else 0
            }
        assertFalse(item.isEnabled())
        assertNull(item.set(true))
        assertTrue(item.isEnabled())
        assertEquals(listOf("add", RunKey.KEY, "/v", "Ketch", "/t", "REG_SZ", "/d", "\"C:\\Program Files\\Ketch\\Ketch.exe\"", "/f"), calls[1])
        assertNull(item.set(false))
        assertFalse(item.isEnabled())
    }

    @Test
    fun aFailedRegIsReported() {
        assertNotNull(RunKey(Path.of("C:\\Ketch.exe")) { 1 }.set(true))
    }
}
