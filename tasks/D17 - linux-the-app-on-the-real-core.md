---
id: D17
title: "Linux: the app on the real core"
status: open
created_at: 1792583854
updated_at: 1792583854
priority: 3
---

Swap the Linux app's fake core for `ketch-capi`. Depends on D15 and D16.

Done when every screen runs on the C ABI, calls run off the main loop with events returned through the `GLib.MainContext`, cancel and `Busy` behave as in the contract, and a manual pass against a scratch root is recorded.
