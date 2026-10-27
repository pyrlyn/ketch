package ketch.cli

import ketch.core.VERSION
import ketch.core.selfupdate.VERSION_CHANNEL
import ketch.core.selfupdate.displayVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CliTest {
    @Test
    fun versionIsThePackageVersionMarkedPreview() {
        val version = ketchCli().version
        assertEquals(displayVersion(), version)
        assertTrue(version.orEmpty().endsWith(" · $VERSION_CHANNEL"), "the version must mark the channel: $version")
        if (VERSION != "0.1.0") assertNotEquals("0.1.0", version, "the version must not be hard-coded")
    }
}
