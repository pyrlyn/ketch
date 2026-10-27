package ketch.core.platform

import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.listDir
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaimsTest {
    @Test
    fun restorePutsEveryOccupantBackAndRemovesWhatWasNew() {
        withTempDir { tmp ->
            val occupied = Path(tmp, "tool")
            occupied.write("old")
            val empty = Path(tmp, "other")

            val claims = Claims()
            claims.take(occupied)
            claims.take(empty)
            assertNull(entryKind(occupied), "moved out of the way")
            occupied.write("new")
            createDirAll(Path(empty, "half"))

            claims.restore()

            assertEquals("old", occupied.read())
            assertNull(entryKind(empty), "a half copy is removed")
            assertEquals(1, listDir(tmp).size)
        }
    }

    @Test
    fun discardDropsThePreviousOccupantsAndKeepsTheNewEntries() {
        withTempDir { tmp ->
            val occupied = Path(tmp, "Tool.app")
            createDirAll(Path(occupied, "Contents"))

            val claims = Claims()
            claims.take(occupied)
            createDirAll(Path(occupied, "New"))
            claims.discard()

            assertTrue(isDir(Path(occupied, "New")))
            assertNull(entryKind(Path(occupied, "Contents")))
            assertEquals(1, listDir(tmp).size)
        }
    }
}
