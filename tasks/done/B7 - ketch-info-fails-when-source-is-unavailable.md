---
id: B7
title: "`ketch info` fails when source is unavailable"
status: done
created_at: 1792583854
updated_at: 1792583854
---

A missing or too-new plugin made `info` exit 1 with `no source is registered for scheme …`, though the comment above the manifest fallback promises that an installed package always has an answer, and `outdated` only warns.

For an installed package, `info` now treats the source as optional: it still prints name, version, prefix and binaries, and warns that url, latest, stars, license, archived (and assets when asked) cannot be shown. An uninstalled package still fails with `UnknownScheme`. Regression: `info_still_reports_an_installed_package_when_the_source_is_unavailable` covers a deleted plugin and a protocol-too-new plugin.
