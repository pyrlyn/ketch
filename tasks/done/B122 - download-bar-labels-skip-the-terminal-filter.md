---
id: B122
title: Download bar labels skip the terminal filter
status: done
created_at: 1792583854
updated_at: 1792583854
---

`BarProgress::start` put the asset name on the download bar with `truncate` and no `printable`. Every other status line runs client text through `changelog::sanitize` first, which drops escapes and bidi overrides. The bar now filters the label, including one stored for a batch, and then truncates it. Callers that already pass a clean name are unchanged.

Test: `a_download_bar_label_cannot_redraw_the_terminal`.
