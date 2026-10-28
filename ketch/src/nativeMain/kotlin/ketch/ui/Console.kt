package ketch.ui

/** Whether [fd] (0, 1 or 2) is an interactive terminal, as Rust's `IsTerminal` tells it. */
internal expect fun isTerminal(fd: Int): Boolean

/** Columns the terminal on [fd] shows, or null when it is not one. */
internal expect fun terminalColumns(fd: Int): Int?

/**
 * Whether escape sequences written to the terminal on [fd] reach the screen as colour (Rust ui.rs `windows_vt`).
 * Legacy conhost prints `ESC[31m` as text until a program switches virtual terminal processing on for its handle;
 * when that cannot be done, plain text beats a screen full of escape codes.
 */
internal expect fun terminalTakesAnsi(fd: Int): Boolean

/** Make the console read and show what ketch writes, UTF-8, before anything is printed. */
expect fun prepareConsole()
