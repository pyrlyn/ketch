package ketch.core.selfupdate

import ketch.core.error.KetchError
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProbeWindowsTest {
    /** tree.com treats the argument as a path, finds nothing, and still exits 0: a stand-in for a ketch that starts fine. */
    @Test
    fun probeAcceptsABinaryThatAnswers() {
        probeRuns(Path("C:\\Windows\\System32\\tree.com"), 15.seconds)
    }

    /** where.exe prints usage and exits 1; the verification must not count that as "can run". */
    @Test
    fun probeRejectsABinaryThatFails() {
        val err = assertFailsWith<KetchError> { probeRuns(Path("C:\\Windows\\System32\\where.exe"), 15.seconds) }
        assertTrue("--version" in err.message.orEmpty(), err.message)
    }
}
