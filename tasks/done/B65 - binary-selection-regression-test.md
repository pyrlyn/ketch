---
id: B65
title: Binary selection regression test
status: done
created_at: 1792583218
updated_at: 1792583218
priority: 0
---

Add a fixture with two similarly named binaries (for example `rtok` and `rtok-hook`) and assert the intended one is chosen on every OS: macOS, Windows and Linux. This is the test that would have caught the Windows alphabetical-sort bug, where `rtok hook` was selected instead of the intended binary.

Note for the owner: B64's branch `b64-bin-name` already adds `tests/bin_choice.rs` with `rtok`, `rtok-hook` and `other-tool` fixtures, not gated by OS. Reuse or extend it once B64 merges instead of writing a second fixture.

Status: done. `tests/bin_choice.rs` installs a release that ships `rtok` and `rtok-hook` and links only `rtok`. The test is not gated by OS, so CI runs it on macOS, Linux and Windows. `bin_choice.rs` covers the same choice when the names are sorted the Windows way (`rtok-hook.exe` before `rtok.exe`) and the Unix way.
