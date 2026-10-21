---
id: B8
title: "`changelog <pkg>@<version> --file` not installed"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`elsewhere` treated any exact version as "not local", and `state.find` was handed the raw `pkg@version` string, which matches no key. The shipped `CHANGELOG.md` on disk was refused with "not installed".

Fixed by looking up the installed package via `spec.alias` (and source ref fallbacks), comparing the requested version against the installed tag before treating the payload as local, and keeping `--file` on the installed copy when versions match. Regression test: `changelog_file_with_an_explicit_version_reads_the_installed_payload`.
