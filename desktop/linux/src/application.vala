/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * The Linux app's entry point. Which core it runs on is decided here and
 * nowhere else, as CoreFactory.swift does on macOS: the fake core over the
 * contract scenarios until D17 puts ketch-capi behind the Core interface.
 */
namespace KetchApp {
    /* Where the scenarios were at build time; KETCH_SCENARIOS overrides it. */
    [CCode (cname = "KETCH_SCENARIOS_DIR")]
    extern const string SCENARIOS_DIR;

    public class Application : Adw.Application {
        Store? store;

        public Application () {
            Object (application_id: "io.github.pyrlyn.Ketch", flags: ApplicationFlags.DEFAULT_FLAGS,
                    resource_base_path: "/io/github/pyrlyn/Ketch");
        }

        public override void startup () {
            base.startup ();
            set_accels_for_action ("win.preferences", { "<Control>comma" });
        }

        public override void activate () {
            if (active_window != null) {
                active_window.present ();
                return;
            }
            if (store == null) {
                try {
                    var fake = new FakeCore (Environment.get_variable ("KETCH_SCENARIOS") ?? SCENARIOS_DIR);
                    // Slow enough that progress and Cancel can be seen working.
                    fake.step_delay_ms = 150;
                    store = new Store (fake);
                } catch (Error e) {
                    critical ("no core: %s", e.message);
                    return;
                }
                store.refresh.begin ();
            }
            new Window (this, store).present ();
        }

        public static int main (string[] args) {
            return new Application ().run (args);
        }
    }
}
