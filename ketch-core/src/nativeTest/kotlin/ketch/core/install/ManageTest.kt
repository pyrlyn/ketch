package ketch.core.install

import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.listDir
import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.model.RetainedVersion
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.source.SourceRegistry
import ketch.core.state.Lock
import ketch.core.state.State
import ketch.core.testing.program
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManageTest {
    private fun <T> withLocal(body: (Ctx, Path) -> T): T =
        withTempDir { tmp ->
            val cfg = Config.load(Path(tmp, "root"), Report.silent())
            body(Ctx(cfg, Report.silent()), tmp)
        }

    private fun storeIsEmpty(cfg: Config) = entryKind(cfg.storeDir) == null || listDir(cfg.storeDir).isEmpty()

    @Test
    fun pruneNeverRemovesTheLivePrefixAStaleEntryNames(): Unit =
        withRoot { cfg, _ ->
            val live = cfg.packageDir("tool", "2.0.0")
            val old = cfg.packageDir("tool", "1.0.0")
            createDirAll(live)
            createDirAll(old)
            val pkg =
                installed("tool", "2.0.0", live).copy(
                    retained =
                        listOf(
                            RetainedVersion.fromInstalled(installed("tool", "1.0.0", old)),
                            RetainedVersion.fromInstalled(installed("tool", "2.0.0", live)),
                        ),
                )
            val state = State().apply { insert(pkg) }

            prune(Ctx(cfg, Report.silent()), state, "tool", 1u)

            assertTrue(isDir(live), "the live prefix must survive")
            assertTrue(isDir(old), "the one kept version stays")
            assertEquals(listOf(old), state.get("tool")!!.retained.map { it.prefix })
        }

    @Test
    fun selectRetainedDefaultsToThePreviousAndNamesAMiss() {
        val pkg = installed("tool", "2.0.0", Path("/store/tool/2.0.0"))
        assertFailsWith<KetchError.NoRetained> { selectRetained(pkg, null) }
        val with = pkg.copy(retained = listOf(RetainedVersion.fromInstalled(installed("tool", "1.0.0", Path("/store/tool/1.0.0")))))
        assertEquals(0, selectRetained(with, null))
        assertEquals(0, selectRetained(with, "1.0.0"))
        assertFailsWith<KetchError> { selectRetained(with, "0.9.0") }
        assertFailsWith<KetchError.AlreadyInstalled> { selectRetained(with, "v2.0.0") }
    }

    @Test
    fun aCancelledTokenStopsABatchBeforeAnyPackageIsPrepared(): Unit =
        withLocal { cx, tmp ->
            val tool = program(tmp, "batchtool", "#!/bin/sh\necho hi\n")
            val state = State()
            val cancel = Cancel().apply { cancel() }
            val reqs = List(2) { localRequest(tool, cancel) }

            for (jobs in listOf(1, 2)) {
                val outcomes = batch(cx, SourceRegistry.load(cx), state, reqs, jobs)
                assertEquals(2, outcomes.size)
                outcomes.forEach { assertIs<KetchError.Cancelled>(it.exceptionOrNull(), "jobs = $jobs") }
            }
            assertTrue(state.iter().isEmpty())
            assertTrue(storeIsEmpty(cx.cfg))
        }

    @Test
    fun aBatchPlacesEveryPackageAndAnswersInRequestOrder(): Unit =
        withLocal { cx, tmp ->
            val names = listOf("alpha", "bravo", "charlie")
            val reqs = names.map { localRequest(program(tmp, it, "#!/bin/sh\necho $it\n")) }
            val state = State()

            val outcomes = batch(cx, SourceRegistry.load(cx), state, reqs, 2)

            assertEquals(names, outcomes.map { it.getOrThrow().pkg.name })
            assertEquals(names, state.names().sorted())
        }

    @Test
    fun twoOperationsInOneProcessRunOneAfterTheOther(): Unit =
        withLocal { cx, tmp ->
            val sources = SourceRegistry.load(cx)
            val state = State()
            for (name in listOf("firsttool", "secondtool")) {
                val tool = program(tmp, name, "#!/bin/sh\necho hi\n")
                // What a host does per operation: lock, run, release.
                Lock.acquire(cx).use { install(cx, sources, state, localRequest(tool)) }
            }
            assertEquals(2, state.iter().size)
        }

    @Test
    fun anUninstallTakesTheLinksTheStoreFolderAndTheStateEntry(): Unit =
        withLocal { cx, tmp ->
            val tool = program(tmp, "gonetool", "#!/bin/sh\necho hi\n")
            val state = State()
            install(cx, SourceRegistry.load(cx), state, localRequest(tool))
            // Named by the record: Windows places `gonetool.exe`.
            val link =
                state
                    .get("gonetool")!!
                    .links
                    .single()
                    .link
            assertTrue(entryKind(link) != null, "linked first")

            assertEquals("gonetool", uninstall(cx, state, "gonetool").name)

            assertNull(state.get("gonetool"))
            assertNull(entryKind(link), "no link")
            assertNull(entryKind(Path(cx.cfg.storeDir, "gonetool")), "no store folder")
            assertFailsWith<KetchError.NotInstalled> { uninstall(cx, state, "gonetool") }
        }

    @Test
    fun unlinkKeepsTheFilesAndRelinkBringsTheLinkBack(): Unit =
        withLocal { cx, tmp ->
            val tool = program(tmp, "backtool", "#!/bin/sh\necho hi\n")
            val state = State()
            install(cx, SourceRegistry.load(cx), state, localRequest(tool))
            val link =
                state
                    .get("backtool")!!
                    .links
                    .single()
                    .link

            unlink(cx, state, "backtool")
            assertNull(entryKind(link))
            assertTrue(state.get("backtool")!!.links.isEmpty())
            assertTrue(isDir(state.get("backtool")!!.prefix))

            relink(cx, state, "backtool")
            assertTrue(entryKind(link) != null)
            assertTrue(state.get("backtool")!!.links.isNotEmpty())
        }

    @Test
    fun aPinnedPackageRefusesARollback(): Unit =
        withLocal { cx, _ ->
            val state = State().apply { insert(installed("tool", "2.0.0", Path("/store/tool/2.0.0"))) }
            assertTrue(pin(state, "tool", true).pinned)
            assertFailsWith<KetchError.Pinned> { rollback(cx, state, "tool", null) }
            pin(state, "tool", false)
            assertFailsWith<KetchError.NoRetained> { rollback(cx, state, "tool", null) }
        }
}
