package ketch.core.platform

import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.joinPath
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
            val occupied = joinPath(tmp, "tool")
            occupied.write("old")
            val empty = joinPath(tmp, "other")

            val claims = Claims()
            claims.take(occupied)
            claims.take(empty)
            assertNull(entryKind(occupied), "moved out of the way")
            occupied.write("new")
            createDirAll(joinPath(empty, "half"))

            claims.restore()

            assertEquals("old", occupied.read())
            assertNull(entryKind(empty), "a half copy is removed")
            assertEquals(1, listDir(tmp).size)
        }
    }

    @Test
    fun discardDropsThePreviousOccupantsAndKeepsTheNewEntries() {
        withTempDir { tmp ->
            val occupied = joinPath(tmp, "Tool.app")
            createDirAll(joinPath(occupied, "Contents"))

            val claims = Claims()
            claims.take(occupied)
            createDirAll(joinPath(occupied, "New"))
            claims.discard()

            assertTrue(isDir(joinPath(occupied, "New")))
            assertNull(entryKind(joinPath(occupied, "Contents")))
            assertEquals(1, listDir(tmp).size)
        }
    }
}
