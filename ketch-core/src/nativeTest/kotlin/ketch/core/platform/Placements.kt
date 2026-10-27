package ketch.core.platform

import ketch.core.extra.ExtraPlacement
import ketch.core.model.LinkRecord
import ketch.core.model.PackageKind
import kotlinx.io.files.Path

/** A linking placement of [payload] into [store], with Rust's test defaults for everything else. */
internal fun placement(
    payload: Path,
    store: Path,
    bin: Path,
    apps: Path,
    name: String = "rg",
    version: String = "1.0",
    kind: PackageKind = PackageKind.Binary,
    replacing: List<LinkRecord> = emptyList(),
    extras: List<ExtraPlacement> = emptyList(),
) = Placement(
    name = name,
    version = version,
    payloadDir = payload,
    storeDir = store,
    binDir = bin,
    appsDir = apps,
    kind = kind,
    binSpecs = emptyList(),
    replacing = replacing,
    linkApps = false,
    link = true,
    extras = extras,
)
