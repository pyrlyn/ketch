---
id: K945
title: Run the fuzz targets on a schedule
status: done
created_at: 1792603911
updated_at: 1792958210
assignee: d2a34757-1247-4b58-beb9-8a90d064e359
---

`fuzz/` has 11 targets over the trust-boundary parsers (archives, manifests, checksums), but CI only type-checks `cfg(fuzzing)`; nothing ever fuzzes.

Done when a weekly Linux job runs each target about two minutes on nightly with a cached corpus, uploads crashes and opens an issue on one.
