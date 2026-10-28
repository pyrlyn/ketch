package ketch.core.listing

import fs.joinPath
import ketch.core.config.Config
import ketch.core.install.InstalledBuild
import ketch.core.model.Channel
import ketch.core.model.Checksum
import ketch.core.model.PackageRef
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.Version
import ketch.core.model.nowUnix
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheTest {
    @Test
    fun thePrereleaseFlagSeparatesCacheEntries() {
        val stable = Lookup(PackageRef("test", "alpha"), false, false, null)
        assertNotEquals(stable.key(), stable.copy(prerelease = true).key())
    }

    @Test
    fun nothingAnsweringIsOfflineButOneAnswerIsNot() {
        assertFalse(Outcome().offline())
        assertTrue(Outcome(3, 0).offline())
        assertFalse(Outcome(3, 1).offline())
    }

    @Test
    fun aCacheEntryIsFreshForTheTtlAndNotFromTheFuture() {
        val entry = CacheEntry(found("1.0.0"), 1_000)
        assertTrue(entry.fresh(1_000))
        assertTrue(entry.fresh(1_000 + CACHE_TTL_SECS - 1))
        assertFalse(entry.fresh(1_000 + CACHE_TTL_SECS))
        assertFalse(entry.fresh(999))
    }

    @Test
    fun theCacheRoundTripsAndAMalformedOneIsEmpty(): Unit =
        withTempDir { tmp ->
            val path = joinPath(tmp, "cache", CACHE_FILE)
            Cache(entries = mapOf("test:alpha#stable" to CacheEntry(found("1.2.3"), 5))).save(path, Report.silent())
            assertEquals(found("1.2.3"), Cache.load(path, Report.silent()).entries["test:alpha#stable"]?.found)
            path.write("not json")
            assertTrue(Cache.load(path, Report.silent()).entries.isEmpty())
        }

    @Test
    fun aCachedLatestIsFilledInAndAnUnknownOneStaysUnchecked(): Unit =
        withTempDir { tmp ->
            val report = Report.silent()
            val cfg = Config.load(joinPath(tmp, "root"), report)
            Cache(
                entries =
                    mapOf(
                        "test:alpha#stable" to CacheEntry(found("1.2.3"), nowUnix()),
                        "test:stale#stable" to CacheEntry(found("9.9.9"), 0),
                    ),
            ).save(cachePath(cfg), report)

            val rows = merge(emptyList(), listOf(offer("alpha"), offer("beta"), offer("stale")))
            fillCached(Ctx(cfg, report), rows)
            assertEquals(listOf("1.2.3", null, null), rows.map { it.latestVersion() })
        }

    @Test
    fun aDevLookupFollowsItsOwnTagAndKeepsTheAssetsItCompares() {
        val lookup = merge(listOf(dev("2026-01-01T00:00:00Z")), emptyList())[0].lookup()!!
        assertEquals("nightly", lookup.tag)
        assertEquals("test:nvim@nightly#dev", lookup.key())
        val kept = nightly("2026-01-01T00:00:00Z", null)
        assertEquals("", kept.release.assets[0].url)
        assertEquals("a.tar.gz", kept.release.assets[0].name)
        assertTrue(found("1.0.0").release.assets.isEmpty())
    }

    @Test
    fun draftsPreAndStableLookupsAreCachedApart() {
        val rows = merge(listOf(local("alpha", "1.0.0")), listOf(offer("beta")))

        fun keys() = rows.map { it.lookup()!!.key() }
        assertEquals(listOf("test:alpha#stable", "test:beta#stable"), keys())
        includePrereleases(rows)
        assertEquals(listOf("test:alpha#pre", "test:beta#pre"), keys())
        includeDrafts(rows)
        assertEquals(listOf("test:alpha#draft", "test:beta#pre"), keys())
    }

    @Test
    fun aCacheFileOfAnOlderFormatIsSetAside() {
        val entry = """{"found":{"version":"9.9.9","tag":"v9.9.9"},"checked_at":5}"""
        assertFailsWith<IllegalArgumentException> { Cache.parse("""{"entries":{"test:alpha#stable":$entry}}""") }
        Cache.parse("""{"format":$CACHE_FORMAT,"entries":{}}""")
    }
}
