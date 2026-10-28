// The stand-in core for the app's tests: it answers as the real core did in desktop/contract/scenarios, the way the
// Rust apps' FakeKetchCore does. A read returns its scenario's value; install, upgrade and uninstall play the
// script, honour a cancel between steps, and change the installed list the fake answers afterwards.
package ketch.desktop.core

import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.Installed
import ketch.wire.KetchError
import ketch.wire.Outcome
import ketch.wire.Package
import ketch.wire.Question
import ketch.wire.Scenario
import ketch.wire.ScriptStep
import ketch.wire.Wire
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.time.Duration

/** The scenarios Rust generates, by file name without `.json`. */
fun scenarios(dir: File = File("contract/scenarios")): Map<String, Scenario> =
    dir
        .listFiles { f -> f.extension == "json" }
        .orEmpty()
        .associate { it.nameWithoutExtension to Wire.json.decodeFromString(Scenario.serializer(), it.readText()) }

fun scenario(name: String): Scenario = checkNotNull(scenarios()[name]) { "no scenario $name" }

/** The name `ketch serve` knows [call] by. */
fun operation(call: Call): String =
    Wire.json
        .encodeToJsonElement(Call.serializer(), call)
        .jsonObject
        .getValue("operation")
        .jsonPrimitive.content

class FakeCore(
    override val version: String = "0.0.0 · fake",
    /** How long each scripted step takes, so a test can cancel or look at a call while it runs. */
    var stepDelay: Duration = Duration.ZERO,
) : Core {
    private val lock = Any()

    // The store reads what is outdated after every operation; a fake nobody scripted that for has nothing newer.
    private val reads = mutableMapOf<String, JsonElement>("outdated" to JsonArray(emptyList()))
    private val scripted = mutableMapOf<String, Scenario>()
    private var installed = emptyList<Package>()
    private val made = mutableListOf<Call>()
    private val asked = mutableListOf<Pair<Question, Answer>>()

    /** Every call made, in order. */
    val calls: List<Call> get() = synchronized(lock) { made.toList() }

    /** Every question put to the app, with its answer. */
    val answers: List<Pair<Question, Answer>> get() = synchronized(lock) { asked.toList() }

    /** Answer [scenario]'s operation as the core did in it. */
    fun script(scenario: Scenario): FakeCore =
        apply {
            val op = operation(scenario.call)
            synchronized(lock) {
                when (op) {
                    "install", "upgrade", "uninstall" -> {
                        scripted[op] = scenario
                    }

                    "installed" -> {
                        installed = Wire.json.decodeFromJsonElement(PACKAGES, okValue(scenario))
                    }

                    else -> {
                        reads[op] = okValue(scenario)
                    }
                }
            }
        }

    override suspend fun call(
        call: Call,
        callbacks: Callbacks,
        cancel: CancelToken,
    ): JsonElement {
        val op = operation(call)
        val (read, scenario) =
            synchronized(lock) {
                made += call
                val read = if (op == "installed") Wire.json.encodeToJsonElement(PACKAGES, installed) else reads[op]
                read to scripted[op]
            }
        return read ?: replay(scenario ?: throw CoreException(KetchError.Other("no fake behaviour for $op")), callbacks, cancel)
    }

    private suspend fun replay(
        scenario: Scenario,
        callbacks: Callbacks,
        cancel: CancelToken,
    ): JsonElement {
        for (step in scenario.script) {
            if (cancel.isCancelled) throw CoreException(KetchError.Cancelled)
            when (step) {
                is ScriptStep.Reported -> {
                    callbacks.event(step.event)
                }

                is ScriptStep.Ask -> {
                    val answer = callbacks.ask(step.question)
                    synchronized(lock) { asked += step.question to answer }
                    if (declinedChoice(step.question, answer)) throw CoreException(KetchError.Cancelled)
                }
            }
            delay(stepDelay)
        }
        return finish(scenario)
    }

    private fun finish(scenario: Scenario): JsonElement =
        when (val outcome = scenario.outcome) {
            is Outcome.Error -> throw CoreException(outcome.error)
            is Outcome.Ok -> outcome.value.also { settle(operation(scenario.call), it) }
        }

    /** A declined binary choice stops the install, as the core does; the other questions only change what it does. */
    private fun declinedChoice(
        question: Question,
        answer: Answer,
    ) = question is Question.ChooseBinary && (answer as? Answer.Index)?.index?.takeIf { it in question.candidates.indices } == null

    private fun settle(
        op: String,
        value: JsonElement,
    ) = synchronized(lock) {
        if (op == "uninstall") {
            val removed =
                Wire.json
                    .decodeFromJsonElement(PACKAGES, value)
                    .map { it.name }
                    .toSet()
            installed = installed.filter { it.name !in removed }
        } else {
            val placed = Wire.json.decodeFromJsonElement(ListSerializer(Installed.serializer()), value).map { it.pkg }
            installed = installed.filter { old -> placed.none { it.name == old.name } } + placed
        }
    }

    override fun close() = Unit

    private companion object {
        val PACKAGES = ListSerializer(Package.serializer())

        fun okValue(scenario: Scenario) = (scenario.outcome as? Outcome.Ok)?.value ?: error("${scenario.name} is no read")
    }
}
