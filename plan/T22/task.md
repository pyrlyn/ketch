---
status: in progress
priority: P1
complexity: 4
readiness: 65%
agent: Claude Code / claude-opus-5-5
---

# T22. End-to-end desktop tests with screenshot comparison on every platform

The window's tests run on a fake core, so nothing proves the app and the real `ketch` work together, and nothing notices
when a screen stops looking right. Each platform has its own look, and a broken pixel in one says nothing about the others.

Done means: on macOS, Linux and Windows a test drives the whole app through the real `ketch serve` against a local stand-in
GitHub with fixed test data (search, install, update, uninstall), and compares every screen with a reference image of that
platform. A changed screen fails the test and leaves the reference, the new image and a diff to look at.

## Plan

1. Test data in `desktop/e2e/data/`: a registry and repositories with releases as plain files. A stand-in GitHub on the JDK's
   own `HttpServer` serves them through `KETCH_GITHUB_API`, in a fixed scratch root, so paths on screen do not depend on the
   checkout.
2. A Gradle task `:desktop:e2e` apart from `test`: it needs the linked `ketch`, and a reference only means something on the
   system that recorded it. `-Pe2e.record` writes the references.
3. Comparison behind one function of ours, on Roborazzi. Fixed window size, density, clock, language and time zone; a frame
   counts once two in a row agree, so no fade lands in a reference. A running install is not captured: its spinner never rests.
4. Scenarios: every section, the package page and a dialog, in the host's look and in Material, light and dark. References in
   `desktop/e2e/references/<os>/`.
5. Record and compare on macOS here, Linux in a Docker image on Colima (fixed JDK and fonts, and the shared Lima VM stays as it
   is; Kotlin/Native has no Linux arm64 host, so `ketch` is linked on the Mac), Windows in the Parallels VM.
6. Docs: `AGENTS.md`, `README.md`, `toolchain.md`, `plan/README.md`.

Check: `./gradlew :desktop:e2e` passes on each of the three systems, a deliberate change to a screen fails it with a diff, and
two recordings in a row give identical images.
