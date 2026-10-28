package ketch.core.install

import fs.createDirAll
import fs.entryKind
import fs.joinPath
import fs.setMode
import fs.symlink
import ketch.core.error.KetchError
import ketch.core.report.Report
import ketch.core.testing.read
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreUnixTest {
    private val silent = Report.silent()

    @Test
    fun refusesAStoreSymlinkThatPointsOutside(): Unit =
        withRoot { cfg, tmp ->
            val victim = joinPath(tmp, "victim")
            createDirAll(victim)
            joinPath(victim, "keep").write("safe")
            val decoy = joinPath(cfg.storeDir, "decoy")
            symlink(victim, decoy)
            removeStoreDir(cfg, decoy, silent)
            assertNotNull(entryKind(joinPath(victim, "keep")), "a symlink inside the store must not delete its outside target")
            assertNotNull(entryKind(decoy))
        }

    @Test
    fun aLeftoverTheSweepCannotRemoveStopsTheInstallAndIsNamed(): Unit =
        withRoot { cfg, _ ->
            val folder = joinPath(cfg.storeDir, "tool")
            createDirAll(joinPath(folder, "1.0.0.old"))
            setMode(folder, "555".toInt(8))
            val err =
                try {
                    assertFailsWith<KetchError> { sweepSwapLeftovers(cfg, "tool", null) }
                } finally {
                    setMode(folder, "755".toInt(8))
                }
            assertTrue("1.0.0.old" in err.message, err.message)
        }

    @Test
    fun aPackageFolderLinkedOutsideTheStoreIsLeftAlone(): Unit =
        withRoot { cfg, tmp ->
            val victim = joinPath(tmp, "victim")
            createDirAll(victim)
            joinPath(victim, "keep").write("safe")
            symlink(victim, joinPath(cfg.storeDir, "tool"))
            assertNull(removePackageDir(cfg, "tool", silent))
            assertEquals("safe", joinPath(victim, "keep").read())
        }
}
