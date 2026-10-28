package ketch.core.config

import fs.joinPath
import ketch.core.error.KetchError
import ketch.core.log.Format
import ketch.core.log.Level
import ketch.core.testing.withEnv
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The log and emoji settings, each read from the file and overridden by its variable. */
class ConfigSettingsTest {
    private fun logWith(
        body: String,
        levelEnv: String? = null,
        formatEnv: String? = null,
    ): Pair<Level, Format> {
        val set = listOfNotNull(levelEnv?.let { "KETCH_LOG_LEVEL" to it }, formatEnv?.let { "KETCH_LOG_FORMAT" to it })
        return withEnv(listOf("KETCH_ROOT", "KETCH_LOG_LEVEL", "KETCH_LOG_FORMAT"), set.toMap()) {
            withTempDir { dir ->
                joinPath(dir, "config.toml").write(body)
                load(dir).let { it.logLevel to it.logFormat }
            }
        }
    }

    private fun logError(
        body: String,
        levelEnv: String? = null,
        formatEnv: String? = null,
    ): String = assertFailsWith<KetchError> { logWith(body, levelEnv, formatEnv) }.message.orEmpty()

    @Test
    fun aBadLogLevelInTheFileIsRefusedNamingTheFileAndTheKey() {
        val err = logError("log_level = \"chatty\"\n")
        for (part in listOf("config.toml", "log_level", "chatty")) assertTrue(part in err, err)
    }

    @Test
    fun aBadLogFormatInTheFileIsRefusedNamingTheFileAndTheKey() {
        val err = logError("log_format = \"xml\"\n")
        for (part in listOf("config.toml", "log_format", "xml")) assertTrue(part in err, err)
    }

    @Test
    fun logValuesInTheFileLoadAsBefore() {
        assertEquals(Level.Debug to Format.Json, logWith("log_level = \"debug\"\nlog_format = \"json\"\n"))
        // The spellings the environment variables always took still load from a file.
        assertEquals(Level.Warn to Format.Json, logWith("log_level = \"warning\"\nlog_format = \"jsonl\"\n"))
    }

    @Test
    fun logValuesInTheFileAreNotCaseSensitive() {
        assertEquals(Level.Info to Format.Json, logWith("log_level = \"Info\"\nlog_format = \"JSON\"\n"))
    }

    @Test
    fun aBadLogValueInTheFileListsTheAllowedValues() {
        val level = logError("log_level = \"chatty\"\n")
        assertTrue("off, error, warn, info or debug" in level, level)
        val format = logError("log_format = \"xml\"\n")
        assertTrue("text or json" in format, format)
    }

    @Test
    fun ketchLogLevelOverridesTheFile() {
        assertEquals(Level.Error to Format.Text, logWith("log_level = \"debug\"\n", levelEnv = "error"))
    }

    @Test
    fun ketchLogFormatOverridesTheFile() {
        assertEquals(Level.Info to Format.Json, logWith("log_format = \"text\"\n", formatEnv = "json"))
    }

    @Test
    fun aBadLogVariableIsRefusedByNameEvenWhenTheFileIsFine() {
        val level = logError("log_level = \"debug\"\n", levelEnv = "chatty")
        assertTrue("KETCH_LOG_LEVEL" in level, level)
        val format = logError("", formatEnv = "xml")
        assertTrue("KETCH_LOG_FORMAT" in format, format)
    }

    @Test
    fun anEmptyLogVariableFallsBackToTheFile() {
        assertEquals(Level.Warn to Format.Text, logWith("log_level = \"warn\"\n", levelEnv = " ", formatEnv = ""))
    }

    private fun emojiWith(
        body: String,
        env: String? = null,
    ): Boolean =
        withEnv(listOf("KETCH_ROOT", "KETCH_EMOJI"), listOfNotNull(env?.let { "KETCH_EMOJI" to it }).toMap()) {
            withTempDir { dir ->
                joinPath(dir, "config.toml").write(body)
                load(dir).emoji
            }
        }

    @Test
    fun emojiAreWantedUnlessSomethingSaysOtherwise(): Unit = assertTrue(emojiWith(""))

    @Test
    fun emojiFalseInTheFileTurnsThemOff(): Unit = assertFalse(emojiWith("emoji = false\n"))

    @Test
    fun ketchEmoji0TurnsThemOffOverTheFile() {
        assertFalse(emojiWith("emoji = true\n", "0"))
        assertTrue(emojiWith("emoji = false\n", "1"))
    }
}
