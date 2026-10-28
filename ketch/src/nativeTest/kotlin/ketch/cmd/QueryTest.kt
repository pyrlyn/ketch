package ketch.cmd

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryTest {
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.first(key: String) = getValue(key).jsonArray[0].jsonObject

    @Test
    fun outdatedJsonNamesTheWorstStatusAndEachFailure() {
        val outdated =
            listOf(
                jsonOf(
                    "name" to JsonPrimitive("ripgrep"),
                    "installed" to JsonPrimitive("14.0.0"),
                    "latest" to JsonPrimitive("14.1.0"),
                    "tag" to JsonPrimitive("v14.1.0"),
                    "pinned" to JsonPrimitive(false),
                ),
            )
        val failed = listOf(jsonOf("name" to JsonPrimitive("ghost"), "error" to JsonPrimitive("no releases")))
        val report = outdatedReport(1, outdated, failed)
        assertEquals("partial", report.text("status"))
        assertEquals("1", report.text("unreachable"))
        assertEquals("ripgrep", report.first("outdated").text("name"))
        assertEquals("ghost", report.first("failed").text("name"))
        assertEquals("no releases", report.first("failed").text("error"))
    }

    @Test
    fun outdatedJsonMarksATotalFailure() {
        val report = outdatedReport(0, emptyList(), listOf(jsonOf("name" to JsonPrimitive("ghost"), "error" to JsonPrimitive("offline"))))
        assertEquals("fail", report.text("status"))
        assertEquals("1", report.text("unreachable"))
        assertTrue((report.getValue("outdated") as JsonArray).isEmpty())
    }

    @Test
    fun jsonProseStripsBidiOverrides() {
        assertEquals("safeevil", jsonProse("safe‮evil"))
    }
}
