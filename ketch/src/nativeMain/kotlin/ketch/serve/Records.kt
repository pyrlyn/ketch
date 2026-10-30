package ketch.serve

import ketch.core.changelog.Entry
import ketch.core.changelog.Origin
import ketch.core.changelog.sanitize
import ketch.core.config.Config
import ketch.core.doctor.repairable
import ketch.core.info.Info
import ketch.core.listing.Latest
import ketch.core.listing.Row
import ketch.core.model.InstalledPackage
import ketch.core.model.Manifest
import ketch.core.model.PackageKind
import ketch.core.model.SourceInfo
import ketch.core.platform.CheckStatus
import ketch.core.platform.DoctorCheck
import ketch.core.report.Report
import ketch.core.resolve.ResolutionTrace
import ketch.core.shell.Setup
import ketch.core.shell.Status
import ketch.wire.Changelog
import ketch.wire.ChangelogSource
import ketch.wire.Check
import ketch.wire.CheckOutcome
import ketch.wire.HistoryEvent
import ketch.wire.Installed
import ketch.wire.Package
import ketch.wire.PackageInfo
import ketch.wire.PathChange
import ketch.wire.PathOutcome
import ketch.wire.PathStatus
import ketch.wire.RegistryPackage
import ketch.wire.Repository
import ketch.wire.Resolution
import ketch.wire.Settings
import ketch.wire.ShellSetup
import ketch.wire.ShellState
import ketch.wire.Upgrade
import kotlinx.io.files.Path
import ketch.core.install.Installed as CoreInstalled
import ketch.core.shell.Outcome as CoreOutcome
import ketch.core.shell.ShellState as CoreShellState
import ketch.core.stats.Event as StatsEvent

/*
 * The core's values as the wire records carry them (`ketch-ffi`'s `records.rs`). Text a package's author or a source
 * wrote — descriptions, changelogs, doctor details naming their files — goes through `sanitize` on the way out, so no
 * control character reaches the app.
 */

internal fun InstalledPackage.toWire() =
    Package(
        name = name,
        version = version.toString(),
        tag = tag,
        source = source.toString(),
        pinned = pinned,
        retained = retained.map { it.version.toString() },
        installedAt = installedAt,
        prefix = prefix.toString(),
        binaries = binaries().map { it.link.toString() },
        trust = publisherTrust(),
    )

internal fun CoreInstalled.toWire() = Installed(pkg.toWire(), replaced?.toString())

internal fun registryPackage(
    manifest: Manifest,
    latest: String?,
) = RegistryPackage(
    manifest.name,
    manifest.source.toString(),
    manifest.description?.let(::sanitize),
    latest,
    isApp = manifest.kind == PackageKind.App,
)

internal fun ResolutionTrace.toWire() =
    Resolution(
        pkg = `package`,
        source = sanitize("${source.scheme}:${source.id}"),
        version = version.selected?.version?.let(::sanitize),
        asset = candidate?.name?.let(::sanitize),
        reason = candidate?.reason?.let(::sanitize),
        checksum = checksum.policy,
    )

internal fun repository(
    scheme: String,
    hit: SourceInfo,
) = Repository("$scheme:${hit.id}", hit.stars, hit.description?.let(::sanitize))

/**
 * The upgrade a listing row offers, when it offers one. A pinned package is offered one too, marked as held, so the app
 * can show what the pin keeps back; the update rules are otherwise the ones `ketch outdated` uses.
 */
internal fun upgradeOf(row: Row): Upgrade? {
    val local = row.local
    val known = row.latest as? Latest.Known
    return if (local != null && known != null && row.newer(Report.silent())) {
        val release = known.found.release
        Upgrade(local.name, local.build.version.toString(), release.version.toString(), release.tag, local.pinned, null)
    } else {
        null
    }
}

internal fun changelog(
    name: String,
    version: String,
    entry: Entry,
) = Changelog(
    name = name,
    version = version,
    source =
        when (val origin = entry.origin) {
            is Origin.File -> ChangelogSource.File(origin.path.toString())
            Origin.Release -> ChangelogSource.Release
        },
    heading = entry.heading?.let(::sanitize),
    body = sanitize(entry.body),
)

internal fun DoctorCheck.toWire() =
    Check(
        name = name,
        outcome =
            when (status) {
                CheckStatus.Ok -> CheckOutcome.Ok
                CheckStatus.Warn -> CheckOutcome.Warn
                CheckStatus.Fail -> CheckOutcome.Fail
            },
        detail = sanitize(detail),
        fix = fix?.let(::sanitize),
        fixable = repairable(this),
    )

internal fun StatsEvent.toWire() = HistoryEvent(packageName, action, version, previousVersion, tag, source, at, checksumVerified, durationMs)

internal fun Info.toWire() =
    PackageInfo(
        name = manifest.name,
        source = manifest.source.toString(),
        url = url?.let(::sanitize),
        description = description()?.let(::sanitize),
        homepage = homepage()?.let(::sanitize),
        stars = described?.stars,
        license = described?.license?.let(::sanitize),
        archived = described?.archived == true,
        latest = latest?.version?.toString(),
        latestTag = latest?.tag?.let(::sanitize),
        installed = installed?.toWire(),
    )

internal fun pathStatus(
    binDir: Path,
    status: Status,
) = PathStatus(
    binDir = binDir.toString(),
    onPath = status.onPath,
    detail = status.detail,
    userPath = status.userPath,
    shells =
        status.shells.map { row ->
            val state =
                when (row.state) {
                    CoreShellState.Configured -> ShellState.Configured
                    CoreShellState.NotSetUp -> ShellState.NotSetUp
                    CoreShellState.NotInUse -> ShellState.NotInUse
                }
            ShellSetup(row.shell.shellName, state, row.file.toString())
        },
)

private fun CoreOutcome.toWire() =
    when (this) {
        CoreOutcome.Added -> PathOutcome.Added
        CoreOutcome.Updated -> PathOutcome.Updated
        CoreOutcome.Removed -> PathOutcome.Removed
        CoreOutcome.Unchanged -> PathOutcome.Unchanged
    }

internal fun Setup.toWire() =
    when (this) {
        is Setup.ShellFile -> PathChange(change.shell.shellName, change.file.toString(), change.outcome.toWire())
        is Setup.UserPath -> PathChange("user PATH", null, outcome.toWire())
    }

internal fun Config.toWire() =
    Settings(
        root = root.toString(),
        binDir = binDir.toString(),
        storeDir = storeDir.toString(),
        appsDir = appsDir.toString(),
        configFile = configFile.toString(),
        logFile = logFile.toString(),
        registry = registry,
        target = target.toString(),
        jobs = jobs,
        prerelease = prerelease,
        allowEmulation = allowEmulation,
        linkApps = linkApps,
        requireChecksums = requireChecksums,
        stripQuarantine = stripQuarantine,
        autoUpdate = autoUpdate,
        githubToken = githubToken != null,
        logLevel = logLevel.toString(),
    )
