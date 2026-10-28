package ketch.core.install

import fs.joinPath
import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.model.InstalledPackage
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageRef
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.TargetSpec
import ketch.core.model.Version
import ketch.core.model.host
import ketch.core.report.Event
import ketch.core.report.Report
import ketch.core.report.Reporter
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal fun asset(name: String) = ReleaseAsset(name, "https://example.invalid/$name", 1)

internal fun release(vararg names: String) = Release(Version.parse("1.0.0"), "v1.0.0", assets = names.map(::asset))

internal fun tagged(
    tag: String,
    draft: Boolean,
) = Release(Version.parse(tag), tag, draft = draft)

internal fun installed(
    name: String,
    version: String,
    prefix: Path,
) = InstalledPackage(
    name = name,
    version = Version.parse(version),
    source = PackageRef.github("o/r"),
    tag = "v$version",
    target = TargetSpec.host(),
    assetName = "a.tar.gz",
    sha256 = "0".repeat(64),
    checksumVerified = true,
    installedAt = 0,
    prefix = prefix,
    origin = ManifestOrigin.Inferred,
)

/** A config rooted in a fresh temporary directory with its folders made, and that directory. */
internal fun <T> withRoot(body: (Config, Path) -> T): T =
    withTempDir { tmp ->
        val cfg = Config.load(joinPath(tmp, "ketch"), Report.silent())
        cfg.ensureDirs()
        body(cfg, tmp)
    }

/** Records every `Warn` event, so a test can assert what the user would have seen on a quiet terminal. */
@OptIn(ExperimentalAtomicApi::class)
internal class Warns : Reporter {
    private val said = AtomicReference(emptyList<String>())

    val all get() = said.load()

    override fun event(event: Event) {
        if (event is Event.Warn) said.store(said.load() + event.detail)
    }
}

/** A request to install the local [program]. */
internal fun localRequest(
    program: Path,
    cancel: Cancel = Cancel(),
) = InstallRequest(PackageSpec.parse("local:$program"), cancel = cancel)
