package ketch.core.extract

import ketch.core.config.HOST_WINDOWS
import ketch.core.config.windowsUnusableComponent
import ketch.core.error.KetchError

/**
 * Reject archive member paths that would write outside the destination: the guard against "zip slip" and tar
 * traversal, so a member named `../../../.zshrc` is never honoured, whoever published it. Absolute paths and `..`
 * components are refused outright; the result is a relative `/`-separated path safe to join onto the destination.
 * On Windows a component that names a device or loses a trailing dot or space is refused too.
 */
fun safeMemberPath(raw: String): String = safeMemberPathFor(raw, HOST_WINDOWS)

private val DRIVE = Regex("^[A-Za-z]:")

/** [safeMemberPath] with the Windows rules switched on or off, so both are tested on every host. */
internal fun safeMemberPathFor(
    raw: String,
    windows: Boolean,
): String {
    val parts = raw.split('/')
    val safe = parts.filter { it.isNotEmpty() && it != "." }
    val refusal =
        when {
            // On Windows a backslash is a separator, so `a\b` would otherwise become the ordinary relative path
            // `a/b` and slip through. Archive member names must never carry one.
            '\\' in raw -> unsafeName(raw)

            // A Windows drive (`C:x`, `C:/x`) is a root even where the host reads it as an ordinary name.
            raw.startsWith('/') || DRIVE.containsMatchIn(raw) || ".." in parts -> escapes(raw)

            // NTFS stream and drive-relative crumbs that are not a full root.
            safe.any { ':' in it || (windows && windowsUnusableComponent(it)) } -> unsafeName(raw)

            safe.isEmpty() -> KetchError.Msg("refusing archive entry with an empty name")

            else -> null
        }
    if (refusal != null) throw refusal
    return safe.joinToString("/")
}

private fun unsafeName(raw: String) = KetchError.Msg("refusing archive entry with unsafe name: $raw")

private fun escapes(raw: String) = KetchError.Msg("refusing archive entry that escapes the target directory: $raw")
