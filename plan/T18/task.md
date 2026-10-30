---
status: in progress
priority: P1
complexity: 5
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T18. A real Windows 11 window and installer

T17 left the Windows look on an undecorated window: no rounded corners, no Snap Layouts, a material
painted over the window's own wall, and an installer never built on Windows. Done means the window
has the system's corners, shadow and Snap Layouts, shows the desktop through a real backdrop where
the system and renderer allow it and falls back to the painted wall where they do not, the MSI is
built and run in the VM, and a second monitor has been tried or the reason it could not be is said.

## Execution plan

1. Build `:desktop:packageMsi` in the VM with `conan`, `cmake` and `ninja` on the user `PATH`; install
   the package and start it.
2. `kotlin-fluent`, jvmMain: a window-frame helper over JNA, without business logic. It subclasses the
   window and the Skia layer, asks DWM for corners and the backdrop, and reports the caption buttons
   to Windows, so the maximize button gets Snap Layouts.
3. `Main.kt` wears it on `Look.Fluent`. Any failure leaves the window as T17 made it: fail open.
4. The material setting picks the backdrop. High contrast, Windows 10 and a renderer without
   transparency keep the painted wall.
5. Check in the VM: corners, Snap Layouts, drag, maximize, resize, DPI, a second monitor if Parallels
   gives one. `./gradlew build --no-watch-fs`.
