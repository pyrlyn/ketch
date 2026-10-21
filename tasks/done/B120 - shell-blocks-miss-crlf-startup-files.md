---
id: B120
title: Shell blocks miss CRLF startup files
status: done
created_at: 1792583218
updated_at: 1792583218
---

`marker_at_line_start` treated a marker as a whole line only when the next character was `\n` or the end of the file. A PowerShell profile or an autocrlf dotfile ends the line with `\r\n`, so the block was invisible: every install appended another copy, and uninstall left them all. A marker now also ends at `\r`, and `block_span` consumes the `\r\n` or `\r` that follows the end marker.

Test: `a_crlf_block_is_replaced_rather_than_appended`.
