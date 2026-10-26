package ketch.core.cancel

import ketch.core.error.KetchError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CancelTest {
    @Test
    fun aCancelledTokenIsSeenByItsHolders() {
        val token = Cancel()
        val other = token
        other.check()
        token.cancel()
        assertTrue(other.isCancelled)
        assertFailsWith<KetchError.Cancelled> { other.check() }
    }
}
