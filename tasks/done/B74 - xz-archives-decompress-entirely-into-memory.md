---
id: B74
title: xz archives decompress entirely into memory
status: done
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

`lzma-rs` decoded each XZ block into one `Vec` before any plaintext was written (`decode::xz::read_block`), so a channel adapter around `xz_decompress` still held a hostile multi-GB block in memory. Done: `TarXzExtractor` and `XzFileExtractor` read through `lzma-rust2`'s `XzReader`, which yields plaintext as it is decoded, and refuse a block whose LZMA2 dictionary would take more than 256 MiB of decoder memory. `docs/PLUGINS.md` states that protocol v1 has no checksum subcommand, so a plugin asset without a sha256 `digest` is trust on first use.
