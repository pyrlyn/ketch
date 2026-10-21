---
id: B116
title: Do not run an installed client as a source plugin
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B116 in the 2026-10-12 findings. Discovery walks `PATH`, and install puts client binaries on that `PATH`, so a package that ships `ketch-source-*` was executed on later commands.

Done: the bin directory and the store are not searched, and an executable whose path resolves into either is skipped. A plugin in the plugins directory, or elsewhere on `PATH`, is still found.
