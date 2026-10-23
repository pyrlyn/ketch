---
id: K925
title: Carry one cancel token per operation
status: open
created_at: 1792603877
updated_at: 1792603877
---

There are two tokens, `Ctx::cancel` (`report.rs:335`) and `InstallRequest::cancel` (`install.rs:73`). `ketch-ffi`'s `ctx()` never sets the caller's token, so a desktop upgrade's network-heavy check phase (`fill_latest`) cannot be cancelled, and `outdated` takes no token at all.

Done when the token lives only on `Ctx`, the FFI context carries the caller's token, `outdated` takes one, and a test shows a cancelled token stops upgrade planning with `Cancelled`.
