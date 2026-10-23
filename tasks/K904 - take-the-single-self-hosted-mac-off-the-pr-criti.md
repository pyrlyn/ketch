---
id: K904
title: Take the single self-hosted Mac off the PR critical path
status: open
created_at: 1792603858
updated_at: 1792603928
priority: 0
blocked_by:
- K903
---

`MACOS_RUNNER=xcode-27-local` sends every macOS job to one org-wide runner. Four macOS jobs (about 85 job-minutes) queue behind each other there, waits reach 165–284 minutes, and 428 pull-request runs were cancelled in three days. Since #462 `rust / plan`, `fmt` and `msrv` land on it too, and the docs-only `package (macos)` no-op waits up to two hours for 0.2 minutes of work. `ketch-ffi` and `macos-app` each build the XCFramework.

Done when pull requests use hosted macOS (the Mac only for `main` or a merge queue), plan/fmt/msrv and the docs-only `package` run on `ubuntu-latest`, one XCFramework build feeds both jobs, and the median pull-request wall time over 20 runs is under 30 minutes.
