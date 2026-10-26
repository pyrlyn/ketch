package ketch.core.binchoice

import ketch.core.error.KetchError

/*
 * Which binary a package links when its manifest names none.
 *
 * Inference links every executable it discovers, which is right until a release ships several that share the
 * package's name — `rtok` beside `rtok-hook`. Then one is the command and the rest are helpers, and nothing in the
 * payload says which. Discovery order cannot say either: `rtok-hook.exe` sorts ahead of `rtok.exe` while `rtok`
 * sorts ahead of `rtok-hook` (B64). So the decision lives here, once: the binary `--bin` names, else the exact
 * package name, else the choice remembered from last time (state's, then the lockfile's), else the user's answer,
 * else an error that says how to name the binary in a manifest.
 */

/** How a binary came to be chosen. */
enum class Picked {
    /** `--bin` named it: the user's answer, given before the question. */
    Flag,

    /** Its name is the package's name. */
    Exact,

    /** It was chosen for this package before, and state or the lockfile remembered it. */
    Remembered,

    /** The user picked it just now. */
    Asked,
}

/** What is known about a choice before anybody is asked. */
data class Known(
    /** `--bin`: the user's answer, given up front. Wins over everything. */
    val flag: String? = null,
    /** Earlier choices, most trusted first: state's, then the lockfile's. */
    val remembered: List<String> = emptyList(),
)

/** Compare-ready form of an executable's file name: lowercase, without the extension Windows needs to run it. */
fun stem(fileName: String): String = withoutExtension(fileName, listOf(".exe", ".cmd", ".bat")).asciiLowercase()

/**
 * The name a `bin` entry records for [fileName], case kept: without `.exe`, which Windows adds back when it links.
 * A `.cmd` or `.bat` keeps its extension, because linked under a bare name Windows would call it `.exe`.
 */
fun commandName(fileName: String): String = withoutExtension(fileName, listOf(".exe"))

/** [fileName] without the first of [extensions] it ends with, ignoring case; a bare extension is kept whole. */
private fun withoutExtension(
    fileName: String,
    extensions: List<String>,
): String {
    val lower = fileName.asciiLowercase()
    for (ext in extensions) {
        if (lower.endsWith(ext) && lower.length > ext.length) return fileName.substring(0, fileName.length - ext.length)
    }
    return fileName
}

/** Rust's `to_ascii_lowercase`: only A–Z fold, so lengths and offsets never change. */
fun String.asciiLowercase(): String = buildString(length) { for (c in this@asciiLowercase) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }

/**
 * True when [fileName] belongs to the package's family: its stem is the package name, or the name followed by
 * `-`, `_` or `.`. The separator is required so that `go` and `gofmt`, two commands, keep being linked together.
 */
fun sharesName(
    pkg: String,
    fileName: String,
): Boolean {
    val name = pkg.asciiLowercase()
    val stem = stem(fileName)
    if (!stem.startsWith(name)) return false
    val rest = stem.substring(name.length)
    return rest.isEmpty() || rest[0] in "-_."
}

/** Indices of the discovered files that compete for the package's name, when there are at least two. */
fun contenders(
    pkg: String,
    files: List<String>,
): List<Int> {
    val family = files.indices.filter { sharesName(pkg, files[it]) }
    return if (family.size < 2) emptyList() else family
}

/**
 * Choose one of [candidates] for [pkg], in the fixed order: the binary `--bin` names (an error when it names none
 * of them), the exact package name, the first remembered choice still among the candidates, the answer [ask]
 * returns (null when it cannot ask), and otherwise an error naming every candidate. [manifestHint] is where the
 * error tells the user to write `bin`.
 */
fun choose(
    pkg: String,
    candidates: List<String>,
    known: Known,
    ask: (List<String>) -> Int?,
    manifestHint: String,
): Pair<Int, Picked> {
    known.flag?.let { return pickFlag(it, candidates) to Picked.Flag }
    val pkgStem = pkg.asciiLowercase()
    val picked =
        indexOfStem(candidates, pkgStem)?.let { it to Picked.Exact }
            ?: known.remembered.firstNotNullOfOrNull { indexOfStem(candidates, stem(it)) }?.let { it to Picked.Remembered }
            ?: ask(candidates)?.takeIf { it in candidates.indices }?.let { it to Picked.Asked }
    return picked ?: throw ambiguous(pkg, candidates, manifestHint)
}

/** The candidate `--bin` names; an error when it names none of them. */
private fun pickFlag(
    flag: String,
    candidates: List<String>,
): Int = indexOfStem(candidates, stem(flag)) ?: throw unknownFlag(flag, candidates)

private fun indexOfStem(
    candidates: List<String>,
    wanted: String,
): Int? = candidates.indexOfFirst { stem(it) == wanted }.takeIf { it >= 0 }

/**
 * Check a `--bin` that had nothing to choose between against every executable discovered, so a typo is reported
 * rather than silently ignored.
 */
fun checkFlag(
    flag: String,
    discovered: List<String>,
) {
    val wanted = stem(flag)
    if (discovered.none { stem(it) == wanted }) throw unknownFlag(flag, discovered)
}

private fun unknownFlag(
    flag: String,
    candidates: List<String>,
): KetchError {
    val listed = if (candidates.isEmpty()) "none were found" else "the candidates are ${candidates.joinToString(", ")}"
    return KetchError.Msg("--bin `$flag` names no binary in this release; $listed")
}

private fun ambiguous(
    pkg: String,
    candidates: List<String>,
    manifestHint: String,
): KetchError {
    val first = candidates.firstOrNull()?.let(::stem).orEmpty()
    return KetchError.Msg(
        "`$pkg` ships several binaries sharing its name (${candidates.joinToString(", ")}) and none is " +
            "named `$pkg`; pass `--bin $first`, run the command in a terminal " +
            "to pick one, or name it in a manifest: `bin = [{ name = \"$first\" }]` " +
            "in $manifestHint",
    )
}
