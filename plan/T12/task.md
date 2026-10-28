---
status: in progress
priority: P1
complexity: 4
readiness: 40%
agent: Claude Code / claude-opus-5-5
---

# T12. Compose desktop app

`:desktop`: the screens of `desktop/macos/Ketch/Views` and `desktop/windows/Ketch.App/Pages` in
one Compose app, on `ketch serve`. Tests run against a fake core that replays the contract
scenarios. Packaging: dmg, msi, deb.

## Subtasks

The creator split T12 when it was claimed: the Rust apps are about 9.5k lines.

| Id | What |
| --- | --- |
| T12.1 | The app's core: a client of `ketch serve`, and a fake that replays the contract scenarios |
| T12.2 | The store: state, operations, progress, busy and retry, questions, log, search |
| T12.3 | The window and its screens, Material 3 on the brand tokens |
| T12.4 | Settings, About, the tray and update notices |
| T12.5 | Packaging: dmg, msi and deb with the native `ketch` inside |

T12 is done when all five are.
