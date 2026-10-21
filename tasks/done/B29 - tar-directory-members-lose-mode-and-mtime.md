---
id: B29
title: tar directory members lose mode and mtime
status: done
created_at: 1792583854
updated_at: 1792583854
---

`EntryType::Directory` went through `walk_inside`/`create_dir` and never `entry.unpack`, so a `private/` member at 0700 landed 0755.

Directory members now use `ensure_parent` plus `entry.unpack`, matching regular files, so permissions from the tar header are applied. The `tar` crate does not restore directory mtimes on unpack (it returns before setting them); mode is covered by unit test `tar_directory_members_keep_mode`.

`NON_BINARY_TOKENS` was matched with `contains`, so `tool-1.0-macos-arm64-resources.tar.gz` was refused as source code.

`is_rejected` now uses the existing `token_at` helper for `NON_BINARY_TOKENS`, matching name/path parts instead of substrings. Replaced the `"-src-"` entry with `"src"` so source builds still reject with whole-token matching. Unit test `is_rejected_matches_non_binary_tokens_as_whole_parts` verifies `*-resources.tar.gz` is accepted while `*-sources.tar.gz` and `*-src-*` builds are rejected.
