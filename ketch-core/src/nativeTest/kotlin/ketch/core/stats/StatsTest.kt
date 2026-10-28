package ketch.core.stats

import ketch.core.model.InstalledPackage
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageRef
import ketch.core.model.TargetSpec
import ketch.core.model.Version
import ketch.core.model.host
import ketch.core.testing.withTempDir
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    private val sha = "0".repeat(64)

    private fun event(
        packageName: String,
        version: String,
        action: Action,
    ) = NewEvent(packageName, action.text, version, null, "v1", "github:o/r", "aarch64-apple-darwin", "a.tar.gz", sha, true, 100, 0, "0.1.0")

    private fun installedPackage() =
        InstalledPackage(
            name = "ripgrep",
            version = Version.parse("14.1.0"),
            source = PackageRef.github("BurntSushi/ripgrep"),
            tag = "14.1.0",
            target = TargetSpec.host(),
            assetName = "ripgrep-14.1.0.tar.gz",
            sha256 = "a".repeat(64),
            checksumVerified = false,
            installedAt = 0,
            prefix = Path("/store/ripgrep/14.1.0"),
            origin = ManifestOrigin.Inferred,
        )

    @Test
    fun aMissingDatabaseIsCreatedAndMigratedOnFirstWrite(): Unit =
        withTempDir { dir ->
            // Two levels down: the parent does not exist either.
            val path = Path(dir, "nested/stats.db")
            recordAt(path, event("ripgrep", "14.1.0", Action.Install))
            assertTrue(SystemFileSystem.exists(path), "the database should have been created")
            assertEquals(1, historyAt(path, null, 10).size)
        }

    @Test
    fun openingAnExistingDatabaseAgainDoesNotReRunMigrations(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            recordAt(path, event("ripgrep", "14.1.0", Action.Install))
            // The row from the first write must survive the second open.
            recordAt(path, event("ripgrep", "14.1.1", Action.Upgrade))
            assertEquals(2, historyAt(path, null, 10).size)
        }

    @Test
    fun historyIsNewestFirstAndScopedToOnePackage(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            listOf("1.0.0", "1.1.0", "2.0.0").forEachIndexed { i, version ->
                recordAt(path, event("ripgrep", version, Action.Upgrade).copy(at = i.toLong()))
            }
            recordAt(path, event("fd", "9.0.0", Action.Install))
            val rg = historyAt(path, "ripgrep", 10)
            assertEquals(listOf("2.0.0", "1.1.0", "1.0.0"), rg.map { it.version }, "newest first")
            assertTrue(rg.all { it.packageName == "ripgrep" }, "fd leaked in")
        }

    /** `at` has one-second resolution, so a fast upgrade ties with the install it replaced; insertion order breaks it. */
    @Test
    fun rowsWrittenInTheSameSecondStillComeBackNewestFirst(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            listOf("1.0.0", "2.0.0").forEach { recordAt(path, event("ripgrep", it, Action.Upgrade).copy(at = 42)) }
            assertEquals("2.0.0", historyAt(path, "ripgrep", 10)[0].version, "the later write should lead")
        }

    @Test
    fun theLimitIsHonoured(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            repeat(5) { recordAt(path, event("ripgrep", "1.0.0", Action.Upgrade).copy(at = it.toLong())) }
            assertEquals(2, historyAt(path, "ripgrep", 2).size)
            assertTrue(historyAt(path, "ripgrep", 0).isEmpty(), "a zero limit must return no rows")
        }

    @Test
    fun everyEventFieldRoundTripsWithoutLoss(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            val written =
                event("unicode-☃", "2.0.0-rc.1+build.7", Action.Upgrade).copy(
                    previousVersion = "1.9.0",
                    tag = "release/2.0.0-rc.1",
                    source = "plugin:forge/package",
                    target = "macos-universal",
                    assetName = "package 'quoted' universal.tar.gz",
                    sha256 = "abcdef".repeat(10) + "abcd",
                    checksumVerified = false,
                    durationMs = null,
                    at = -1,
                    ketchVersion = "9.8.7-test",
                )
            recordAt(path, written)
            val read = historyAt(path, null, 1).single()
            assertEquals(
                written,
                NewEvent(
                    read.packageName,
                    read.action,
                    read.version,
                    read.previousVersion,
                    read.tag,
                    read.source,
                    read.target,
                    read.assetName,
                    read.sha256,
                    read.checksumVerified,
                    read.durationMs,
                    read.at,
                    read.ketchVersion,
                ),
            )
        }

    @Test
    fun eventBuildersDistinguishFirstInstallReplacementAndUninstall() {
        val pkg = installedPackage()
        val source = pkg.source.toString()
        val target = pkg.target.toString()

        val first = installEvent(pkg, null, 250, "14.1.0", source, target)
        assertEquals(Action.Install.text, first.action)
        assertNull(first.previousVersion)
        assertEquals(250, first.durationMs)
        assertEquals(pkg.name, first.packageName)
        assertEquals(pkg.assetName, first.assetName)
        assertEquals(pkg.checksumVerified, first.checksumVerified)

        // Replacing a package is an upgrade even when the version moves backwards: the operation is the fact recorded.
        val replacement = installEvent(pkg, "15.0.0", 1, "14.1.0", source, target)
        assertEquals(Action.Upgrade.text, replacement.action)
        assertEquals("15.0.0", replacement.previousVersion)

        val removal = uninstallEvent(pkg, "14.1.0", source, target)
        assertEquals(Action.Uninstall.text, removal.action)
        assertNull(removal.previousVersion)
        assertNull(removal.durationMs, "removals do not affect the mean")
        assertEquals("14.1.0", removal.version)
        assertEquals(source, removal.source)
        assertEquals(target, removal.target)
    }

    @Test
    fun aSummaryCountsEachActionAndAveragesOnlyWhatWasTimed(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            recordAt(path, event("ripgrep", "1.0.0", Action.Install).copy(durationMs = 100, at = 10))
            recordAt(path, event("ripgrep", "2.0.0", Action.Upgrade).copy(durationMs = 300, at = 20))
            // An uninstall has no duration, and must not drag the mean down.
            recordAt(path, event("fd", "9.0.0", Action.Uninstall).copy(durationMs = null, at = 30))
            val s = summaryAt(path)
            assertEquals(3, s.events)
            assertEquals(1, s.installs)
            assertEquals(1, s.upgrades)
            assertEquals(1, s.uninstalls)
            assertEquals(2, s.packages, "ripgrep and fd")
            assertEquals(2, s.timed)
            assertEquals(200, s.meanDurationMs())
            assertEquals(10, s.firstAt)
            assertEquals(30, s.lastAt)
        }

    @Test
    fun anEmptyDatabaseSummarisesToZeroRatherThanFailing(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            val s = summaryAt(path)
            assertEquals(0, s.events)
            assertNull(s.meanDurationMs(), "no divisor, no mean")
            assertNull(s.firstAt)
            assertTrue(historyAt(path, null, 10).isEmpty())
            assertFalse(SystemFileSystem.exists(path), "reading must not create the database")
        }

    /** Manifests are written by other people; this one is a package name, and the only thing it may ever be is one. */
    @Test
    fun aNameThatLooksLikeSqlIsStoredAsAName(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            val hostile = "rg'); DROP TABLE events;--"
            recordAt(path, event(hostile, "1.0.0", Action.Install))
            val seen = historyAt(path, hostile, 10)
            assertEquals(1, seen.size, "the table should still be there")
            assertEquals(hostile, seen[0].packageName)
        }

    @Test
    fun aCorruptDatabaseIsReportedAndLeftUntouched(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            val corrupt = "this is not sqlite".encodeToByteArray()
            SystemFileSystem.sink(path).buffered().use { it.write(corrupt) }
            val historyError = assertFailsWith<ketch.core.error.KetchError> { historyAt(path, null, 10) }.message.orEmpty()
            val summaryError = assertFailsWith<ketch.core.error.KetchError> { summaryAt(path) }.message.orEmpty()
            assertTrue("could not" in historyError, historyError)
            assertTrue("could not" in summaryError, summaryError)
            assertContentEquals(corrupt, SystemFileSystem.source(path).buffered().use { it.readByteArray() })
        }

    /**
     * A stats.db Rust ketch made: its migration's DDL as diesel ran it, and diesel's record of it. The port reads its
     * rows and adds to them without migrating again, and a database the port made carries the same version row.
     */
    @Test
    fun aDatabaseRustKetchMadeIsReadAndExtendedAsIs(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "stats.db")
            sqlite.open(path.toString()).use { db ->
                db.exec(RUST_SCHEMA)
                db.execute(
                    "INSERT INTO events (package, action, version, tag, source, target, asset_name, sha256, checksum_verified, at, ketch_version) " +
                        "VALUES ('jq', 'install', '1.7.1', 'jq-1.7.1', 'github:jqlang/jq', 'aarch64-apple-darwin', 'jq', ?, 1, 5, '0.9.0')",
                    sha,
                )
            }
            recordAt(path, event("fd", "9.0.0", Action.Install).copy(at = 6))
            assertEquals(listOf("fd", "jq"), historyAt(path, null, 10).map { it.packageName })
            assertTrue(historyAt(path, "jq", 1).single().checksumVerified)

            val fresh = Path(dir, "fresh.db")
            recordAt(fresh, event("fd", "9.0.0", Action.Install))
            recordAt(fresh, event("fd", "9.0.1", Action.Upgrade))
            listOf(path, fresh).forEach { db ->
                val versions = sqlite.open(db.toString()).use { it.query("SELECT version FROM __diesel_schema_migrations") { r -> r.string(0) } }
                assertEquals(listOf("20260904000000"), versions, "$db")
            }
        }

    private companion object {
        /** Rust ketch's migration and diesel's bookkeeping table, as text: the other side's files, not this port's SQL. */
        const val RUST_SCHEMA = """
            CREATE TABLE IF NOT EXISTS __diesel_schema_migrations (
                version VARCHAR(50) PRIMARY KEY NOT NULL,
                run_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package TEXT NOT NULL,
                action TEXT NOT NULL,
                version TEXT NOT NULL,
                previous_version TEXT,
                tag TEXT NOT NULL,
                source TEXT NOT NULL,
                target TEXT NOT NULL,
                asset_name TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                checksum_verified BOOLEAN NOT NULL,
                duration_ms INTEGER,
                at BIGINT NOT NULL,
                ketch_version TEXT NOT NULL
            );
            CREATE INDEX events_package_at ON events (package, at DESC);
            CREATE INDEX events_at ON events (at DESC);
            INSERT INTO __diesel_schema_migrations (version) VALUES ('20260904000000');
        """
    }
}
