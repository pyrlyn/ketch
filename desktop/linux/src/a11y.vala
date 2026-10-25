/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * The words a screen reader should speak, and the window accelerators.
 * They live with the store rather than in the GTK window so the tests can
 * check them where the GUI is not built. The window and the application are
 * what put them on the widgets.
 */
namespace KetchApp {
    /* One accelerator installed on the window in Application.startup. */
    public struct WindowAccel {
        public string action;
        public string combo;
    }

    /* Ctrl+, stays. Ctrl+F focuses Discover search; Ctrl+R re-reads the store. */
    public WindowAccel[] window_accels () {
        WindowAccel[] accels = {};
        accels += WindowAccel () { action = "win.preferences", combo = "<Control>comma" };
        accels += WindowAccel () { action = "win.search", combo = "<Control>f" };
        accels += WindowAccel () { action = "win.refresh", combo = "<Control>r" };
        return accels;
    }

    /* The visible button stays the verb. Orca would otherwise announce only that. */
    public string install_button_name (string package) {
        return _("Install %s").printf (package);
    }

    public string update_button_name (string package) {
        return _("Update %s").printf (package);
    }

    public string uninstall_button_name (string package) {
        return _("Uninstall %s").printf (package);
    }

    /* The glyph stays. These are the words for it: info, warning, error, ok.
       Success uses the ok emblem, so it is spoken as ok. */
    public string log_level_name (LogLevel level) {
        switch (level) {
        case LogLevel.SUCCESS:
            return _("ok");
        case LogLevel.WARNING:
            return _("warning");
        case LogLevel.ERROR:
            return _("error");
        default:
            return _("info");
        }
    }

    /* Doctor outcomes are "ok", "warn" or "fail". Anything else failed the check. */
    public string finding_name (string outcome) {
        switch (outcome) {
        case "ok":
            return _("ok");
        case "warn":
            return _("warning");
        default:
            return _("error");
        }
    }
}
