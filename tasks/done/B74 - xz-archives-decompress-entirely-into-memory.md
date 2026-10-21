---
id: B74
title: xz archives decompress entirely into memory
status: done
created_at: 1792583854
updated_at: 1792583854
---

`lzma-rs` decoded each XZ block into one `Vec` (`read_block` writes the block only after it has been decompressed) before tar or a lone-file extract saw a byte. A channel in front of that writer still held the whole block. `TarXzExtractor` and `XzFileExtractor` now read `lzma-rust2`'s `XzReader`, which implements `Read` and yields plaintext as it is decoded. A block whose LZMA2 dictionary plus decoder overhead would exceed 256 MiB is refused before the dictionary is allocated; stock `xz -9` uses 64 MiB and still fits. The crate's `optimization` feature (its unsafe path) stays off, and the encoder is not a dependency: tests use fixed xz fixtures. `lzma-rs` is gone.

Protocol v1 has no checksum subcommand. `docs/PLUGINS.md` (and `docs/ru`, `docs/uk`) says a plugin asset without a sha256 `digest` is trust on first use, and `require_checksums` refuses that install.

Tests: `xz_plaintext_arrives_in_the_callers_buffer`, `an_xz_dictionary_over_the_memory_limit_is_refused`, `a_tar_xz_extracts_through_the_streaming_decoder`, plus the existing lone-xz and detection cases.
