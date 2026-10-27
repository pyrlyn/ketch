package ketch.core.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FetchTest {
    @Test
    fun eachEndpointFollowsItsOwnVariableAndDropsATrailingSlash() {
        val e =
            Endpoints.fromLookup {
                when (it) {
                    "KETCH_IMPORT_BREW" -> "http://127.0.0.1:9/brew/"
                    "KETCH_IMPORT_AUR" -> "  "
                    else -> null
                }
            }
        assertEquals("http://127.0.0.1:9/brew", e.brew)
        assertEquals(Endpoints().aur, e.aur, "a blank value is no value")
        assertEquals(Endpoints().wingetRaw, e.wingetRaw)
    }

    @Test
    fun aRecordedUrlAnswersAndAnyOtherIsAbsent() {
        val f = Recorded().with("u", "body")
        assertEquals("body", f.text("u"))
        assertNull(f.text("v"))
    }
}
