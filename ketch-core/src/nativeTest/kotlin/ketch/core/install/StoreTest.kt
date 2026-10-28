package ketch.core.install

import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.joinPath
import fs.listDir
import ketch.core.config.Config
import ketch.core.config.HOST_WINDOWS
import ketch.core.model.RetainedVersion
import ketch.core.model.Version
import ketch.core.platform.pathIsWithin
import ketch.core.report.Report
import ketch.core.state.State
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private val silent = Report.silent()

    @Test
    fun refusesToDeleteOutsideTheStore(): Unit =
        withRoot { cfg, tmp ->
            val outside = joinPath(tmp, "ketch-not-the-store")
            createDirAll(outside)
            removeStoreDir(cfg, outside, silent)
            assertTrue(isDir(outside), "a path outside the store must survive")
        }

    /** On Windows a prefix that differs from the store only in ASCII case is a real uninstall and GC failure mode. */
    @Test
    fun insideStoreAcceptsTheSameCasePrefix(): Unit =
        withRoot { cfg, _ ->
            val payload = joinPath(joinPath(cfg.storeDir, "rg"), "1.0.0")
            createDirAll(payload)
            assertTrue(isInsideStore(cfg.storeDir, payload), "same-case payload must be inside")
            if (HOST_WINDOWS) {
                val swapped =
                    cfg.storeDir
                        .toString()
                        .map { if (it.isLowerCase()) it.uppercaseChar() else it.lowercaseChar() }
                        .joinToString("")
                val alt = joinPath(joinPath(Path(swapped), "rg"), "1.0.0")
                assertTrue(isInsideStore(cfg.storeDir, alt), "Windows must treat differently-cased store prefixes as inside: ${cfg.storeDir} vs $alt")
            }
        }

    @Test
    fun refusesADotDotEscapeOutOfTheStore(): Unit =
        withRoot { cfg, tmp ->
            val victim = joinPath(tmp, "victim")
            createDirAll(victim)
            joinPath(victim, "keep").write("safe")
            // A lexical child of the store that resolves to `victim` through `..`.
            val escape = joinPath(cfg.storeDir, "pkg/1.0.0/../../../victim")
            assertTrue(pathIsWithin(escape, cfg.storeDir), "precondition: a lexical prefix test alone would allow this")
            removeStoreDir(cfg, escape, silent)
            assertNotNull(entryKind(joinPath(victim, "keep")), "`..` must not let uninstall delete outside the store")
        }

    @Test
    fun thePackageFolderGoesWholeWithStaleSwapSiblings(): Unit =
        withRoot { cfg, _ ->
            val folder = joinPath(cfg.storeDir, "tool")
            createDirAll(joinPath(folder, "1.0.0.old"))
            joinPath(folder, "1.0.0.old/stale").write("x")
            assertEquals(folder, removePackageDir(cfg, "tool", silent))
            assertNull(entryKind(folder))
            assertTrue(isDir(cfg.storeDir), "the store itself stays")
        }

    @Test
    fun theSweepTakesSwapLeftoversAndKeepsEveryVersion(): Unit =
        withRoot { cfg, _ ->
            val folder = joinPath(cfg.storeDir, "tool")
            createDirAll(joinPath(folder, "1.0.0"))
            createDirAll(joinPath(folder, "1.1.0.incoming"))
            joinPath(folder, "1.1.0.incoming/planted").write("x")
            createDirAll(joinPath(folder, "1.0.0.old"))
            joinPath(folder, "0.9.0.old").write("a file, not a folder")
            sweepSwapLeftovers(cfg, "tool", null)
            assertEquals(listOf("1.0.0"), listDir(folder).sorted())
        }

    @Test
    fun theSweepNeverTakesAPrefixThePackageRecords(): Unit =
        withRoot { cfg, _ ->
            // A release really versioned like a leftover.
            val prefix = joinPath(joinPath(cfg.storeDir, "tool"), "2.old")
            createDirAll(prefix)
            sweepSwapLeftovers(cfg, "tool", installed("tool", "2.old", prefix))
            assertTrue(isDir(prefix))
        }

    @Test
    fun theSweepIsANoOpWithoutAPackageFolder(): Unit =
        withRoot { cfg, _ ->
            sweepSwapLeftovers(cfg, "tool", null)
            sweepSwapLeftovers(cfg, "../outside", null)
        }

    @Test
    fun aPackageNameThatIsNotOneComponentIsNeverRemoved(): Unit =
        withRoot { cfg, _ ->
            val nested = joinPath(joinPath(cfg.storeDir, "a"), "b")
            createDirAll(nested)
            for (name in listOf("a/b", "..", ".", "", "a/../a")) assertNull(removePackageDir(cfg, name, silent), name)
            assertTrue(isDir(nested))
            assertTrue(isDir(cfg.storeDir))
        }

    @Test
    fun aFolderHoldingARecordedPrefixIsNotALeftover(): Unit =
        withRoot { cfg, _ ->
            val prefix = cfg.packageDir("tool", "1.0.0")
            createDirAll(prefix)
            val orphan = joinPath(cfg.storeDir, "ghost")
            createDirAll(orphan)
            val state = State()
            // Recorded under another key, so a lookup of "tool" misses it.
            state.insert(installed("renamed", "1.0.0", prefix))
            assertNull(leftoverPackageDir(cfg, state, "tool"))
            assertEquals(orphan, leftoverPackageDir(cfg, state, "ghost"))
        }

    @Test
    fun aNameSpelledDifferentlyOnDiskIsNotACandidate(): Unit =
        withRoot { cfg, _ ->
            val folder = joinPath(cfg.storeDir, "tool")
            createDirAll(folder)
            for (name in listOf("TOOL", "Tool")) assertNull(removePackageDir(cfg, name, silent), name)
            assertTrue(isDir(folder))
            assertEquals(folder, leftoverPackageDir(cfg, State(), "tool"))
        }

    @Test
    fun aMissingPackageFolderIsNotACandidate(): Unit =
        withRoot { cfg, _ ->
            assertNull(removePackageDir(cfg, "tool", silent))
        }

    /**
     * `KETCH_ROOT=../ketch` keeps the `..` in the root path, and so in every store prefix below it. Those are the
     * root's components, not the state file's, and refusing them disables every cleanup there is.
     */
    @Test
    fun aRootWrittenWithDotDotStillCleansUpAfterItself(): Unit =
        withRoot { _, tmp ->
            val work = joinPath(tmp, "work")
            createDirAll(work)
            val cfg = Config.load(joinPath(work, "../ketch"), silent)
            cfg.ensureDirs()
            val prefix = joinPath(joinPath(cfg.storeDir, "tool"), "1.0.0")
            createDirAll(prefix)
            joinPath(prefix, "tool").write("x")
            removeStoreDir(cfg, prefix, silent)
            assertNull(entryKind(prefix), "a `..` that came from the root must not stop the payload being removed")
        }

    @Test
    fun retainReplacedKeepsAnEligiblePrefixAndSkipsTheSameOne(): Unit =
        withRoot { cfg, _ ->
            val oldPrefix = cfg.packageDir("tool", "1.0.0")
            createDirAll(oldPrefix)
            val old = installed("tool", "1.0.0", oldPrefix)
            val retained = mutableListOf<RetainedVersion>()
            retainReplaced(cfg, old, cfg.packageDir("tool", "2.0.0"), Version.parse("2.0.0"), retained)
            assertEquals(listOf("1.0.0"), retained.map { it.version.toString() })
            assertEquals(oldPrefix, retained[0].prefix)
            assertTrue(isDir(oldPrefix), "retention must not delete the prefix")

            retainReplaced(cfg, old, oldPrefix, old.version, retained)
            assertTrue(retained.isEmpty(), "an in-place reinstall must not retain itself")
        }

    @Test
    fun upgradingBackToARetainedVersionDropsItsRetainedEntry(): Unit =
        withRoot { cfg, _ ->
            val v1 = cfg.packageDir("tool", "1.0.0")
            val v2 = cfg.packageDir("tool", "2.0.0")
            createDirAll(v1)
            createDirAll(v2)
            // What a rollback from 2.0.0 to 1.0.0 leaves behind.
            val retained = mutableListOf(RetainedVersion.fromInstalled(installed("tool", "2.0.0", v2)))
            val rolledBack = installed("tool", "1.0.0", v1).copy(retained = retained.toList())
            retainReplaced(cfg, rolledBack, v2, Version.parse("2.0.0"), retained)
            assertEquals(listOf(v1), retained.map { it.prefix })
        }
}
