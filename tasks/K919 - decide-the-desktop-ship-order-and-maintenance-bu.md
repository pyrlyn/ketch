---
id: K919
title: Decide the desktop ship order and maintenance budget
status: open
created_at: 1792603870
updated_at: 1792603870
priority: 1
---

About 15,600 lines of Swift, C# and Vala for one maintainer, and fixes already land unevenly (B129–B131 reached one or two apps). Linux has D17–D19 open and needs C ABI safety work first.

Done when the creator's choice is recorded in `docs/research-desktop-platforms.md`, `plan.md` and `roadmap.md`: (a) macOS, then Windows, Linux frozen at the fake shell; (b) all three, with every behaviour fix landing in all apps in one PR; or (c) Linux on gtk4-rs linking `ketch-core`, dropping `ketch-capi`.
