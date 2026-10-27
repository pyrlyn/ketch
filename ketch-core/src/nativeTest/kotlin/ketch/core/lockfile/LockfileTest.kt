package ketch.core.lockfile

import fs.readText
import ketch.core.model.Arch
import ketch.core.model.InstalledPackage
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageRef
import ketch.core.model.RetainedVersion
import ketch.core.model.TargetSpec
import ketch.core.model.Version
import ketch.core.model.host
import ketch.core.state.State
import ketch.core.testing.RUST_FIXTURES
import ketch.core.testing.Schema
import ketch.core.tomlfile.parse
import ketch.core.tomlfile.stringLiteral
import ketch.core.tomlfile.toJson
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockfileTest {
    @Test
    fun aTreeThatMatchesItsLockfileIsClean() {
        val state = stateWith(installed("fd", "sharkdp/fd", "v10.2.0"))
        val plan = plan(lockOf(state), state)
        assertTrue(plan.isClean(true))
        assertEquals(1, plan.matched)
    }

    @Test
    fun aHashThatDriftedUnderTheSameTagIsNotAMatch() {
        val lock = lockOf(stateWith(installed("fd", "sharkdp/fd", "v10.2.0")))
        val drifted = installed("fd", "sharkdp/fd", "v10.2.0").copy(sha256 = "b".repeat(64))
        val plan = plan(lock, stateWith(drifted))
        assertFalse(plan.isClean(false))
        assertEquals(0, plan.matched)
        assertEquals(1, plan.changed.size)
    }

    @Test
    fun aHashOnAnotherTargetIsNotThisMachinesPin() {
        val lock = lockOf(stateWith(installed("fd", "sharkdp/fd", "v10.2.0")))
        val elsewhere = installed("fd", "sharkdp/fd", "v10.2.0").copy(sha256 = "b".repeat(64)).onTheOtherArch()
        val plan = plan(lock, stateWith(elsewhere))
        assertTrue(plan.isClean(false))
        assertEquals(1, plan.matched)
    }

    @Test
    fun hashComparisonIsCaseInsensitive() {
        val pkg = installed("fd", "sharkdp/fd", "v10.2.0").copy(sha256 = "ab".repeat(32))
        val lock = lockOf(stateWith(pkg))
        val plan = plan(lock, stateWith(pkg.copy(sha256 = "AB".repeat(32))))
        assertTrue(plan.isClean(false))
        assertEquals(1, plan.matched)
    }

    @Test
    fun aDifferentTagIsDriftNotAMissingPackage() {
        val lock = lockOf(stateWith(installed("fd", "sharkdp/fd", "v10.2.0")))
        val plan = plan(lock, stateWith(installed("fd", "sharkdp/fd", "v10.3.0")))
        assertTrue(plan.missing.isEmpty())
        assertEquals(1, plan.changed.size)
        assertEquals("v10.3.0", plan.changed[0].second)
    }

    @Test
    fun aPackageRenamedUpstreamIsStillTheSamePackage() {
        val lock = lockOf(stateWith(installed("fd", "sharkdp/fd", "v10.2.0")))
        // Same source, installed under a different name.
        val plan = plan(lock, stateWith(installed("fd-find", "sharkdp/fd", "v10.2.0")))
        assertEquals(1, plan.matched)
        assertTrue(plan.extra.isEmpty(), plan.extra.toString())
    }

    @Test
    fun somethingInstalledThatTheLockfileOmitsIsExtra() {
        val lock = lockOf(stateWith(installed("fd", "sharkdp/fd", "v10.2.0")))
        val plan = plan(lock, stateWith(installed("fd", "sharkdp/fd", "v10.2.0"), installed("jq", "jqlang/jq", "jq-1.7")))
        assertEquals(listOf("jq"), plan.extra)
        assertTrue(plan.isClean(false), "extras alone are not drift")
        assertFalse(plan.isClean(true), "with --prune they are")
    }

    @Test
    fun aWellFormedLockfilePasses() {
        parseChecked(entry())
    }

    @Test
    fun aNameThatWouldEscapeTheStoreIsRefused() {
        assertFailsWith<Exception> { parseChecked(entry().replace("name = \"fd\"", "name = \"../../.zshrc\"")) }
    }

    @Test
    fun aSourceThatIsNotARepoIsRefused() {
        assertFailsWith<Exception> { parseChecked(entry().replace("github:sharkdp/fd", "github:../../etc")) }
    }

    @Test
    fun aHashThatIsNotAHashIsRefused() {
        assertFailsWith<Exception> { parseChecked(entry().replace("a".repeat(64), "not-a-hash")) }
    }

    @Test
    fun aPackageListedTwiceIsRefused() {
        val text = entry()
        assertFailsWith<Exception> { parseChecked(text + text.substringAfter("\n\n")) }
    }

    @Test
    fun aBinThatIsNotOneFileNameIsRefused() {
        for (bad in listOf("../../bin/sh", "sub/rtok", "..", "", "rtok" + Char(0x202E))) {
            assertFailsWith<Exception>(bad) { parseChecked(entry("bin = ${stringLiteral(bad)}\n")) }
        }
        parseChecked(entry("bin = \"rtok-cli.exe\"\n"))
    }

    private fun nightly(published: String) = installed("tool", "acme/tool", "nightly").copy(publishedAt = published)

    @Test
    fun aFloatingTagRecordsItsBuildAndNothingElseDoes() {
        val state = stateWith(nightly("2026-10-01T00:00:00Z"), installed("fd", "sharkdp/fd", "v10.2.0"))
        val text = lockOf(state).toToml()
        assertEquals(1, Regex("published = ").findAll(text).count(), text)
        assertEquals(lockOf(state).packages, parseChecked(text).packages)
    }

    @Test
    fun aFloatingTagRepublishedOnAnotherTargetIsDrift() {
        val lock = lockOf(stateWith(nightly("2026-10-01T00:00:00Z")))

        fun elsewhere(published: String) = nightly(published).copy(sha256 = "b".repeat(64)).onTheOtherArch()
        assertTrue(plan(lock, stateWith(elsewhere("2026-10-01T00:00:00Z"))).isClean(false))
        assertEquals(1, plan(lock, stateWith(elsewhere("2026-10-09T00:00:00Z"))).changed.size)
    }

    @Test
    fun aPublicationTimeATerminalCouldActOnIsRefused() {
        val esc = Char(0x1B)
        for (bad in listOf("", " ", "$esc[2J", "2026" + Char(0x202E), "2026\n")) {
            assertFailsWith<Exception>(bad) { parseChecked(entry("published = ${stringLiteral(bad)}\n")) }
        }
        parseChecked(entry("published = \"2026-10-01T00:00:00Z\"\n"))
    }

    @Test
    fun anUnknownKeyIsRefusedRatherThanIgnored() {
        assertFailsWith<Exception> { parseChecked(entry("surprise = true\n")) }
    }

    @Test
    fun aLockfileFromANewerKetchIsRefused() {
        assertFailsWith<Exception> { parseChecked(entry().replace("version = 1", "version = 99")) }
    }

    @Test
    fun anEmptyLockfileIsValidAndMeansNothingInstalled() {
        assertTrue(parseChecked("version = 1\n").packages.isEmpty())
    }

    @Test
    fun theRecordedHashOnlyAppliesToTheMachineThatWroteIt() {
        val lock = parseChecked(entry())
        assertTrue(lock.packages[0].matchesTarget("macos-aarch64"))
        assertFalse(lock.packages[0].matchesTarget("macos-x86_64"))
    }
}
