package ketch.core.source

import fs.setMode
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginTest {
    @Test
    fun httpsAndLoopbackHttpAreAllowedAndAnythingElseIsNot() {
        assertTrue(allowedAssetUrl("https://example.com/a.tar.gz"))
        assertTrue(allowedAssetUrl("http://127.0.0.1:9/a.bin"))
        assertTrue(allowedAssetUrl("http://localhost/a.bin"))
        assertFalse(allowedAssetUrl("http://evil.example/a.bin"))
        assertFalse(allowedAssetUrl("http://localhost.evil.example/a.bin"))
        assertFalse(allowedAssetUrl("file:///etc/passwd"))
        assertFalse(allowedAssetUrl("ftp://127.0.0.1/a.bin"))
    }
}
