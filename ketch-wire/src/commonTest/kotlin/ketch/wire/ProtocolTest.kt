package ketch.wire

import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class ProtocolTest {
    private fun line(message: ServeMessage) = Wire.json.encodeToString(ServeMessage.serializer(), message)

    private fun app(text: String) = Wire.json.decodeFromString(AppMessage.serializer(), text)

    @Test
    fun the_hello_line_names_the_protocol_and_the_version() {
        line(ServeMessage.Hello(PROTOCOL, "0.0.1")) shouldBe """{"type":"hello","protocol":1,"version":"0.0.1"}"""
    }

    @Test
    fun a_call_names_its_operation_and_takes_the_default_install_options() {
        app("""{"type":"call","id":7,"call":{"operation":"install","specs":["rg"]}}""") shouldBe
            AppMessage.Start(7, Call.Install(listOf("rg"), InstallOptions()))
        app("""{"type":"call","id":1,"call":{"operation":"installed"}}""") shouldBe
            AppMessage.Start(1, Call.InstalledPackages)
    }

    @Test
    fun answers_and_cancels_name_their_call() {
        app("""{"type":"answer","id":2,"question_id":5,"answer":{"type":"index","index":null}}""") shouldBe
            AppMessage.Reply(2, 5, Answer.Index(null))
        app("""{"type":"cancel","id":2}""") shouldBe AppMessage.Cancel(2)
    }

    @Test
    fun a_result_carries_its_error_or_its_value() {
        line(ServeMessage.Result(3, Outcome.Error(KetchError.Busy(null)))) shouldBe
            """{"type":"result","id":3,"outcome":{"type":"error","error":{"type":"busy","pid":null}}}"""
        line(ServeMessage.Result(4, Outcome.Ok(JsonPrimitive(12)))) shouldBe
            """{"type":"result","id":4,"outcome":{"type":"ok","value":12}}"""
    }

    @Test
    fun an_ask_and_an_event_keep_their_ids() {
        line(ServeMessage.Ask(1, 2, Question.ChooseBinary("uv", listOf("a", "b")))) shouldBe
            """{"type":"ask","id":1,"question_id":2,""" +
            """"question":{"type":"choose_binary","package":"uv","candidates":["a","b"]}}"""
        line(ServeMessage.Reported(1, Event.Began(9, TaskKind.Download("rg", null)))) shouldBe
            """{"type":"event","id":1,""" +
            """"event":{"type":"began","id":9,"task":{"type":"download","label":"rg","batch_id":null}}}"""
    }

    @Test
    fun a_call_names_the_serializer_its_value_reads_with() {
        @Suppress("UNCHECKED_CAST")
        val count = resultSerializer(Call.RegistryRefresh) as KSerializer<Long>
        Wire.json.encodeToJsonElement(count, 3L) shouldBe JsonPrimitive(3)
        Wire.json.decodeFromJsonElement(resultSerializer(Call.Root), JsonPrimitive("/k")) shouldBe "/k"
    }
}
