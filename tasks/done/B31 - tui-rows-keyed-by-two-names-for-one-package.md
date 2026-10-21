---
id: B31
title: TUI rows keyed by two names for one package
status: done
created_at: 1792583218
updated_at: 1792583218
---

`prepare` emitted `PackageSpec::label()` (an alias, or a path) while `commit` emitted `manifest.name`, so a TUI row spun at Installing forever. Both prepare and commit now key the row with `PackageSpec::label()`, carried on `Prepared`. Install `completed` uses the same key. Test: `alias_or_path_install_does_not_stick_on_installing`.
