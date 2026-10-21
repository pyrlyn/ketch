---
id: D16
title: "Linux: Vala + GTK 4 app shell on a fake core"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The Linux app's screens live in `desktop/linux`, built before the C ABI is wired in (D17). The core contract, the fake and the store are a GLib static library, so `meson test` runs without a display. The app is Vala, GTK 4 and libadwaita: an `AdwNavigationSplitView` (Installed, Discover, Updates, Activity, Doctor) that collapses under 600px, a package page pushed on the content navigation view, and preferences for the colour scheme. Blueprint 0.22.2 is a pinned Meson subproject; its output and a copy of `ketch-tokens.css` go into one GResource. `AdwAlertDialog` covers binary choice, stopping processes, uninstall and upgrade-all; `AdwBanner` is the busy lock (Retry) and the running operation (Cancel); `AdwToast` is a finished operation. Dark and high contrast follow libadwaita 1.8, which applies the token stylesheet's `prefers-color-scheme` and `prefers-contrast` queries. An empty search plays `search-browse`; a typed query filters the search scenario. The fake answers in ketch-capi's JSON envelopes, so D17 can sit behind the same `Core` interface.

Tests: `desktop/linux/tests/fake-core-test.vala` and `store-test.vala` (`just linux-app-test`). The `ketch-linux-app` CI job builds with `-Dgui=enabled` on Ubuntu 26.04, where GTK 4.20 and libadwaita 1.8 are the distribution's.
