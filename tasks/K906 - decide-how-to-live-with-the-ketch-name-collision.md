---
id: K906
title: Decide how to live with the `ketch` name collision
status: open
created_at: 1792603859
updated_at: 1792603859
priority: 1
---

Homebrew core already ships a `ketch` formula (1broseidon/ketch, web scraping for agents), and the AUR `ketch` is a third project. `brew install ketch` installs the wrong program, and a second `ketch` on PATH is only hinted at for `~/.cargo/bin` (`doctor.rs:405`).

Done when the creator's decision (keep the name and disambiguate, or rename the binary or brand) is recorded, README, site and docs (with ru/uk) point at the tap explicitly, and `ketch doctor` names a Homebrew-core `ketch` that shadows this one.
