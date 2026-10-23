---
id: K965
title: Share one HTTP agent between sources
status: open
created_at: 1792739905
updated_at: 1792739905
priority: 3
---

`Http::new` and `Http::anonymous` (`http.rs:40-60`) build two copies of the same `ureq` agent, and `Sources::load` (`source/mod.rs:307`, `:329`) and the registry update (`registry.rs:63`) each build their own, so connections and TLS sessions to `api.github.com` are not reused within one command. Extends K956.

Done when one builder function sets the user agent and timeouts, and one agent per command is shared by every source and the registry download.
