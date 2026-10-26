package ketch.core.diff

import ketch.core.text.rustLines

/*
 * Line diffs between two texts, as `ketch registry push` shows them.
 *
 * A diff of two package files is small and needs no library: the point is a readable unified rendering of what
 * changed between the registry's copy and the local one, not a patch anyone applies. The algorithm is a
 * longest-common-subsequence table over lines, walked forward into an edit script; on a few dozen lines the
 * quadratic table costs a few kilobytes and the code stays short enough to audit in one read.
 *
 * Lines are compared as Rust's `str::lines` splits them, so `"a"` and `"a\n"` diff as equal and no
 * `\ No newline at end of file` marker is ever shown; a CRLF-versus-LF difference does not appear either.
 */

/** Context lines shown on each side of a change. */
private const val CONTEXT = 3

private enum class Kind(
    val prefix: Char,
) {
    Context(' '),
    Delete('-'),
    Insert('+'),
}

private data class Op(
    val kind: Kind,
    val line: String,
)

/**
 * A unified diff between [old] and [new], with three lines of context and no `---`/`+++` header; empty when
 * the texts are equal. Every `@@` header prints both counts even when one is 1 — GNU diff drops a `,1`, but the
 * exact shape is a rendering contract with the caller. Two changes share a hunk while at most six unchanged lines
 * run between them, so no side of a hunk shows more than three context lines.
 */
fun unified(
    old: String,
    new: String,
): String {
    val ops = script(rustLines(old), rustLines(new))
    if (ops.all { it.kind == Kind.Context }) return ""
    return render(ops)
}

private fun script(
    old: List<String>,
    new: List<String>,
): List<Op> {
    // table[i][j] is the LCS length of old[i..] and new[j..]; building it over suffixes lets the walk run forward.
    val table = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) {
        for (j in new.indices.reversed()) {
            table[i][j] = if (old[i] == new[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
    }
    val ops = ArrayList<Op>()
    var i = 0
    var j = 0
    while (i < old.size || j < new.size) {
        // A matching pair of next lines is always on some optimal path, so taking it never costs a shorter diff.
        if (i < old.size && j < new.size && old[i] == new[j]) {
            ops += Op(Kind.Context, old[i++])
            j++
        } else if (i < old.size && (j == new.size || table[i + 1][j] >= table[i][j + 1])) {
            ops += Op(Kind.Delete, old[i++])
        } else {
            ops += Op(Kind.Insert, new[j++])
        }
    }
    return deletionsFirst(ops)
}

/** Lays each changed run out as its deletions followed by its insertions, the order every diff tool shows. */
private fun deletionsFirst(ops: List<Op>): List<Op> {
    val ordered = ArrayList<Op>(ops.size)
    var i = 0
    while (i < ops.size) {
        if (ops[i].kind == Kind.Context) {
            ordered += ops[i++]
            continue
        }
        val start = i
        while (i < ops.size && ops[i].kind != Kind.Context) i++
        val run = ops.subList(start, i)
        ordered += run.filter { it.kind == Kind.Delete }
        ordered += run.filter { it.kind == Kind.Insert }
    }
    return ordered
}

private fun render(ops: List<Op>): String {
    val out = StringBuilder()
    var firstChange = nextChange(ops, 0)
    while (firstChange != null) {
        val lo = (firstChange - CONTEXT).coerceAtLeast(0)
        // A later change joins this hunk only while the unchanged run before it is short enough that the two
        // hunks' context lines would meet or overlap; once the run is longer, no later change can join.
        var lastChange: Int = firstChange
        var k = firstChange + 1
        while (k < ops.size && k - lastChange - 1 <= 2 * CONTEXT) {
            if (ops[k].kind != Kind.Context) lastChange = k
            k++
        }
        val hi = minOf(lastChange + 1 + CONTEXT, ops.size)
        emitHunk(out, ops.subList(lo, hi), ops.subList(0, lo))
        firstChange = nextChange(ops, hi)
    }
    return out.toString()
}

private fun nextChange(
    ops: List<Op>,
    from: Int,
): Int? = (from until ops.size).firstOrNull { ops[it].kind != Kind.Context }

private fun emitHunk(
    out: StringBuilder,
    hunk: List<Op>,
    before: List<Op>,
) {
    // A line of old is anything but an insertion and a line of new anything but a deletion.
    val oldCount = hunk.count { it.kind != Kind.Insert }
    val newCount = hunk.count { it.kind != Kind.Delete }
    val oldBefore = before.count { it.kind != Kind.Insert }
    val newBefore = before.count { it.kind != Kind.Delete }
    // An empty range starts at the line before it, 0 at the top of the file: "inserted after that line".
    val oldStart = if (oldCount == 0) oldBefore else oldBefore + 1
    val newStart = if (newCount == 0) newBefore else newBefore + 1
    out.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
    for (op in hunk) out.append(op.kind.prefix).append(op.line).append('\n')
}
