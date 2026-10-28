package ketch.core.lockfile

import fs.joinPath
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

// Shared by the lockfile test classes, split only to keep each one readable.
internal fun installed(
    name: String,
    repo: String,
    tag: String,
) = InstalledPackage(
    name = name,
    version = Version.parse(tag.trimStart('v')),
    source = PackageRef.github(repo),
    tag = tag,
    target = TargetSpec.host(),
    assetName = "$name.tar.gz",
    sha256 = "a".repeat(64),
    checksumVerified = true,
    installedAt = 0,
    prefix = joinPath("/store", name),
    origin = ManifestOrigin.Inferred,
)

internal fun stateWith(vararg packages: InstalledPackage) = State().apply { packages.forEach(::insert) }

internal fun lockOf(state: State) = Lockfile.fromState(state)

internal fun parseChecked(text: String) = Lockfile.parseChecked(text, Path("ketch.lock"))

internal fun entry(overrides: String = "") =
    "version = 1\n\n[[package]]\nname = \"fd\"\nsource = \"github:sharkdp/fd\"\nversion = \"10.2.0\"\n" +
        "tag = \"v10.2.0\"\ntarget = \"macos-aarch64\"\nasset = \"fd.tar.gz\"\nsha256 = \"${"a".repeat(64)}\"\n$overrides"

internal fun InstalledPackage.onTheOtherArch() = copy(target = target.copy(arch = if (target.arch == Arch.Aarch64) Arch.X86_64 else Arch.Aarch64))
