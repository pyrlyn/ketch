// Aligned tables on stdout, from ui.rs.

package ketch.ui

import platform.posix.STDOUT_FILENO

/** A cell painter for [tableStyled]: row index, column index and the cell's filtered text; returns what to print. */
typealias CellPaint = (row: Int, col: Int, cell: String) -> String

/** Rows as an aligned table. Empty input prints nothing. */
fun table(
    headers: List<String>,
    rows: List<List<String>>,
) = tableLines(headers, rows).forEach(::out)

/**
 * [table], with colour. Cells are filtered and measured first and painted after, so the escape sequences the painter
 * adds neither get filtered out nor count towards a column's width.
 */
fun tableStyled(
    headers: List<String>,
    rows: List<List<String>>,
    paint: CellPaint,
) = styledTableLines(headers, rows, paint).forEach(::out)

/**
 * Columns stdout can use before a line wraps, or null when it is not a terminal and nothing should be cut. `COLUMNS`
 * wins when set, as it does for most tools, so a script or a test can ask for a width of its own.
 */
fun stdoutWidth(): Int? = env("COLUMNS")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: terminalColumns(STDOUT_FILENO)

/** The table's lines, built rather than printed so a test can read them. */
internal fun tableLines(
    headers: List<String>,
    rows: List<List<String>>,
): List<String> = styledTableLines(headers, rows) { _, _, cell -> cell }

/**
 * Cells carry client-app text — asset names, descriptions, package files — so they are filtered here, and measured
 * after filtering: an escape sequence counted as printable width would push every later column out of line.
 */
internal fun styledTableLines(
    headers: List<String>,
    rows: List<List<String>>,
    paint: CellPaint,
): List<String> {
    if (rows.isEmpty()) return emptyList()
    val cells = rows.map { row -> row.take(headers.size).map { foldLine(printable(it)) } }
    val widths = headers.map(::chars).toMutableList()
    cells.forEach { row -> row.forEachIndexed { i, cell -> widths[i] = maxOf(widths[i], chars(cell)) } }

    fun padded(
        line: List<String>,
        paint: (Int, String) -> String,
    ): String =
        line
            .mapIndexed { i, c -> paint(i, c) + " ".repeat((widths[i] - chars(c)).coerceAtLeast(0)) }
            .joinToString("  ")
            .trimEnd()

    return listOf(bold(padded(headers) { _, c -> c })) + cells.mapIndexed { r, row -> padded(row) { i, c -> paint(r, i, c) } }
}

/** Rust's `chars().count()`: code points, so a surrogate pair is one. */
private fun chars(text: String): Int = text.count { !it.isLowSurrogate() }
