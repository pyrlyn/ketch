package ketch.core.decide

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DeciderTest {
    @Test
    fun noDeciderAnswersNothingAndDeclines() {
        assertNull(NoDecider.chooseBinary("rtok", listOf("rtok-cli", "rtok-hook")))
        assertFalse(NoDecider.stopProcesses(emptyList()))
        assertFalse(NoDecider.confirmRemoveLeftover(Path("/k/store/ghost")))
    }
}
