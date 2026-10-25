---
id: K921
title: Port the B129–B131 store fixes to the Linux app
status: done
created_at: 1792603873
updated_at: 1792957311
priority: 1
assignee: d2a34757-1247-4b58-beb9-8a90d064e359
---

On the real core an offline machine or a fast typist will hit, in the Linux app, bugs already fixed elsewhere: search results have no generation guard (`store.vala:64-69`, B130), a second operation is dropped silently (`store.vala:151-154`, B131), and a background refresh failure raises an alert (`store.vala:51-52`, B129).

Done when the Linux store behaves as macOS does in all three cases, with `store-test.vala` cases named after each B id.
