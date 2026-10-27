package ketch.core.extract

import fs.atomic
import fs.createDirAll
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtractorTest {
    @Test
    fun aLoneBundleIsThePayloadAndIsNotUnwrapped(): Unit =
        withTempDir { tmp ->
            createDirAll(Path(tmp, "TestApp.app/Contents/MacOS"))
            assertEquals(tmp, unwrapSingleDir(tmp))
        }

    @Test
    fun aLonePlainDirectoryIsStillUnwrapped(): Unit =
        withTempDir { tmp ->
            val inner = Path(tmp, "tool-1.0.0")
            createDirAll(Path(inner, "bin"))
            assertEquals(inner, unwrapSingleDir(tmp))
        }

    @Test
    fun unwrapsASingleWrapperDirectory(): Unit =
        withTempDir { tmp ->
            val inner = Path(tmp, "tool-1.2.3")
            createDirAll(Path(inner, "bin"))
            atomic(Path(tmp, ".DS_Store"), "x".encodeToByteArray())
            assertEquals(inner, unwrapSingleDir(tmp))
        }

    @Test
    fun keepsRootWhenPayloadIsFlat(): Unit =
        withTempDir { tmp ->
            atomic(Path(tmp, "a"), "x".encodeToByteArray())
            atomic(Path(tmp, "b"), "x".encodeToByteArray())
            assertEquals(tmp, unwrapSingleDir(tmp))
        }
}
