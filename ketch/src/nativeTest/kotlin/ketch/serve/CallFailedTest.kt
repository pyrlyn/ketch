package ketch.serve

import ketch.core.error.KetchError
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ketch.wire.KetchError as WireError

class CallFailedTest {
    @Test
    fun aHeldLockKeepsItsPid() {
        assertEquals(WireError.Busy(42), KetchError.Busy(42, Path("/tmp/.lock")).toWire())
    }

    @Test
    fun cancellationIsItsOwnVariant() {
        assertEquals(WireError.Cancelled, KetchError.Cancelled().toWire())
    }

    @Test
    fun aMissingPackageOrReleaseIsNotFound() {
        assertEquals(WireError.NotFound("rg"), KetchError.NotInstalled("rg").toWire())
        assertEquals(WireError.NotFound("jq"), KetchError.NoRelease("jq").toWire())
    }

    @Test
    fun httpErrorsAreNetworkErrorsWithDetailAndHint() {
        val error = assertIs<WireError.Network>(KetchError.Http("https://api.github.com/x", 403, "rate limited").toWire())
        assertEquals(
            listOf(
                "HTTP 403 from https://api.github.com/x",
                "rate limited",
                "GitHub rate limit. Set GITHUB_TOKEN (or `gh auth token`) to raise it.",
            ),
            error.message.lines(),
        )
    }

    @Test
    fun aChecksumMismatchIsAVerificationError() {
        val error = assertIs<WireError.Verification>(KetchError.ChecksumMismatch("rg.tar.gz", "aa", "bb").toWire())
        assertTrue(error.message.startsWith("checksum mismatch for rg.tar.gz"))
        assertTrue("expected aa" in error.message)
    }

    @Test
    fun everythingElseKeepsTheCoreWordingWithoutControlCharacters() {
        assertEquals(WireError.Other("bad [2Jthing"), KetchError.Msg("bad \u001b[2Jthing‮").toWire())
    }

    @Test
    fun anExceptionFromUnderTheCoreIsStillAnErrorTheAppHears() {
        assertEquals(WireError.Other("disk full"), IllegalStateException("disk full").toWire())
    }
}
