package ketch.core.importer

import ketch.core.error.KetchError
import ketch.core.model.TargetSpec
import ketch.core.model.Version
import ketch.core.model.VersionSpec
import ketch.core.text.BYTE_ORDER

/*
 * What a `ketch import` run changes, decided before anything is touched.
 *
 * The decision is a pure function of four things: the manifest the source converts to today, the file already on
 * disk, what is installed, and the host. Keeping it pure is what lets every idempotency rule be tested without a
 * network, a ketch root or an install.
 */

/** The outcome of comparing the source with what this machine has. */
sealed interface Step {
    /** The file is current and so is the install: nothing to do. */
    data object UpToDate : Step
}

/** Writes and the install that bring this machine in line with the source. */
data class Apply(
    /** The manifest text to write; null when the file already holds it. */
    val write: String?,
    /** The release to install. */
    val version: VersionSpec,
    /** Reinstall a version that is already there, because the manifest describing it changed (another binary, another asset). */
    val force: Boolean,
    /** The exact asset the source downloads for this host, so ketch takes the same file rather than scoring the release itself. */
    val asset: String?,
    /** The checksum the source recorded for that asset. */
    val sha256: String?,
    /** The source recorded no checksum for this host's asset. */
    val unchecked: Boolean,
) : Step

/** What is installed under the imported name: its version and tag. */
data class Have(
    val version: Version,
    val tag: String,
)

/**
 * Decide what to do. [onDisk] is the current text of the manifest file, [label] names that file in errors.
 *
 * A file that does not start with the import header was written by a person, and replacing it would lose their work,
 * so it is refused rather than overwritten.
 */
fun decide(
    converted: Converted,
    rendered: String,
    onDisk: String?,
    installed: Have?,
    host: TargetSpec,
    label: String,
): Step {
    if (onDisk != null && !onDisk.startsWith(IMPORT_HEADER)) {
        throw KetchError.Msg("$label was not written by `ketch import`, so it is left as it is; move it away to import `${converted.name}`")
    }
    // Refused before the file is written: a manifest this machine cannot install from would leave a package behind
    // that `ketch install` fails on.
    val artifact =
        converted.artifactFor(host) ?: throw KetchError.Msg(
            "the ${converted.backend} definition of `${converted.packageId}` has no download for $host, " +
                "only for ${converted.artifacts.keys.sortedWith(BYTE_ORDER).joinToString(", ")}",
        )

    val changed = onDisk != rendered
    val write = rendered.takeIf { changed }
    val wanted = Version.parse(converted.tag ?: converted.version)

    val have = installed?.version
    return when {
        have != null && !changed && have >= wanted -> {
            Step.UpToDate
        }

        // Newer than the catalogue: the catalogue is behind, not the user, so the installed release stays and only
        // the manifest is redone.
        have != null && have > wanted -> {
            Apply(write, VersionSpec.Exact(checkNotNull(installed).tag), force = true, asset = null, sha256 = null, unchecked = false)
        }

        else -> {
            Apply(
                write = write,
                version = converted.tag?.let { VersionSpec.Exact(it) } ?: VersionSpec.Latest,
                force = changed && have == wanted,
                asset = artifact.file,
                // A `latest/download` URL follows the repository, so a checksum the catalogue took from an older
                // release would not match.
                sha256 = artifact.sha256.takeIf { converted.tag != null },
                unchecked = artifact.sha256 == null,
            )
        }
    }
}
