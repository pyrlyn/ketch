# T20. Done

On Linux the app now opens in the `Ketch Linux` design by default: a dark glass window with a header bar, a view
switcher, boxed lists, a popover menu and toasts, on real data. High contrast keeps Material 3; `KETCH_LOOK=adwaita`
shows the look on any system.

- `packages/kotlin-adwaita` (`pyrlyn.adwaita:adwaita`) holds the generic part, with no ketch word in it, and
  `kotlin-glass` gains the `Menu` glyph: https://github.com/pyrlyn/packages/pull/4. Until that merges, build with
  `KOTLIN_ADWAITA_DIR` and `KOTLIN_GLASS_DIR` pointing at its branch `t20-kotlin-adwaita`.
- `desktop/.../ui/adwaita/` holds the shell and every screen. `Edge.kt` gives the undecorated window resize edges.
- Logic the looks share moved up: `Kind`, `emptyWords`, `isFixed` in `ui/Summaries.kt`, `readWhy` in `ui/screens/Package.kt`,
  the font lookup in `ui/Host.kt`.

## Left out of the design

Each of these has no call in the core, or none the page can make honestly, so it is not drawn (T19: no control that does
nothing):

- Settings: Add source…, Add token…, Change…, the source toggles, and the Security and Keep previous toggles. The values
  show as read-only pills beside Open config.toml.
- Package and Updates: Roll back, Unpin, and "Also remove its configuration" in the uninstall dialog.
- Activity: the kind chips, because log entries carry only a level and a message. A filter entry takes their place.
- Menu and Discover: Keyboard Shortcuts, and the hero carousel's previous and next.

## How it was checked

- `./gradlew build`: compile, 230 desktop tests, ktlint, detekt. `./gradlew check` in `kotlin-adwaita` (11 tests) and
  `kotlin-glass`.
- Ubuntu 26.04 ARM64 VM, the app with a real `ketch` for linuxArm64, on Xvfb with openbox and picom: the look is picked
  without `KETCH_LOOK`, corners are round, the right and bottom edges resize, the header drags and double-click
  maximizes and restores, the menu, Ctrl+, and Ctrl+K work, ripgrep searched, installed and uninstalled through the UI,
  Doctor ran, and the close button ended the app and `ketch serve`.
- On the Mac, the app started with `KETCH_LOOK=adwaita` and logged no error. Its pixels were checked in rendered test
  screenshots, including the menu, the Select, the dialogs and About.

- A real session on the same VM: GNOME Shell 50.1 on Wayland, the app through XWayland, real pointer and keyboard input
  through `org.gnome.Mutter.RemoteDesktop`, measured with `xdotool getwindowgeometry`:
  - The look is picked without `KETCH_LOOK`, corners are round, `ketch serve` answers.
  - The right, top, bottom and left edges follow the pointer to the pixel, shrinking included. The SE and NE corners are
    exact; NW was exact in 4 of 5 pulls and once came up short by 7 and 5 px, which did not happen again. The SW corner
    was not measured.
  - The header drags exactly. Double-click and the button maximize inside the panel and the dock, with square corners,
    and restore returns the same geometry. Minimize and the dock bring it back.
  - Ctrl+K with real typing searches the registry and GitHub. Ctrl+, and the main menu work.
  - Close hides to the tray. Left click, Open Ketch and Quit Ketch work, and Quit ends the app and `ketch serve`.

This session found three bugs, each fixed and rechecked there:

- Resizing lost the first pixels of a pull on a slow frame, because it started from where the pointer was by then.
- Ctrl+, in the search field also typed the comma, and the changed query went back to Discover.
- A window hidden to the tray while maximized never came back: mutter held its frames (`_XWAYLAND_ALLOW_COMMITS=0`). It
  now leaves for the tray floating, so it comes back floating at its last floating size.

## Rendering

Measured in the same session, on 2 vCPUs and no GPU, with `skiko.fps.enabled` and the same scroll each time:

| Change | Scrolling at 1200x800 | Text acuity, 15sp and 13sp |
| --- | --- | --- |
| Before | 18 fps | 0.603, 0.570 |
| The wall blurred once per size, kept at quarter size | 45 fps | |
| The wall kept at full size | 54 fps | |
| Glyphs on whole pixels below 2x | 52 to 56 fps | 0.634, 0.585 |

- The wall was a full-window `Modifier.blur`, redone on every frame. It is now painted once for each size. The picture
  did not change: the mean difference is 0.26 of 255 and 333 of 960,000 pixels differ by more than 8.
- Acuity is the share of the horizontal edge energy in single sharp steps, from a nearest-neighbour read of the captures.
  Whole-pixel positions take about 3% more width. Forcing the autohinter gave the same pixels, so it is not used.
- Live resize runs at 16 to 22 fps here, with the wall repainted for each size. The old wall was not measured on a resize.
- Compose's other effects need nothing: its shadows are cached already.
- Native GTK text on this machine is grayscale too. Only GNOME Shell draws LCD text, following fontconfig.

LCD text is left out. Compose builds its `SkiaLayer` with `PixelGeometry.UNKNOWN`, which turns LCD text off whatever the
font settings say, and gives no way to change it. Writing that private field by reflection did show LCD text on
`SOFTWARE_COMPAT`, but it hangs on Skiko's internals, and Skiko's Linux `SOFTWARE_FAST` surface never reads the geometry.

## Not checked

- Rendering on a real GPU: GLX works in this VM, but Skiko refuses `llvmpipe` and `virgl` by name and falls back to
  software, with the same `Cannot create Linux GL context` for both. So the transparent window on OpenGL has never run,
  and every number above is software.
- A scaled display: AWT goes through XWayland, which mutter upscales above 100%, so the window would be soft there. The
  VM's screen is too small to scale.
- Frame rate while maximized: nothing on the page scrolls at that size.
- `ketch` for Linux cannot be linked from the Mac as it stands: kotlin-kit's Conan bzip2 and liblzma are the host's. The
  VM run passed the VM's own libraries through `kotlin.native.linkArgs`, by hand.
- The `.deb` was not built, so neither was its `.desktop` file. Run from a classpath, the dock shows
  `ketch-desktop-MainKt` with a gear, and `StartupWMClass` is not configured.

## Known and left

- The first click after coming back from the tray is lost, on any control; the second works. The cause is unknown.
- A second gear appears in the dock after coming back from the tray.
- GNOME draws no shadow around the transparent window.
- The tray menu is plain AWT.
