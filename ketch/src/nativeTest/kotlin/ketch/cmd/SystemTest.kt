package ketch.cmd

import ketch.core.platform.DoctorCheck
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemTest {
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun doctorJsonNamesTheWorstStatusAndEachCheck() {
        val checks = listOf(DoctorCheck.ok("version", "ketch 0.3.2"), DoctorCheck.warn("links", "1 broken", "ketch link x"))
        val report = doctorReport(checks)
        assertEquals("warn", report.text("status"))
        val rows = report.getValue("checks").jsonArray.map { it.jsonObject }
        assertEquals("version", rows[0].text("name"))
        assertEquals("ok", rows[0].text("status"))
        assertEquals(JsonNull, rows[0]["fix"])
        assertEquals("warn", rows[1].text("status"))
        assertEquals("ketch link x", rows[1].text("fix"))
    }

    @Test
    fun doctorJsonFailsWhenAnyCheckFails() {
        val checks = listOf(DoctorCheck.ok("version", "ketch 0.3.2"), DoctorCheck.fail("packages", "1 missing", "ketch install --force x"))
        assertEquals("fail", doctorReport(checks).text("status"))
    }
}
