// What is installed, on disk.
//
// `state.json` is the only durable record ketch keeps. It is rewritten atomically — staged next to the real file and
// renamed — so an interrupted write can never leave a half-parsed state file behind, which would look exactly like
// "nothing is installed".
//
// Version 1 stays readable when fields are added with defaults. A file written before retention existed loads as
// keep-1 with an empty retained list per package. Upgrade never deletes an old prefix; `ketch prune` is the only
// command that does.
package ketch.core.state

import fs.FsException
import fs.readText
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.fsio.writePrivate
import ketch.core.model.InstalledPackage
import ketch.core.model.PackageSpec
import ketch.core.model.RetentionPolicy
import ketch.core.text.BYTE_ORDER
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.rustTrim
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** Bumped only when the on-disk shape changes incompatibly. */
const val STATE_VERSION = 1u

/**
 * Laid out as Rust's `serde_json::to_string_pretty` writes it, so the file reads the same whichever ketch wrote it.
 * Unknown keys are skipped type by type (`@JsonIgnoreUnknownKeys`), as serde skips them in a struct that does not deny
 * them; an embedded manifest still refuses them.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val STATE_JSON =
    Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

/** The file's shape; `version` has no default, so a file without one is refused as Rust refuses it. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
private class StateFile(
    val version: UInt,
    val retention: RetentionPolicy = RetentionPolicy(),
    val packages: Map<String, InstalledPackage> = emptyMap(),
)

class State(
    val version: UInt = STATE_VERSION,
    /** How many previous prefixes `ketch prune` leaves. Upgrade never prunes. */
    var retention: RetentionPolicy = RetentionPolicy(),
    packages: Map<String, InstalledPackage> = emptyMap(),
) {
    /** Keyed by package name, which is unique across sources by construction. */
    private val byName = LinkedHashMap(packages)

    /** Every package in name order (Rust `BTreeMap`), which the state file, `list` and the lockfile rely on. */
    val packages: Map<String, InstalledPackage>
        get() = byName.keys.sortedWith(BYTE_ORDER).associateWith { byName.getValue(it) }

    fun save(cfg: Config) = savePath(cfg.stateFile)

    fun savePath(path: Path) {
        val json =
            try {
                STATE_JSON.encodeToString(StateFile(version, retention, packages))
            } catch (e: SerializationException) {
                throw KetchError.Parse("state", e.message.orEmpty(), e)
            }
        // Durable by default in fs-kit: without the fsync the kernel is free to record the rename and lose the
        // bytes, leaving a zero-length state file, which is to say an empty list of installed packages.
        writePrivate(path, "$json\n".encodeToByteArray())
    }

    /** A copy that later changes to this one do not reach (Rust `State::clone`). */
    fun snapshot() = State(version, retention, byName)

    fun get(name: String): InstalledPackage? = byName[name]

    fun insert(pkg: InstalledPackage) {
        byName[pkg.name] = pkg
    }

    fun remove(name: String): InstalledPackage? = byName.remove(name)

    fun names(): List<String> = packages.keys.toList()

    fun iter(): List<InstalledPackage> = packages.values.toList()

    /** Look a package up the way a user would name it: by install name, by a binary it provides, or by its source id (`owner/repo`). */
    fun find(query: String): InstalledPackage? =
        byName[query] ?: iter().firstOrNull { p ->
            p.source.id.eqIgnoreAsciiCase(query) ||
                p.source.toString().eqIgnoreAsciiCase(query) ||
                p.binaries().any { it.link.name.isNotEmpty() && it.link.name == query }
        }

    /**
     * Look up the package [spec] names. `pkg@version` is not a state key, so this falls back to its alias and its
     * source ref, which lets a request for another version still find the payload on disk.
     */
    fun findSpec(spec: PackageSpec): InstalledPackage? =
        find(spec.raw)
            ?: spec.alias?.let(::find)
            ?: spec.reference?.let { find(it.toString()) ?: find(it.id) }

    companion object {
        /** Read the state file. A missing file is an empty state, not an error. */
        fun load(cfg: Config) = loadPath(cfg.stateFile)

        fun loadPath(path: Path): State {
            val text =
                try {
                    readText(path)
                } catch (e: FsException) {
                    if (e.kind == FsException.Kind.NotFound) return State()
                    throw KetchError.fsKit(path, e)
                }
            val file = decode(path, text)
            if (file.version > STATE_VERSION) {
                throw KetchError.Msg(
                    "$path was written by a newer ketch (state version ${file.version}); upgrade with `ketch self upgrade`",
                )
            }
            return State(file.version, file.retention, file.packages)
        }

        private fun decode(
            path: Path,
            text: String,
        ): StateFile {
            // An absent file means nothing has been installed yet. An empty one means a write was lost, and every
            // package on disk is about to be forgotten — say so rather than quietly starting over.
            if (text.rustTrim().isEmpty()) {
                throw KetchError.Msg(
                    "$path is empty, which usually means an interrupted write. Anything already installed is still " +
                        "in the store; remove the file to start a fresh record, then `ketch relink` each package.",
                )
            }
            return try {
                STATE_JSON.decodeFromString<StateFile>(text)
            } catch (e: IllegalArgumentException) {
                // A SerializationException, or a value a model type refuses while it is decoded.
                throw KetchError.Parse(path.toString(), e.message.orEmpty(), e)
            }
        }
    }
}
