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
    public delegate void Clicked ();
    delegate void Answer (string response);

    [GtkTemplate (ui = "/io/github/pyrlyn/Ketch/window.ui")]
    public class Window : Adw.ApplicationWindow {
        [GtkChild] unowned Adw.ToastOverlay toasts;
        [GtkChild] unowned Adw.NavigationSplitView split_view;
        [GtkChild] unowned Adw.NavigationPage content_page;
        [GtkChild] unowned Adw.NavigationView nav;
        [GtkChild] unowned Adw.NavigationPage section_page;
        [GtkChild] unowned Adw.Banner banner;
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
        /* The question the core is waiting on, closed if the store stops waiting first. */
        Adw.AlertDialog? question;
        Preferences? preferences;

        public Window (Adw.Application app, Store store) {
            Object (application: app, store: store);
        }

        construct {
            bind (installed_list, store.installed, installed_state, (item) => {
                var package = (Package) item;
                var row = new Adw.ActionRow () { title = package.name, subtitle = package.version, activatable = true };
                row.add_suffix (new Gtk.Image.from_icon_name ("go-next-symbolic"));
                row.activated.connect (() => nav.push (new PackagePage (this, package)));
                return row;
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
            store.notify["activity"].connect (update_banner);
            store.notify["busy"].connect (update_banner);
            store.notify["error-message"].connect (show_error);
            store.notify["pending-choice"].connect (ask_choice);
            store.notify["pending-stop"].connect (ask_stop);
            store.finished.connect ((title) => toasts.add_toast (new Adw.Toast (_("%s: done").printf (title))));
            banner.button_clicked.connect (() => {
                if (store.busy != null) {
                    store.retry_busy ();
                } else {
                    store.cancel ();
                }
            });
            var show_preferences = new SimpleAction ("preferences", null);
            show_preferences.activate.connect (() => {
                // Kept, so the scenario pickers still show what the fake is playing.
                if (preferences == null) {
                    preferences = new Preferences (store.core);
                }
                preferences.present (this);
            });
            add_action (show_preferences);

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

        /* The held lock wins the banner over the running operation: Retry is the only way on. */
        void update_banner () {
            if (store.busy != null) {
                banner.title = store.busy.message;
                banner.button_label = _("_Retry");
            } else if (store.activity != null) {
                banner.title = store.activity.title;
                banner.button_label = _("_Cancel");
            }
            banner.revealed = store.busy != null || store.activity != null;
        }

        void show_error () {
            var message = store.error_message;
            if (message == null) {
                return;
            }
            var alert = new Adw.AlertDialog (_("Something Went Wrong"), message);
            alert.add_response ("close", _("_Close"));
            alert.response.connect (() => store.error_message = null);
            alert.present (this);
        }

        /* Leaves `page` if it is still the one showing; the user may have gone back already. */
        public void pop_page (Adw.NavigationPage page) {
            if (nav.visible_page == page) {
                nav.pop ();
            }
        }

        /* Asks before `then`; the cancel response is the default way out. */
        public void confirm (string heading, string body, string label, Adw.ResponseAppearance look, owned Clicked then) {
            var alert = new Adw.AlertDialog (heading, body);
            alert.add_response ("cancel", _("_Cancel"));
            alert.add_response ("go", label);
            alert.set_response_appearance ("go", look);
            alert.close_response = "cancel";
            alert.response.connect ((response) => {
                if (response == "go") {
                    then ();
                }
            });
            alert.present (this);
        }

        void ask_choice () {
            var choice = store.pending_choice;
            if (choice == null) {
                close_question ();
                return;
            }
            var alert = new Adw.AlertDialog (_("Which Binary?"),
                                             _("%s ships several binaries. Choose the one to link onto PATH.").printf (choice.package));
            for (var i = 0; i < choice.candidates.length; i++) {
                alert.add_response (i.to_string (), choice.candidates[i]);
            }
            // Declining is not cancelling: ketch goes on and links by its own rules.
            alert.add_response ("decline", _("_Let ketch Decide"));
            alert.close_response = "decline";
            ask (alert, (response) => store.answer_choice (response == "decline" ? -1 : int64.parse (response)));
        }

        void ask_stop () {
            var stop = store.pending_stop;
            if (stop == null) {
                close_question ();
                return;
            }
            var alert = new Adw.AlertDialog (_("Stop Running Processes?"),
                                             _("These are using files the update replaces:\n%s").printf (string.joinv ("\n", stop.holders)));
            alert.add_response ("keep", _("_Keep Running"));
            alert.add_response ("stop", _("_Stop Them"));
            alert.set_response_appearance ("stop", Adw.ResponseAppearance.DESTRUCTIVE);
            alert.close_response = "keep";
            ask (alert, (response) => store.answer_stop (response == "stop"));
        }

        void ask (Adw.AlertDialog alert, owned Answer answer) {
            close_question ();
            question = alert;
            alert.response.connect ((response) => {
                if (question == alert) {
                    question = null;
                    answer (response);
                }
            });
            alert.present (this);
        }

        void close_question () {
            var open = question;
            question = null;
            if (open != null) {
                open.force_close ();
            }
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
            confirm (_("Update All Packages?"), _("Every package with a newer release is upgraded; held packages stay."),
                     _("_Update All"), Adw.ResponseAppearance.SUGGESTED, () => store.upgrade.begin ({}));
        }

        [GtkCallback]
        void on_run_doctor () {
            store.run_doctor.begin ();
        }
    }
}
