package ketch.desktop.core

import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.Event
import ketch.wire.InstallOptions
import ketch.wire.Installed
import ketch.wire.KetchError
import ketch.wire.Package
import ketch.wire.Question
import ketch.wire.Stage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ServeCoreTest {
    private val dir = createTempDirectory("ketch-desktop-test-")
    private val root = dir.resolve("root")

    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    /** The native ketch the build linked; null on a host that links none, where only the scripted tests run. */
    private val exe = System.getenv("KETCH_EXE")?.let { Path.of(it) }

    /** `ketch serve` over a scratch root and home, so nothing reaches the real `~/.ketch`. */
    private fun serving(body: suspend (ServeCore) -> Unit) {
        val ketch = exe ?: return println("KETCH_EXE is not set; skipping")
        val home = dir.resolve("home").createDirectories()
        runBlocking {
            withTimeout(60.seconds) {
                ServeCore.start(ketch, root, mapOf("HOME" to home.toString(), "KETCH_HOME" to home.resolve(".ketch").toString())).use { body(it) }
            }
        }
    }

    /** A stand-in for ketch: a shell script with [body]. */
    private fun script(body: String): Path {
        val file = dir.resolve("fake-ketch")
        file.writeText("#!/bin/sh\n$body\n")
        file.toFile().setExecutable(true)
        return file
    }

    private fun failure(body: suspend () -> Unit): KetchError = assertFailsWith<CoreException> { runBlocking { body() } }.error

    /** A folder a failed uninstall left behind for `ghost`, so uninstalling it asks a question. */
    private fun leftover(): Path = root.resolve("store/ghost").also { it.resolve("1.0.0.old").createDirectories() }

    @Test
    fun aScratchRootHasNothingInstalledAndTheVersionIsTheBinarys() =
        serving { core ->
            assertTrue(core.version.isNotBlank())
            assertEquals(emptyList(), core.value<List<Package>>(Call.InstalledPackages))
            assertEquals(root.toString(), core.value<String>(Call.Root))
        }

    @Test
    fun aLocalInstallReportsItsStepsAndReturnsWhatItPlaced() =
        serving { core ->
            val program = dir.resolve("hello")
            program.writeText("#!/bin/sh\necho hi\n")
            program.toFile().setExecutable(true)
            val events = mutableListOf<Event>()
            val callbacks =
                object : Callbacks {
                    override fun event(event: Event) {
                        events += event
                    }
                }
            val placed = core.value<List<Installed>>(Call.Install(listOf("local:$program"), InstallOptions()), callbacks)
            assertEquals("hello", placed.single().pkg.name)
            assertTrue(events.any { (it as? Event.Step)?.stage == Stage.Installing }, events.toString())
            assertEquals(listOf("hello"), core.value<List<Package>>(Call.InstalledPackages).map { it.name })
        }

    @Test
    fun theAppAnswersAQuestionWhileTheCallWaits() =
        serving { core ->
            val leftover = leftover()
            val asked = mutableListOf<Question>()
            val confirming =
                object : Callbacks {
                    override suspend fun ask(question: Question): Answer {
                        asked += question
                        return Answer.Confirm(true)
                    }
                }
            val error = assertFailsWith<CoreException> { core.call(Call.Uninstall(listOf("ghost")), confirming) }.error
            assertEquals(KetchError.NotFound("ghost"), error)
            assertEquals(listOf<Question>(Question.ConfirmRemoveLeftover(leftover.toString())), asked)
            assertTrue(!leftover.exists())
        }

    @Test
    fun aCancelDeclinesTheQuestionTheAppHasNotAnswered() =
        serving { core ->
            val leftover = leftover()
            val cancel = CancelToken()
            val stopped = CompletableDeferred<Unit>()
            val waiting =
                object : Callbacks {
                    override suspend fun ask(question: Question): Answer {
                        try {
                            cancel.cancel()
                            return CompletableDeferred<Answer>().await()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                }
            assertFailsWith<CoreException> { core.call(Call.Uninstall(listOf("ghost")), waiting, cancel) }
            withTimeout(2.seconds) { stopped.await() }
            assertTrue(leftover.exists(), "a cancel is no consent")
            assertEquals(root.toString(), core.value<String>(Call.Root), "the core still serves")
        }

    @Test
    fun closingFailsOpenAndFutureCallsEvenWhileStderrIsStillDraining() =
        runBlocking {
            val fake = script("""echo '{"type":"hello","protocol":1,"version":"x"}'; sleep 2 >&2 & cat >/dev/null""")
            val core = ServeCore.start(fake)
            try {
                val pending = async(start = CoroutineStart.UNDISPATCHED) { assertFailsWith<CoreException> { core.call(Call.Root) } }
                core.close()
                withTimeout(1.seconds) {
                    pending.await()
                    assertFailsWith<CoreException> { core.call(Call.Root) }
                    Unit
                }
            } finally {
                core.close()
            }
        }

    @Test
    fun aChildThatDiesFailsItsOpenCallWithWhatItSaid() {
        val fake = script("""echo '{"type":"hello","protocol":1,"version":"x"}'; read line; echo boom >&2; exit 3""")
        val error =
            failure {
                ServeCore.start(fake).use { core ->
                    assertEquals("x", core.version)
                    core.call(Call.Root)
                }
            }
        assertEquals(KetchError.Other("ketch serve stopped (exit 3)\nboom"), error)
    }

    @Test
    fun aChildThatSpeaksAnotherProtocolOrNoneIsRefused() {
        val other = failure { ServeCore.start(script("""echo '{"type":"hello","protocol":99,"version":"x"}'""")) }
        assertTrue("protocol 99" in assertIs<KetchError.Other>(other).message, other.toString())
        val mute = failure { ServeCore.start(script("echo nope >&2; exit 1")) }
        val said = assertIs<KetchError.Other>(mute).message
        assertTrue("did not say hello" in said && "nope" in said, said)
    }

    @Test
    fun theAppFindsKetchFromTheEnvironmentOrItsResources() {
        assertEquals(Path.of("/x/ketch"), locateKetch(mapOf("KETCH_EXE" to "/x/ketch"), null))
        val resources = dir.resolve("resources").createDirectories()
        val bundled = Files.createFile(resources.resolve("ketch")).also { it.toFile().setExecutable(true) }
        assertEquals(bundled, locateKetch(emptyMap(), resources.toString()))
        assertEquals(null, locateKetch(mapOf("KETCH_EXE" to ""), dir.toString()))
    }
}
