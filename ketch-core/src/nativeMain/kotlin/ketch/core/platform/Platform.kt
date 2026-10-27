// Per-operating-system behaviour.
//
// Everything that differs between macOS, Linux and Windows lives behind [Platform]: which release asset is even
// installable, how a payload becomes something on PATH, and what "is this code trustworthy" means locally. Adding an
// OS means adding a backend and one `actual` of [host] — no changes anywhere else.
package ketch.core.platform

import ketch.core.config.Config
import ketch.core.extra.ExtraPlacement
import ketch.core.extract.Extractor
import ketch.core.model.BinSpec
import ketch.core.model.CompletionShell
import ketch.core.model.LinkRecord
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import ketch.core.report.Report
import kotlinx.io.files.Path

/** Everything the platform needs to place an extracted payload. */
data class Placement(
    val name: String,
    val version: String,
    /** Directory holding the extracted release payload. */
    val payloadDir: Path,
    /** Final home of this version inside the store. */
    val storeDir: Path,
    val binDir: Path,
    /** macOS `.app` install root; unused on Linux. */
    val appsDir: Path,
    val kind: PackageKind,
    /** Explicit binaries from the manifest. Empty means "discover them". */
    val binSpecs: List<BinSpec>,
    /**
     * Links recorded for the version being replaced, which still exist: placement runs before the old version is
     * retired. A destination listed here is ketch's own to overwrite. Anything else occupying a destination belongs
     * to another package or to the user.
     */
    val replacing: List<LinkRecord>,
    /** Symlink `.app` bundles rather than copying them. */
    val linkApps: Boolean,
    /** Create user-visible links. False still moves the payload into the store, so `ketch relink` can expose it later without re-downloading. */
    val link: Boolean,
    /** Man pages and completions already classified and given destinations. */
    val extras: List<ExtraPlacement>,
)

/** Result of a local trust check on downloaded code. */
sealed interface TrustVerdict {
    /** Validly signed and accepted by the system policy. */
    data class Trusted(
        val authority: String,
    ) : TrustVerdict

    /** Signed, but the system would still warn (ad-hoc, or unnotarized). */
    data class Weak(
        val detail: String,
    ) : TrustVerdict

    /** No usable signature. */
    data class Untrusted(
        val detail: String,
    ) : TrustVerdict

    /** This platform does not do signature checks. */
    data object NotApplicable : TrustVerdict

    /** Whether it is safe to remove the quarantine flag without silently disabling a protection the user is relying on. */
    fun mayStripQuarantine() = this is Trusted
}

enum class CheckStatus { Ok, Warn, Fail }

/** One line of `ketch doctor` output. */
data class DoctorCheck(
    val name: String,
    val status: CheckStatus,
    val detail: String,
    val fix: String?,
) {
    companion object {
        fun ok(
            name: String,
            detail: String,
        ) = DoctorCheck(name, CheckStatus.Ok, detail, null)

        fun warn(
            name: String,
            detail: String,
            fix: String,
        ) = DoctorCheck(name, CheckStatus.Warn, detail, fix)

        fun fail(
            name: String,
            detail: String,
            fix: String,
        ) = DoctorCheck(name, CheckStatus.Fail, detail, fix)
    }
}

/** Present so `ketch doctor` can colour a summary without re-deriving it. */
fun worstStatus(checks: List<DoctorCheck>): CheckStatus =
    when {
        checks.any { it.status == CheckStatus.Fail } -> CheckStatus.Fail
        checks.any { it.status == CheckStatus.Warn } -> CheckStatus.Warn
        else -> CheckStatus.Ok
    }

/** The host operating system's rules. */
// One method per thing that differs between operating systems; splitting them would scatter a backend over several types.
@Suppress("TooManyFunctions")
interface Platform {
    /** Stable identifier, e.g. `macos`. */
    val id: String

    fun target(): TargetSpec

    /**
     * Rate an asset by file name alone.
     *
     * Null means "cannot run here" and the asset is discarded. This is the single most important function for
     * install quality: it is what stops ketch grabbing a Linux tarball or a `.sha256` sidecar.
     */
    fun scoreAsset(
        assetName: String,
        allowEmulation: Boolean,
    ): AssetScore?

    /** Extractors this platform can use, most specific first. */
    fun extractors(): List<Extractor>

    /** Move the payload into the store and create user-visible links. */
    fun place(plan: Placement): List<LinkRecord>

    /**
     * The executables [place] would discover and link in [payload] when the manifest names none, in discovery order.
     * The file named [pkg] leads. Install asks before placing, so that a choice between them is made once, by
     * `binChoice`, and not by whichever order this platform happens to sort them in.
     */
    fun binCandidates(
        payload: Path,
        kind: PackageKind,
        pkg: String,
    ): List<Path> = emptyList()

    /** Undo [place]. Must tolerate links that are already gone, and leaves a link that is no longer ketch's, saying so on [report]. */
    fun unplace(
        links: List<LinkRecord>,
        report: Report,
    )

    /** Inspect downloaded code before it is exposed to the user. */
    fun verifyTrust(path: Path): TrustVerdict = TrustVerdict.NotApplicable

    /** Clear the OS "downloaded from the internet" mark. Only called when the trust verdict allows it. */
    fun clearQuarantine(path: Path) = Unit

    /** Is this file something we can execute and link onto PATH? */
    fun isExecutable(path: Path): Boolean

    /** Files this platform treats as app bundles rather than executables. */
    fun appBundleExtension(): String? = null

    /** Environment checks for `ketch doctor`. */
    fun doctor(cfg: Config): List<DoctorCheck>

    /**
     * User-writable man root. Pages go in `manN/` underneath. `$XDG_DATA_HOME/man` or `~/.local/share/man`, not the
     * platform data directory, which on macOS is Application Support — a place `man` never looks.
     */
    fun userManRoot(): Path = Path(dataHome(), "man")

    /** Directory this shell searches for user completion scripts. */
    fun completionDir(shell: CompletionShell): Path = completionDirFor(shell)
}

/** The platform for the machine we are on. */
expect fun host(): Platform
