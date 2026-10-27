package ketch.core.platform

import ketch.core.config.Config
import ketch.core.extract.Extractor
import ketch.core.extract.portableExtractors
import ketch.core.model.LinkRecord
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import ketch.core.model.host
import ketch.core.report.Report
import kotlinx.io.files.Path

/**
 * Native Linux CLI releases: tar/zip assets, bin-dir symlinks, no macOS trust or app-bundle behaviour.
 *
 * Built for every native target, not only Linux, so its placement is tested on the Mac too; [host] picks it on Linux.
 */
class LinuxPlatform(
    private val target: TargetSpec = TargetSpec.host(),
) : Platform {
    override val id = "linux"

    override fun target() = target

    // Kotlin/Native's Linux targets link glibc; there is no musl build to prefer musl assets for.
    override fun scoreAsset(
        assetName: String,
        allowEmulation: Boolean,
    ) = scoreLinuxAsset(assetName, target.arch, allowEmulation, false)

    override fun extractors(): List<Extractor> = portableExtractors()

    override fun place(plan: Placement): List<LinkRecord> = placeCli(this, plan)

    override fun binCandidates(
        payload: Path,
        kind: PackageKind,
        pkg: String,
    ): List<Path> = if (kind == PackageKind.App) emptyList() else discoverExecutables(this, payload, pkg)

    override fun unplace(
        links: List<LinkRecord>,
        report: Report,
    ) = ketch.core.platform.unplace(links, report)

    override fun isExecutable(path: Path) = isUnixExecutable(path)

    override fun doctor(cfg: Config) = writableChecks(listOf("root" to cfg.root, "store" to cfg.storeDir, "bin" to cfg.binDir))
}
