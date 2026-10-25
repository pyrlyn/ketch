/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * One installed package, pushed onto the window's navigation view. It keeps
 * the Package it was opened with rather than following the store: once the
 * package is uninstalled or upgraded the page is popped, not patched.
 */
namespace KetchApp {
    [GtkTemplate (ui = "/io/github/pyrlyn/Ketch/package-page.ui")]
    public class PackagePage : Adw.NavigationPage {
        [GtkChild] unowned Adw.ActionRow version_row;
        [GtkChild] unowned Adw.ActionRow source_row;
        [GtkChild] unowned Adw.ActionRow pinned_row;
        [GtkChild] unowned Gtk.Label changelog_label;
        [GtkChild] unowned Adw.PreferencesGroup files_group;
        [GtkChild] unowned Adw.ActionRow prefix_row;
        [GtkChild] unowned Gtk.Button uninstall_button;

        public Window window { get; construct; }
        public Package package { get; construct; }

        public PackagePage (Window window, Package package) {
            Object (window: window, package: package, title: package.name);
        }

        construct {
            version_row.subtitle = package.version;
            source_row.subtitle = package.source;
            pinned_row.subtitle = package.pinned ? _("Yes") : _("No");
            prefix_row.subtitle = package.prefix;
            foreach (var binary in package.binaries) {
                files_group.add (new Adw.ActionRow () { title = Path.get_basename (binary), subtitle = binary, subtitle_selectable = true });
            }
            // The label is only "Uninstall". The accessible name names the package.
            describe_control (uninstall_button, Gtk.AccessibleRole.BUTTON, uninstall_button_name (package.name));
            changelog_label.label = _("Loading…");
            window.store.changelog.begin (package.name, null, package.version, (obj, res) => {
                var text = window.store.changelog.end (res);
                changelog_label.label = text ?? _("No changelog found");
            });
        }

        [GtkCallback]
        void on_uninstall () {
            window.confirm (_("Uninstall %s?").printf (package.name), _("Its files and the links onto PATH are removed."),
                            _("_Uninstall"), Adw.ResponseAppearance.DESTRUCTIVE, () => {
                window.store.uninstall.begin ({ package.name });
                window.pop_page (this);
            });
        }
    }
}
