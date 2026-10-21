---
id: B23
title: Release notes and `info --json` prose unfiltered
status: done
created_at: 1792583218
updated_at: 1792583218
---

`ketch self update` prints the release body with `ui::out`, and `ketch info --json` serialises manifest prose as it stands; serde escapes C0 but not bidi overrides.

`ui::printable` is now `pub(crate)` so command code reuses the same filter as status lines and tables. `ketch self update` passes release notes through it before `ui::out`. `ketch info --json` runs manifest, source, and asset prose through `json_prose` / `ui::printable` before serialisation. Unit test `json_prose_strips_bidi_overrides` and integration tests `info_json_keeps_bidi_out_of_the_json_it_prints` and `release_notes_are_filtered_before_they_reach_stdout` verify the strip.
