// macOS.
//
// Asset scoring understands Apple's naming conventions (`darwin`, `apple`, `universal`, `arm64` vs `aarch64`),
// placement knows the difference between a CLI binary and a `.app` bundle, and trust checks run `codesign`/`spctl`
// before any quarantine flag is cleared.
package ketch.core.platform.macos

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.joinPath
import fs.walk
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.error.ioAt
import ketch.core.extract.Extractor
import ketch.core.extract.isBundleName
import ketch.core.extract.macos.DmgExtractor
import ketch.core.extract.macos.PkgExtractor
import ketch.core.extract.macos.TOOL_OUTPUT
import ketch.core.extract.macos.TOOL_TIMEOUT
import ketch.core.extract.macos.copyTree
import ketch.core.extract.macos.tryTool
import ketch.core.extract.portableExtractors
import ketch.core.model.Arch
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.LinkRole
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import ketch.core.model.host
import ketch.core.model.rustComponents
import ketch.core.platform.Claims
import ketch.core.platform.DoctorCheck
import ketch.core.platform.Placement
import ketch.core.platform.Platform
import ketch.core.platform.TrustVerdict
import ketch.core.platform.binaryTargets
import ketch.core.platform.claimDestination
import ketch.core.platform.clearDestination
import ketch.core.platform.discoverExecutables
import ketch.core.platform.isUnixExecutable
import ketch.core.platform.linkBinary
import ketch.core.platform.placeIntoStore
import ketch.core.platform.preflightExtras
import ketch.core.platform.scoreMacosAsset
import ketch.core.platform.swapIntoStore
import ketch.core.platform.symlink
import ketch.core.platform.writableChecks
import ketch.core.report.Report
import kotlinx.io.files.Path
import process.Command
import process.ProcessException
import process.runBounded

private const val CODESIGN = "/usr/bin/codesign"
private const val SPCTL = "/usr/sbin/spctl"
private const val XATTR = "/usr/bin/xattr"
private const val AUTHORITY = "Authority="
private val DOCTOR_TOOLS = listOf(CODESIGN, XATTR, "/usr/bin/hdiutil", "/usr/bin/ditto")
private val ROSETTA = listOf("/usr/libexec/rosetta/oahd", "/Library/Apple/usr/share/rosetta")

/** How deep under the payload an `.app` bundle may sit. */
private const val BUNDLE_DEPTH = 3

class MacOsPlatform(
    private val target: TargetSpec = TargetSpec.host(),
) : Platform {
    override val id = "macos"

    override fun target() = target

    override fun scoreAsset(
        assetName: String,
        allowEmulation: Boolean,
    ) = scoreMacosAsset(assetName, target.arch, allowEmulation)

    override fun extractors(): List<Extractor> = listOf(DmgExtractor, PkgExtractor) + portableExtractors()

    // The same rule `place` applies: an app bundle carries its own executables, so an `auto` payload holding one
    // links none.
    override fun binCandidates(
        payload: Path,
        kind: PackageKind,
        pkg: String,
    ): List<Path> = if (wantBinaries(kind) { findAppBundles(payload).isEmpty() }) discoverExecutables(this, payload, pkg) else emptyList()

    override fun place(plan: Placement): List<LinkRecord> =
        placeIntoStore(plan, ::copyTree, preflight = { preflightDestinations(this, plan, it) }) { owned, claims, links ->
            if (plan.kind != PackageKind.Binary) {
                for (bundle in findAppBundles(plan.storeDir)) {
                    links += placeApp(bundle, plan.appsDir, plan.linkApps, owned, plan.replacing, claims)
                }
            }
            // An app bundle carries its own executables; do not also scatter them across PATH.
            if (wantBinaries(plan.kind) { links.isEmpty() }) {
                for ((target, name) in binaryTargets(this, plan.storeDir, plan)) {
                    links += linkBinary(target, plan.binDir, name, owned, plan.replacing, claims)
                }
            }
        }

    override fun unplace(
        links: List<LinkRecord>,
        report: Report,
    ) = ketch.core.platform.unplace(links, report)

    override fun verifyTrust(path: Path): TrustVerdict {
        if (!exists(CODESIGN)) return TrustVerdict.NotApplicable
        val (valid, detail) = capture(CODESIGN, listOf("--verify", "--strict", "--", path.toString()))
        return if (valid) signedVerdict(path) else TrustVerdict.Untrusted(firstLine(detail))
    }

    // Non-zero simply means the attribute was not there.
    override fun clearQuarantine(path: Path) {
        tryTool(XATTR, listOf("-r", "-d", "com.apple.quarantine", path.toString()))
    }

    override fun isExecutable(path: Path) = isUnixExecutable(path)

    override fun appBundleExtension() = ".app"

    override fun doctor(cfg: Config): List<DoctorCheck> {
        val checks = writableChecks(listOf("root" to cfg.root, "store" to cfg.storeDir)).toMutableList()
        for (tool in DOCTOR_TOOLS) {
            checks +=
                if (exists(tool)) {
                    DoctorCheck.ok(tool, "present")
                } else {
                    DoctorCheck.warn(tool, "missing", "install the Command Line Tools: xcode-select --install")
                }
        }
        if (target.arch == Arch.Aarch64) {
            checks +=
                if (ROSETTA.any(::exists)) {
                    DoctorCheck.ok("rosetta", "installed — x86_64-only releases will run")
                } else {
                    DoctorCheck.warn(
                        "rosetta",
                        "not installed — x86_64-only releases will not run",
                        "softwareupdate --install-rosetta --agree-to-license",
                    )
                }
        }
        return checks
    }
}

private inline fun wantBinaries(
    kind: PackageKind,
    auto: () -> Boolean,
) = when (kind) {
    PackageKind.App -> false
    PackageKind.Binary -> true
    PackageKind.Auto -> auto()
}

/** What a valid signature on [path] is worth. */
private fun signedVerdict(path: Path): TrustVerdict {
    val info = capture(CODESIGN, listOf("-dv", "--verbose=4", "--", path.toString())).second
    val authority =
        info
            .lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith(AUTHORITY) }
            ?.removePrefix(AUTHORITY)
    return when {
        // Valid but ad-hoc: the signature proves nothing about origin.
        authority == null -> {
            TrustVerdict.Weak("ad-hoc signature, no signing authority")
        }

        !authority.startsWith("Developer ID") -> {
            TrustVerdict.Weak("signed by $authority, which is not a distribution identity")
        }

        else -> {
            // Only a notarized binary passes system policy; a Developer ID signature on its own does not.
            val (accepted, why) = capture(SPCTL, listOf("--assess", "--type", "exec", "--", path.toString()))
            if (accepted) TrustVerdict.Trusted(authority) else TrustVerdict.Weak("$authority; system policy: ${firstLine(why)}")
        }
    }
}

private fun exists(path: String) = entryKind(Path(path)) != null

/**
 * Run a tool and capture stdout and stderr together.
 *
 * `codesign` reports everything interesting on stderr, so splitting the two would just mean reassembling them at
 * every call site.
 */
private fun capture(
    program: String,
    args: List<String>,
): Pair<Boolean, String> =
    try {
        val out = Command(program, args).runBounded(TOOL_TIMEOUT, TOOL_OUTPUT)
        out.status.success to out.stdout.decodeToString() + out.stderr.decodeToString()
    } catch (e: ProcessException) {
        false to e.toString()
    }

internal fun firstLine(text: String): String = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "no output"

/** `ditto` is what copies a payload when the cache and the store differ. */
internal fun moveIntoStore(
    payload: Path,
    store: Path,
) = swapIntoStore(payload, store, ::copyTree)

/**
 * True when [path] sits inside *another* bundle — a helper app nested in the one being installed, which must not be
 * placed in the applications directory on its own.
 *
 * Only the ancestors below [root] count. Testing the whole relative path includes the leaf, and since every `.app`
 * ends in `.app`, that answered "inside a bundle" for every bundle there is.
 */
internal fun isInsideBundle(
    path: Path,
    root: Path,
): Boolean {
    val parts = rustComponents(path)
    val rootParts = rustComponents(root)
    if (parts.take(rootParts.size) != rootParts) return false
    return parts.drop(rootParts.size).dropLast(1).any(::isBundleName)
}

internal fun findAppBundles(root: Path): List<Path> =
    walk(root, BUNDLE_DEPTH, onError = {})
        .filter { it.kind == EntryKind.Dir && it.path.name.endsWith(".app") && it.path.name != ".app" }
        .map { it.path }
        .filter { !isInsideBundle(it, root) }
        .toList()

internal fun preflightDestinations(
    platform: Platform,
    plan: Placement,
    owned: Path,
) {
    val bundles = if (plan.kind != PackageKind.Binary) findAppBundles(plan.payloadDir) else emptyList()
    val binaries = if (wantBinaries(plan.kind) { bundles.isEmpty() }) binaryTargets(platform, plan.payloadDir, plan) else emptyList()
    val destinations = mutableSetOf<String>()
    for (bundle in bundles) claimDestination(destinations, joinPath(plan.appsDir, bundle.name), owned, plan)
    for ((_, name) in binaries) claimDestination(destinations, joinPath(plan.binDir, name), owned, plan)
    preflightExtras(plan, owned)
    for (extra in plan.extras) {
        if (!destinations.add(extra.dest.toString())) throw KetchError.Msg("multiple payload entries want to create ${extra.dest}")
    }
    if (destinations.isEmpty()) throw KetchError.EmptyPayload(plan.payloadDir)
}

internal fun placeApp(
    bundle: Path,
    appsDir: Path,
    linkApps: Boolean,
    owned: Path,
    recorded: List<LinkRecord>,
    claims: Claims,
): LinkRecord {
    ioAt(appsDir) { createDirAll(appsDir) }
    val link = joinPath(appsDir, bundle.name)
    clearDestination(link, owned, recorded, claims)
    if (linkApps) {
        symlink(bundle, link)
        return LinkRecord(link, bundle, LinkKind.LinkedApp, LinkRole.Binary)
    }
    // Copied by default: Launchpad and Spotlight both ignore symlinked apps.
    copyTree(bundle, link)
    return LinkRecord(link, bundle, LinkKind.CopiedApp, LinkRole.Binary)
}
