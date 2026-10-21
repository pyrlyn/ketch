---
id: B22
title: Log keeps bidi and zero-width characters
status: done
created_at: 1792583854
updated_at: 1792583854
---

`log::escape` drops `is_control()` characters but not the invisible formatting ones, though the file is meant to be `cat`ed.

`log::escape` now also drops bidi and zero-width formatting characters via `changelog::is_invisible`, which is now `pub(crate)` for reuse. Unit test `a_record_drops_bidi_and_zero_width_formatting` verifies the strip.
