package ketch.cmd

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryTest {
    @Test
    fun theReviewDiffDropsEscapeSequencesFromTheRegistryCopy() {
        val registry = "name = \"tool\"\ndescription = \"\u001b[2J\u001b]0;x\u0007hi‮\"\n"
        val shown = reviewDiff(registry, "name = \"tool\"\n")
        assertTrue(shown.contains("-description"), shown)
        for (bad in listOf('\u001b', '\u0007', '‮')) assertFalse(shown.contains(bad), "$bad reached the terminal: $shown")
    }
}
