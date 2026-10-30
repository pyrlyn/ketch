# T18. Done

The Windows look now has the system's frame, and the MSI was built and run on Windows. Two things
stay unconfirmed: a Snap Layouts choice and a second monitor. See "Not confirmed".

## What was done

- `WindowsFrame` in `kotlin-fluent`'s `jvmMain`, over JNA, without business logic: the window keeps
  Compose's undecorated window and gets DWM's rounded corners and shadow, the system menu, and Mica or
  Acrylic behind a see-through canvas. Windows is told what lies under the pointer through
  `WindowZones`, so it treats the maximize button as its own and offers Snap Layouts. It replaces
  three limits T17 recorded in `plan/T17/done.md`: the material over the window's own wall, the square
  corners and no Snap Layouts.
- `Main.kt` attaches it on `Look.Fluent` before the window is first shown. `attach` returns null
  off Windows 11 and on any failure, and the window then goes on as T17 made it, `maximizedBounds`
  included. Mica needs build 22621; High Contrast keeps Material 3.
- The material setting picks the backdrop on the fly. `Transparent` stays ours, as the system has
  nothing that thin. The wall and fill drop out only when the backdrop was really applied.
- `java.logging` joined the runtime's modules, as JNA logs through it and jlink would leave it out.
- `conan`, `cmake` and `ninja` went on the VM's user `PATH`, and `:desktop:packageMsi` ran there.

Package commits, on the branch of https://github.com/pyrlyn/packages/pull/2:

- `a558f7b` `feat(kotlin-fluent): give an undecorated window Windows 11's frame`
- `17f1fe1` `fix(kotlin-kit): keep a zip member's bytes past one buffer segment`, found while running
  the installed app. kotlinx-io's `readAtMostTo` stops at one 8 KiB segment, so a zip member larger
  than that lost bytes on a large read: `ripgrep` failed its CRC check and could not update. A
  regression test failed before the fix and passes after it.

## How it was checked

- `./gradlew build --no-watch-fs` on the Mac, with the local packages: compile, tests, ktlint, detekt,
  successful. `./gradlew check` in `kotlin-fluent` and `:archive:macosArm64Test` in `kotlin-kit`.
- `Ketch-1.0.0.msi` (72.7 MB) built in the VM (Windows 11 Pro build 26300 on Arm, Parallels 27),
  installed per user, exit code 0. `jna-5.19.1` is in the app's folder, and `runtime/release` lists
  `java.logging`.
- On the installed app: rounded corners, the real desktop through Acrylic, and Mica, Acrylic and
  Transparent switching live. Hover on Maximize and its reset on leaving the window were confirmed
  on screenshots.
- Snap Layouts: Windows' own panel opened from our button twice on the software renderer and once on
  ANGLE with Acrylic.

## Not confirmed

- **Snap Layouts, in full.** The panel did not open in Transparent (five runs, though the button's hover
  showed) and in one run on Mica, where the pointer was already on the button. The cause was not found,
  and the task's console window in the VM got in the way. Picking a layout was not confirmed either: the
  panel closes when the pointer is moved by a script, and dragging to the screen's edge failed in that
  run.
- **A second monitor.** The Mac has one display, the VM reports one screen, and `prlctl` has no option
  for more. The only virtual display driver for Arm is self-signed and needs test signing, a security
  setting that was not changed. `keepOnTheScreen` takes its rectangle from `WM_NCCALCSIZE` and the
  window's DPI, so it does not depend on the monitor, but that is reasoning, not a run. An external
  monitor or a real Windows machine settles it.
- **Direct3D 12.** Parallels stops at feature level 11_1, `D3D12CreateDevice` fails for 12_0 and up, and
  Skiko's DX12 needs 12_0 and turns WARP down, so no package can add it. The VM ran ANGLE. Real
  hardware will use DX12, which was never exercised here.
- **The dark theme live**, which is covered by unit tests of the colours only, as the Windows setting
  was left alone; and **Alt+Space by hand**, as the emulated key closes the menu when Alt is released.

## Known limits

- In the VM, on any GPU renderer (ANGLE, OpenGL, ANGLE without vsync), the screen shows the previous
  frame until the next one; software rendering does not. The app's own logic takes milliseconds, so
  this is left alone until it is seen on real hardware.
- An auto-hiding taskbar is not allowed for when the window is maximized.
