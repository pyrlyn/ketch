/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * The accessible names and accelerators the window applies. This does not
 * construct a GTK widget: the GUI is built only where GTK 4.20 and
 * libadwaita 1.8 are installed, and this test runs with the core library.
 */
using KetchApp;

void button_names_include_the_package () {
    assert (install_button_name ("ripgrep") == "Install ripgrep");
    assert (update_button_name ("ripgrep") == "Update ripgrep");
    assert (uninstall_button_name ("ripgrep") == "Uninstall ripgrep");
}

void status_names_are_the_text_for_the_glyph () {
    assert (log_level_name (LogLevel.INFO) == "info");
    assert (log_level_name (LogLevel.SUCCESS) == "ok");
    assert (log_level_name (LogLevel.WARNING) == "warning");
    assert (log_level_name (LogLevel.ERROR) == "error");
    assert (finding_name ("ok") == "ok");
    assert (finding_name ("warn") == "warning");
    assert (finding_name ("fail") == "error");
}

void search_and_refresh_shortcuts_sit_beside_preferences () {
    string[] lines = {};
    foreach (var accel in window_accels ()) {
        lines += "%s %s".printf (accel.action, accel.combo);
    }
    assert (string.joinv ("\n", lines) == "win.preferences <Control>comma\nwin.search <Control>f\nwin.refresh <Control>r");
}

int main (string[] args) {
    Test.init (ref args);
    Test.add_func ("/a11y/button-names-include-the-package", button_names_include_the_package);
    Test.add_func ("/a11y/status-names-are-the-text-for-the-glyph", status_names_are_the_text_for_the_glyph);
    Test.add_func ("/a11y/search-and-refresh-beside-preferences", search_and_refresh_shortcuts_sit_beside_preferences);
    return Test.run ();
}
