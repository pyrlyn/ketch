---
id: B65
title: Binary selection regression test
status: done
created_at: 1792583854
updated_at: 1792583854
---

Add a fixture with two similarly named binaries (`rtok` and `rtok-hook`) and assert the intended one is chosen on every OS: macOS, Windows and Linux. This is the test that would have caught the Windows alphabetical-sort bug, where `rtok-hook` was selected instead of the intended binary.

B64's `tests/bin_choice.rs` already linked `rtok` beside `rtok-hook` on every OS, but each host shipped its own names, so only the Windows job saw the order that broke: `rtok-hook.exe` sorts before `rtok.exe`. With discovery reverted to a plain sort and the chooser taking the first candidate, that test still passed on macOS. `windows_names_link_the_binary_named_like_the_package_on_every_os` now ships `rtok-hook.exe` and `rtok.exe` (both archive orders, and upper case) on every host and reads the linked target from `state.json`, so macOS and Linux CI fail on the same regression.

`tests/bin_choice.rs` publishes a release with `rtok` and `rtok-hook` and asserts only `rtok` is linked. The file is not gated by OS. `crates/ketch-core/src/bin_choice.rs` asserts the exact package name wins in Windows sort order (`rtok-hook.exe` before `rtok.exe`) and in Unix sort order.

Tests: `the_binary_named_like_the_package_is_linked_and_its_helper_is_not`, `the_exact_name_wins_in_windows_sort_order`, `the_exact_name_wins_in_unix_sort_order`.
