// Open at login, where the platform allows. Only the installed app can be started at login: a development run has no
// launcher to name, so it reports the switch as unavailable. The state is read from the system each time, since the
// person can change it there too.
package ketch.desktop.settings

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface LoginItem {
    /** False when this run cannot be started at login, as a development run cannot. */
    val isAvailable: Boolean

    fun isEnabled(): Boolean

    /** Registers or unregisters the app; the message to show on failure. */
    fun set(enabled: Boolean): String?

    companion object {
        /** The item for this system and this run's launcher (`jpackage.app-path`, set by the packaged app). */
        fun forThisSystem(
            app: String? = System.getProperty("jpackage.app-path"),
            os: String = System.getProperty("os.name"),
            home: Path = Path.of(System.getProperty("user.home")),
            env: Map<String, String> = System.getenv(),
        ): LoginItem {
            val launcher = app?.takeIf { it.isNotEmpty() }?.let(Path::of) ?: return Unavailable
            return when {
                os.startsWith("Mac") -> LaunchAgent(launcher, home)
                os.startsWith("Windows") -> RunKey(launcher)
                else -> Autostart(launcher, env["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() }?.let(Path::of) ?: home.resolve(".config"))
            }
        }
    }
}

object Unavailable : LoginItem {
    override val isAvailable = false

    override fun isEnabled() = false

    override fun set(enabled: Boolean) = "Open at login is available in the installed app."
}

/** A file the system reads at login: a LaunchAgent plist on macOS, an autostart entry on Linux. */
abstract class LoginFile(
    private val file: Path,
) : LoginItem {
    override val isAvailable = true

    protected abstract fun contents(): String

    override fun isEnabled() = Files.isRegularFile(file)

    override fun set(enabled: Boolean): String? =
        try {
            if (enabled) {
                Files.createDirectories(file.parent)
                Files.writeString(file, contents())
            } else {
                Files.deleteIfExists(file)
            }
            null
        } catch (e: IOException) {
            "Could not change $file: ${e.message}"
        }
}

class LaunchAgent(
    private val launcher: Path,
    home: Path,
) : LoginFile(home.resolve("Library/LaunchAgents/$LABEL.plist")) {
    override fun contents() =
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |    <key>Label</key>
        |    <string>$LABEL</string>
        |    <key>ProgramArguments</key>
        |    <array>
        |        <string>${xml(launcher.toString())}</string>
        |    </array>
        |    <key>RunAtLoad</key>
        |    <true/>
        |</dict>
        |</plist>
        |
        """.trimMargin()

    companion object {
        /** The macOS app's bundle id. */
        const val LABEL = "io.github.pyrlyn.ketch"

        fun xml(text: String) =
            text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
    }
}

class Autostart(
    private val launcher: Path,
    config: Path,
) : LoginFile(config.resolve("autostart/ketch.desktop")) {
    override fun contents() = "[Desktop Entry]\nType=Application\nName=Ketch\nExec=${exec(launcher.toString())}\nX-GNOME-Autostart-enabled=true\n"

    companion object {
        /** One quoted argument, as the Desktop Entry spec's Exec key wants it; `%` would start a field code. */
        fun exec(path: String): String {
            val quoted =
                path
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("`", "\\`")
                    .replace("$", "\\$")
            // The spec's escape for a quoted argument is applied first, then the key's own backslash escape.
            return "\"$quoted\"".replace("\\", "\\\\").replace("%", "%%").replace("\n", " ")
        }
    }
}

/** The `HKCU\…\Run` value, through `reg.exe`, which every Windows has. */
class RunKey(
    private val launcher: Path,
    private val reg: (List<String>) -> Int = ::run,
) : LoginItem {
    override val isAvailable = true

    override fun isEnabled() = reg(listOf("query", KEY, "/v", NAME)) == 0

    override fun set(enabled: Boolean): String? {
        val args = if (enabled) listOf("add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "\"$launcher\"", "/f") else listOf("delete", KEY, "/v", NAME, "/f")
        return if (reg(args) == 0 || (!enabled && !isEnabled())) null else "Windows did not change the login item."
    }

    companion object {
        const val KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
        const val NAME = "Ketch"
        private const val WAIT_SECONDS = 10L

        private fun run(args: List<String>): Int =
            try {
                val process = ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start()
                process.inputStream.readAllBytes()
                if (process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) process.exitValue() else -1
            } catch (e: IOException) {
                System.err.println("ketch: could not run reg: ${e.message}")
                -1
            }
    }
}
