package ketch.wire

import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test

/** Every contract scenario reads into the wire types and writes back as it was: no field renamed, dropped or added. */
class ScenarioTest {
    private val scenarios = File("../desktop/contract/scenarios").listFiles { file -> file.extension == "json" }.orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun typed(
        call: Call,
        value: JsonElement,
    ): JsonElement {
        val serializer = resultSerializer(call) as KSerializer<Any?>
        return Wire.json.encodeToJsonElement(serializer, Wire.json.decodeFromJsonElement(serializer, value))
    }

    @Test
    fun everyScenarioRoundTripsThroughTheWireTypes() {
        scenarios.toList() shouldHaveAtLeastSize 19
        for (file in scenarios) {
            val text = Wire.json.parseToJsonElement(file.readText())
            val scenario = Wire.json.decodeFromJsonElement(Scenario.serializer(), text)
            scenario.name shouldBe file.nameWithoutExtension
            Wire.json.encodeToJsonElement(Scenario.serializer(), scenario) shouldBe text
            (scenario.outcome as? Outcome.Ok)?.let { typed(scenario.call, it.value) shouldBe it.value }
        }
    }
}
