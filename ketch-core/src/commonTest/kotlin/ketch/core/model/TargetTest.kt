package ketch.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetTest {
    @Test
    fun aTargetRoundTripsThroughText() {
        for (os in Os.entries) for (arch in Arch.entries) assertEquals(TargetSpec(os, arch), TargetSpec.parse(TargetSpec(os, arch).toString()))
        assertEquals("macos-aarch64", TargetSpec(Os.MacOs, Arch.Aarch64).toString())
    }

    @Test
    fun unknownTargetsAreRefused() {
        val err = assertFailsWith<IllegalArgumentException> { TargetSpec.parse("plan9-mips") }
        assertEquals("`plan9-mips` is not a target; expected `<os>-<arch>`", err.message)
    }
}
