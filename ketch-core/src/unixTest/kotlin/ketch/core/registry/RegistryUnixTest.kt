package ketch.core.registry

import fs.createDirAll
import fs.symlink
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegistryUnixTest {
    /** The tree is somebody else's: a link here would have ketch read — and quote in an error — a file from outside it. */
    @Test
    fun checkTreeNeverReadsThroughASymlinkedPackageFile(): Unit =
        withTempDir { tmp ->
            val secret = Path(tmp, "secret.txt")
            secret.write("root:x:0:0:root:/root:/bin/sh\n")
            write(tmp, "good", "source = \"github:a/b\"\n")
            val sneaky = Path(tmp, "sneaky")
            createDirAll(sneaky)
            symlink(secret, Path(sneaky, PACKAGE_FILE))
            val report = checkTree(tmp)
            assertEquals(1, report.packages)
            val error = report.errors.single()
            assertTrue("not a symlink" in error.message, error.message)
            assertTrue(error.path.endsWith("sneaky/ketch.toml"), error.path)
        }
}
