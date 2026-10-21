---
id: R12
title: Consolidate duplicated helpers
status: done
created_at: 1792583854
updated_at: 1792583854
---

`sibling` lived in three backends. Linux and Windows lossily rebuilt the file name and fell back to `pkg`; macOS appended the suffix as bytes and used an empty name when the path had none. One function in `platform/mod.rs` does both: bytes, so a non-UTF-8 name stays intact, and `pkg` when there is no file name. Store swaps still rename `.incoming` and `.old` beside the live directory.

`remove_any` was already `fs_kit::tree::remove_any`. `Http::has_token` is called before a GitHub draft listing, so the unused-helper note was stale and the method stays.

The local source redeclared the gzip, xz, bzip2, zip and ustar checks. Those constants and `looks_like_tar` are now `pub(crate)` on the extractors, and `looks_like_archive` uses them. It still treats a lone compressed file as an archive and does not open the stream to see whether tar follows.

Tests: `sibling_appends_the_suffix_to_the_file_name`, `sibling_of_a_nameless_path_uses_pkg`, `sibling_keeps_a_non_utf8_file_name`, `archive_bytes_match_the_extractor_magics`.
