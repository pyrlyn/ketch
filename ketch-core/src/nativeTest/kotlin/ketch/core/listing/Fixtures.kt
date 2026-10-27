package ketch.core.listing

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

internal fun local(
    name: String,
    version: String,
) = Local(
    name = name,
    source = PackageRef("test", name),
    build = InstalledBuild(name, Version.parse(version), "v$version", Channel.Stable, false, "a.tar.gz", "0".repeat(64), null),
    pinned = false,
    retained = emptyList(),
    prerelease = false,
    draft = false,
)

internal fun offer(name: String) = Available(name, PackageRef("test", name), "the $name tool", false)

internal fun release(tag: String) = Release(Version.parse(tag), tag)

internal fun found(version: String) = Found.of(release("v$version").copy(version = Version.parse(version)), false)

/** Whether a row for [local] offers [found] as an update. */
internal fun updateAvailable(
    local: Local,
    found: Found,
): Boolean {
    val rows = merge(listOf(local), emptyList())
    rows[0].latest = Latest.Known(found)
    return rows[0].updateAvailable()
}

internal fun names(rows: List<Row>) = rows.map { it.name }

internal fun nightly(
    publishedAt: String,
    digest: String?,
) = Found.of(
    release("nightly").copy(
        publishedAt = publishedAt,
        assets = listOf(ReleaseAsset("a.tar.gz", "https://example.invalid/a.tar.gz", 1, digest = digest?.let { Checksum("sha256", it) })),
    ),
    true,
)

internal fun dev(publishedAt: String) =
    local("nvim", "0.12.0-dev").let {
        it.copy(build = it.build.copy(tag = "nightly", channel = Channel.Dev, publishedAt = publishedAt))
    }
