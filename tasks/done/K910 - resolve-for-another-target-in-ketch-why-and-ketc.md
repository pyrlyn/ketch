---
id: K910
title: Resolve for another target in `ketch why` and `ketch info`
status: done
created_at: 1792603862
updated_at: 1792969386
priority: 1
assignee: d2a34757-1247-4b58-beb9-8a90d064e359
---

Resolution only runs for the host: nothing lets you ask which asset Linux or Windows would get. That blocks checking a registry entry on every OS from one machine, measuring inference, and a lockfile for several targets.

Done when `ketch why <pkg> --target <triple-or-os-arch> [--json]` and `ketch info --assets --target …` resolve without installing, and unit tests cover each supported target.
