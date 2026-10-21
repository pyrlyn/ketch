---
id: D12
title: "Windows: the app on the real core"
status: open
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

Swap the Windows app's fake core for the binding. Depends on D10, D11, D1 and D2.

Done when the app runs every screen on `ketch-ffi` through D10's binding, work runs off the UI thread with events marshalled to the `DispatcherQueue`, cancel and `Busy` behave as in the contract, and a manual pass against a scratch root is recorded.

The app ships both as an MSIX and unpackaged (D14), and an unpackaged app has no `ApplicationData`, so settings go in a file under `%LOCALAPPDATA%\ketch` that works in both.
