package ketch.cmd

import ketch.core.error.KetchError
import ketch.core.importer.notGithubMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ImportTest {
    @Test
    fun aRefusalForTheTypedNameIsPrintedBare() {
        val err = assertIs<KetchError.Reported>(refusal("jq", KetchError.Msg(notGithubMessage("jq"))))
        assertEquals(1, err.code)
    }

    @Test
    fun anyOtherFailureKeepsItsError() {
        assertIs<KetchError.Msg>(refusal("nope", KetchError.Msg("Arch Linux has no package named `nope`")))
    }
}
