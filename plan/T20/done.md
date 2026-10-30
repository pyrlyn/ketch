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

## Not checked

- Rendering on a real GPU: skiko cannot create a GL context in this VM (`Cannot create Linux GL context`, with any look),
  so everything above ran in software.
- `ketch` for Linux cannot be linked from the Mac as it stands: kotlin-kit's Conan bzip2 and liblzma are the host's. The
  VM run passed the VM's own libraries through `kotlin.native.linkArgs`, by hand.
- The `.deb` was not built, so neither was its `.desktop` file. Run from a classpath, the dock shows
  `ketch-desktop-MainKt` with a gear, and `StartupWMClass` is not configured.

## Known and left

- The first click after coming back from the tray is lost, on any control; the second works. The cause is unknown.
- A second gear appears in the dock after coming back from the tray.
- GNOME draws no shadow around the transparent window.
- The tray menu is plain AWT.
