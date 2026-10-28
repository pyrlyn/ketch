// Releases, assets and archives the test plugin serves (Rust tests/support/mod.rs).
package ketch.e2e.support

import archive.TarWriter
import archive.ZipWriter
import archive.gzip
import fs.bytesIfExists
import ketch.core.config.HOST_WINDOWS
import ketch.core.hash.Sha256
import kotlinx.io.files.Path

/** The environment variable naming the file [Entry.sleeper] creates once it is running. */
const val SLEEPER_READY = "KETCH_TEST_SLEEPER_READY"

/** A changelog with one section per version, the same whatever version ships it, so a test can prove the section printed is the one installed. */
const val CHANGELOG = "# Changelog\n\n## [2.0.0] - 2024-06-01\n\n- the second one\n\n## [1.0.0] - 2024-05-01\n\n- the first one\n"

private const val FILE_MODE = 0x1a4
private const val PROGRAM_MODE = 0x1ed

/** One release the plugin will report. */
data class Release(
    val version: String,
    val assets: List<Asset>,
    val notes: String? = null,
    val prerelease: Boolean = false,
    val tag: String? = null,
    val publishedAt: String? = null,
) {
    /** Publish under [tag] instead of `v<version>`: a floating tag such as `nightly`. */
    fun withTag(tag: String) = copy(tag = tag)

    /** When the release was published, as the forge reports it. */
    fun published(at: String) = copy(publishedAt = at)

    /** Notes published alongside the release. */
    fun withNotes(notes: String) = copy(notes = notes)

    fun intoPrerelease() = copy(prerelease = true)

    internal fun toJson(): String {
        val notesJson = notes?.let { ""","notes":"${jsonEscape(it)}"""" } ?: ""
        val published = publishedAt?.let { ""","published_at":"${jsonEscape(it)}"""" } ?: ""
        return """{"version":"$version","tag":"${jsonEscape(tag ?: "v$version")}","prerelease":$prerelease,""" +
            """"draft":false$published$notesJson,"assets":[${assets.joinToString(",") { it.toJson() }}]}"""
    }
}

/** A fixture asset: a real file on disk, plus the digest the plugin publishes. */
data class Asset(
    val name: String,
    val path: Path,
    val digest: String?,
) {
    /** Publish a digest that does not match the bytes, so the install is rejected as a tampered download would be. */
    fun withWrongDigest() = copy(digest = "0".repeat(64))

    /** Publish no digest at all, the way a release without checksums looks. */
    fun withoutDigest() = copy(digest = null)

    // The plugin downloads, so `url` is just the path it copies from.
    internal fun toJson(): String {
        val digestJson = digest?.let { ""","digest":{"algo":"sha256","hex":"$it"}""" } ?: ""
        return """{"name":"${jsonEscape(name)}","url":"${jsonEscape(path.toString())}"$digestJson}"""
    }
}

/** One file inside a fixture archive. */
class Entry(
    val path: String,
    val body: ByteArray,
    val mode: Int,
) {
    companion object {
        fun file(
            path: String,
            body: String,
        ) = Entry(path, body.encodeToByteArray(), FILE_MODE)

        /**
         * An executable that prints [says] when run, so a test can prove the thing on PATH is what was installed. On
         * Windows it is a `.cmd`, since only the extension makes a file runnable there.
         */
        fun program(
            path: String,
            says: String,
        ) = if (HOST_WINDOWS) {
            Entry(runnable(path), "@echo off\r\necho $says\r\n".encodeToByteArray(), PROGRAM_MODE)
        } else {
            Entry(path, "#!/bin/sh\necho '$says'\n".encodeToByteArray(), PROGRAM_MODE)
        }

        /**
         * A program that stays running, so upgrade can see a process holding the file. It creates the file
         * [SLEEPER_READY] names, when set, before it starts waiting.
         */
        fun sleeper(path: String) =
            if (HOST_WINDOWS) {
                Entry(
                    runnable(path),
                    "@echo off\r\nif defined $SLEEPER_READY type nul > \"%$SLEEPER_READY%\"\r\nping -n 30 127.0.0.1 >nul\r\n".encodeToByteArray(),
                    PROGRAM_MODE,
                )
            } else {
                Entry(
                    path,
                    "#!/bin/sh\nif [ -n \"\${$SLEEPER_READY}\" ]; then : > \"\${$SLEEPER_READY}\"; fi\nsleep 30\n".encodeToByteArray(),
                    PROGRAM_MODE,
                )
            }

        /** [path] with `.cmd` added unless it already names something Windows runs. */
        private fun runnable(path: String) = if (listOf(".cmd", ".exe", ".bat").any { path.lowercase().endsWith(it) }) path else "$path.cmd"
    }
}

/** A fixture archive, in one of the formats ketch sniffs for. */
sealed class Archive {
    abstract val entries: List<Entry>

    class TarGz(
        override val entries: List<Entry>,
    ) : Archive()

    class Zip(
        override val entries: List<Entry>,
    ) : Archive()

    fun writeTo(dest: Path) =
        writeBytes(
            dest,
            when (this) {
                is TarGz -> gzip(TarWriter().apply { entries.forEach { file(it.path, it.body, it.mode) } }.finish(), level = 1)
                is Zip -> ZipWriter().apply { entries.forEach { file(it.path, it.body, it.mode) } }.finish()
            },
        )
}

fun tarGz(vararg entries: Entry) = Archive.TarGz(entries.toList())

fun zip(vararg entries: Entry) = Archive.Zip(entries.toList())

/** The archive kind a release for this host comes in: a zip on Windows, a gzipped tar elsewhere. */
fun nativeArchive(entries: List<Entry>): Archive = if (HOST_WINDOWS) Archive.Zip(entries) else Archive.TarGz(entries)

/** Where a program installed as [name] lands in the bin dir: Windows keeps a script's `.cmd`. */
fun Sandbox.binProgram(name: String): Path = bin.resolve(if (HOST_WINDOWS) "$name.cmd" else name)

/** Enough of a JSON string escape for fixture text. */
private fun jsonEscape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

fun sha256File(path: Path) = Sha256.hex(checkNotNull(bytesIfExists(path)) { "no asset at $path" })

/** The architecture token naming an asset this machine runs natively (Rust `std::env::consts::ARCH`). */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun hostArch(): String =
    when (Platform.cpuArchitecture) {
        CpuArchitecture.ARM64 -> "aarch64"
        CpuArchitecture.X64 -> "x86_64"
        else -> error("no asset arch for ${Platform.cpuArchitecture}")
    }

/** A release asset name the host's platform picks for [pkg] at [version]. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun nativeName(
    pkg: String,
    version: String,
): String =
    when (Platform.osFamily) {
        OsFamily.MACOSX -> "$pkg-$version-${hostArch()}-apple-darwin.tar.gz"
        OsFamily.WINDOWS -> "$pkg-$version-${hostArch()}-pc-windows-msvc.zip"
        else -> "$pkg-$version-${hostArch()}-unknown-linux-gnu.tar.gz"
    }
