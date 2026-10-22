---
id: K945
title: Run the fuzz targets on a schedule
status: open
created_at: 1792603911
updated_at: 1792603911
---

`fuzz/` has 11 targets over the trust-boundary parsers (archives, manifests, checksums), but CI only type-checks `cfg(fuzzing)`; nothing ever fuzzes.

Done when a weekly Linux job runs each target about two minutes on nightly with a cached corpus, uploads crashes and opens an issue on one.
