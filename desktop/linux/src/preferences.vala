/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Preferences: the colour scheme, and on the fake core which scenario each
 * operation plays next, so every state of the screens can be reached by hand.
 * Nothing is saved: the scheme defaults to the system's, and the scenarios
 * only mean something to the fake.
 */
namespace KetchApp {
    public class Preferences : Adw.PreferencesDialog {
        const Adw.ColorScheme[] SCHEMES = { Adw.ColorScheme.DEFAULT, Adw.ColorScheme.FORCE_LIGHT, Adw.ColorScheme.FORCE_DARK };

        public Preferences (Core core) {
            var page = new Adw.PreferencesPage ();
            var appearance = new Adw.PreferencesGroup () { title = _("Appearance") };
            var manager = Adw.StyleManager.get_default ();
            var style = new Adw.ComboRow () {
                title = _("Style"),
                model = new Gtk.StringList ({ _("Follow System"), _("Light"), _("Dark") })
            };
            style.notify["selected"].connect (() => manager.color_scheme = SCHEMES[style.selected]);
            appearance.add (style);
            page.add (appearance);

            var fake = core as FakeCore;
            if (fake != null) {
                var scenarios = new Adw.PreferencesGroup () {
                    title = _("Fake Core"),
                    description = _("The scenario from desktop/contract/scenarios each operation plays next")
                };
                scenarios.add (scenario_row (fake, "install", _("Install")));
                scenarios.add (scenario_row (fake, "upgrade", _("Upgrade")));
                scenarios.add (scenario_row (fake, "uninstall", _("Uninstall")));
                page.add (scenarios);
            }
            add (page);
        }

        static Adw.ComboRow scenario_row (FakeCore fake, string operation, string title) {
            string[] labels = { _("Default") };
            string?[] names = { null };
            foreach (var name in fake.names ()) {
                if (fake.operation_of (name) == operation) {
                    labels += name;
                    names += name;
                }
            }
            var row = new Adw.ComboRow () { title = title, model = new Gtk.StringList (labels) };
            row.notify["selected"].connect (() => fake.play (operation, names[row.selected]));
            return row;
        }
    }
}
