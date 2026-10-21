---
id: R3
title: Cross-platform CI
status: in-progress
created_at: 1792583218
updated_at: 1792583218
priority: 1
---

Run verification on macOS, Windows and Linux. A ketch config is either a local file in the project or pushed to a registry; keep that model. The cross-platform check must catch OS-specific binary selection bugs like the one in B64.

Current state: `ci.yml` has separate jobs on macOS, Linux and Windows running lint, the full nextest suite and the `tui`-feature tests on all three. Formatting and commit-message checks run on macOS only, PowerShell syntax on Windows only. The packaging matrix runs on all three OSes. `Swatinem/rust-cache` is already in every job. `verify.yml` mirrors the same three-OS checks before a release.

To add:

1. Binary selection regression test: see B65 below.
2. Both config paths, local file and registry: cover the select-mode prompt when the binary name is missing and several candidates match, and assert the chosen binary is written back into the config.
3. Caching: rust-cache is already in place. Also evaluate caching the mise toolchain and the target directories on all three OSes. Do this in any case, and base the decision on the before/after build-time numbers from the cox and ketch infra-template PRs.

Findings: no workflow edit. `ci.yml` jobs `check` (`macos-latest`), `check-linux` (`ubuntu-latest`), `check-windows` (`windows-latest`) and `package` (all three) already run lint and the full nextest suite, so a binary-selection test is picked up with no extra job. `verify.yml` job `verify` uses the same three runners. `Swatinem/rust-cache@v2` is already in each of those jobs.

Do not add a second cache. `jdx/mise-action` already caches the toolchain, and `rust-cache` already caches `target/`. Numbers from [ketch#151](https://github.com/pyrlyn/ketch/pull/151) run [36318217579](https://github.com/pyrlyn/ketch/actions/runs/36318217579), the current-workflow run [36318223420](https://github.com/pyrlyn/ketch/actions/runs/36318223420), the following warm main run [36320755597](https://github.com/pyrlyn/ketch/actions/runs/36320755597), and [cox#57](https://github.com/listepo/cox/pull/57) run [36318140763](https://github.com/listepo/cox/actions/runs/36318140763):

- Mise cache hits on the ketch infra-template jobs: 35 MB macOS, 46 MB Linux, 62 MB Windows. Cox shows the same macOS and Linux hits and has no Windows job.
- macOS `check`: 8m9s on a target-cache miss, then 1m47s after restoring 778 MB.
- Linux `check-linux`: 894 MB restored in 12s, job 1m12s.
- Windows `check-windows`: 815 MB restored, and the restore itself was 2m52s of a 5m56s job. Another target cache would pay that download again.

Item 2 stays with B64. Item 1 is B65, which closes with B64's `tests/bin_choice.rs`.
