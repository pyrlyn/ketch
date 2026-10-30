---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T19. What the Windows look left out of the canvas

T17 dropped what the store had no data or action for. Done means each of those works on real data:
the All / CLI tools / Apps selector, "How ketch picks the asset" and the `ketch why` block, Fix and
Open folder on Doctor, the selected row, and the Update button in the notification. What cannot be
done honestly is named in `done.md` rather than drawn as a control that does nothing.

## Execution plan

1. `ketch-wire` and `ketch serve`: `RegistryPackage.isApp`, `Check.fixable`, `Call.Why` returning a
   `Resolution`. Serve keeps sanitizing what a release wrote.
2. Store: `why`, `fixDoctor`, the checks it fixed. Navigation: the row last acted on and the page's
   first tab.
3. `kotlin-fluent`: a terminal output block.
4. `ui/fluent/`: the selector, the hero's second button, the block on Overview, Fix and Open folder,
   the selected row.
5. A Windows notification with an Update button, in place of the tray's balloon, which has no
   buttons; the balloon stays as the fallback.
6. Tests: `RecordsTest`, `KetchStoreTest`, `FluentAppTest`, the package's tests. `./gradlew build
   --no-watch-fs`, `macosArm64Test` for serve, and a run in the VM.
