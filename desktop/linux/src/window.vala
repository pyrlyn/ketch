/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * The main window over the store: every list is bound to one of the store's
 * ListStores, so the window never copies state it would have to keep in step.
 * The layout is window.blp; this file only binds it.
 */
namespace KetchApp {
    delegate Gtk.Widget RowFunc (Object item);
    delegate void Clicked ();

    [GtkTemplate (ui = "/io/github/pyrlyn/Ketch/window.ui")]
    public class Window : Adw.ApplicationWindow {
        [GtkChild] unowned Adw.NavigationSplitView split_view;
        [GtkChild] unowned Adw.NavigationPage content_page;
        [GtkChild] unowned Adw.NavigationView nav;
        [GtkChild] unowned Adw.NavigationPage section_page;
        [GtkChild] unowned Gtk.Stack sections;
        [GtkChild] unowned Gtk.Label updates_badge;
        [GtkChild] unowned Gtk.Stack installed_state;
        [GtkChild] unowned Gtk.ListBox installed_list;
        [GtkChild] unowned Gtk.SearchEntry discover_entry;
        [GtkChild] unowned Gtk.Stack discover_state;
        [GtkChild] unowned Gtk.ListBox discover_list;
        [GtkChild] unowned Gtk.Stack updates_state;
        [GtkChild] unowned Gtk.ListBox updates_list;
        [GtkChild] unowned Gtk.ListBox held_list;
        [GtkChild] unowned Adw.PreferencesGroup activity_group;
        [GtkChild] unowned Gtk.ProgressBar activity_progress;
        [GtkChild] unowned Gtk.ListBox log_list;
        [GtkChild] unowned Gtk.ListBox findings_list;

        public Store store { get; construct; }

        public Window (Adw.Application app, Store store) {
            Object (application: app, store: store);
        }

        construct {
            bind (installed_list, store.installed, installed_state, (item) => {
                var package = (Package) item;
                return new Adw.ActionRow () { title = package.name, subtitle = package.version };
            });
            bind (discover_list, store.search_results, discover_state, (item) => {
                var found = (RegistryPackage) item;
                var row = new Adw.ActionRow () { title = found.name, subtitle = found.description ?? found.source };
                row.add_suffix (button (_("Install"), () => store.install.begin (found.name)));
                return row;
            });
            bind (updates_list, store.updates, updates_state, (item) => {
                var upgrade = (Upgrade) item;
                var row = new Adw.ActionRow () { title = upgrade.name, subtitle = "%s → %s".printf (upgrade.installed, upgrade.latest) };
                row.add_suffix (button (_("Update"), () => store.upgrade.begin ({ upgrade.name })));
                return row;
            });
            bind (held_list, store.held, null, (item) => {
                var upgrade = (Upgrade) item;
                return new Adw.ActionRow () {
                    title = upgrade.name,
                    subtitle = _("%s → %s, held by %s").printf (upgrade.installed, upgrade.latest, upgrade.held_by)
                };
            });
            bind (log_list, store.log, null, (item) => {
                var entry = (LogEntry) item;
                var row = new Adw.ActionRow () { title = entry.message, subtitle = entry.time.format ("%X") };
                row.add_prefix (new Gtk.Image.from_icon_name (level_icon (entry.level)));
                return row;
            });
            bind (findings_list, store.findings, null, (item) => {
                var finding = (Finding) item;
                var row = new Adw.ActionRow () {
                    title = finding.name,
                    subtitle = finding.fix == null ? finding.detail : "%s\n%s".printf (finding.detail, finding.fix)
                };
                row.add_prefix (new Gtk.Image.from_icon_name (finding_icon (finding.outcome)));
                return row;
            });

            // The held list has its own group inside the updates page, so the
            // page is empty only when neither list has anything in it.
            store.held.items_changed.connect (() => show_state (updates_state, store.updates.get_n_items () + store.held.get_n_items ()));
            store.updates.items_changed.connect (() => {
                var n = store.updates.get_n_items ();
                updates_badge.label = n.to_string ();
                updates_badge.visible = n > 0;
            });
            store.notify["activity"].connect (follow_activity);

            store.search.begin ("");
            store.run_doctor.begin ();
        }

        /* Fills `list` from `model`, and flips `state` between its empty page and the list as the model changes. */
        void bind (Gtk.ListBox list, ListStore model, Gtk.Stack? state, owned RowFunc make) {
            list.bind_model (model, (item) => make (item));
            if (state != null) {
                model.items_changed.connect (() => show_state (state, model.get_n_items ()));
                show_state (state, model.get_n_items ());
            }
        }

        static void show_state (Gtk.Stack state, uint n) {
            state.visible_child_name = n > 0 ? "list" : "empty";
        }

        static Gtk.Button button (string label, owned Clicked clicked) {
            var b = new Gtk.Button.with_label (label) { valign = Gtk.Align.CENTER };
            b.clicked.connect (() => clicked ());
            return b;
        }

        /* The store swaps in a new Activity per operation; the bar follows whichever is current. */
        void follow_activity () {
            var activity = store.activity;
            activity_group.visible = activity != null;
            if (activity == null) {
                return;
            }
            activity_group.title = activity.title;
            activity.notify.connect (() => {
                activity_progress.fraction = activity.fraction;
                activity_progress.text = activity.cancelling ? _("Cancelling…") : activity.status;
            });
        }

        static string level_icon (LogLevel level) {
            switch (level) {
            case LogLevel.SUCCESS: return "emblem-ok-symbolic";
            case LogLevel.WARNING: return "dialog-warning-symbolic";
            case LogLevel.ERROR: return "dialog-error-symbolic";
            default: return "dialog-information-symbolic";
            }
        }

        static string finding_icon (string outcome) {
            return outcome == "ok" ? "emblem-ok-symbolic" : outcome == "warn" ? "dialog-warning-symbolic" : "dialog-error-symbolic";
        }

        [GtkCallback]
        void on_section_activated (Gtk.ListBoxRow row) {
            var name = row.name;
            var title = ((Adw.PreferencesRow) row).title;
            sections.visible_child_name = name;
            section_page.title = title;
            content_page.title = title;
            nav.pop_to_page (section_page);
            split_view.show_content = true;
        }

        [GtkCallback]
        void on_search_changed () {
            store.search.begin (discover_entry.text);
        }

        [GtkCallback]
        void on_update_all () {
            store.upgrade.begin ({});
        }

        [GtkCallback]
        void on_cancel () {
            store.cancel ();
        }

        [GtkCallback]
        void on_run_doctor () {
            store.run_doctor.begin ();
        }
    }
}
