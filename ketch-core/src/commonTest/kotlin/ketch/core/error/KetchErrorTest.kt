package ketch.core.error

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KetchErrorTest {
    @Test
    fun messagesReadAsRustKetchPrintsThem() {
        assertEquals("another ketch process holds the lock (pid 42)", KetchError.Busy(42, Path("/k/lock")).message)
        assertEquals("another ketch process holds the lock (/k/lock)", KetchError.Busy(null, Path("/k/lock")).message)
        assertEquals("release `v1` of `a/b` has no asset for macos-aarch64", KetchError.NoCompatibleAsset("a/b", "v1", "macos-aarch64").message)
    }

    @Test
    fun exitCodesSeparateFailureClasses() {
        assertEquals(4, KetchError.NotInstalled("rg").exitCode())
        assertEquals(5, KetchError.Pinned("rg", "1.0").exitCode())
        assertEquals(6, KetchError.ChecksumMissing("rg").exitCode())
        assertEquals(7, KetchError.Http("u", 500, null).exitCode())
        assertEquals(8, KetchError.Busy(null, Path("/l")).exitCode())
        assertEquals(3, KetchError.Reported(3).exitCode())
        assertEquals(130, KetchError.Cancelled().exitCode())
        assertEquals(1, KetchError.Msg("x").exitCode())
    }

    @Test
    fun detailsAndHints() {
        assertEquals(listOf("expected a", "actual   b"), KetchError.ChecksumMismatch("rg", "a", "b").details())
        assertEquals(listOf("one", "two"), KetchError.Command("c", "1", "\n one\ntwo \n").details())
        assertEquals(emptyList(), KetchError.Command("c", "1", "  \n").details())
        assertEquals("Check the owner/repo spelling, or the repo may be private.", KetchError.Http("u", 404, null).hint())
        assertNull(KetchError.Http("u", 500, null).hint())
    }
}
