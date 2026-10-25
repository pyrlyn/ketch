/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * A stand-in for ketch-capi that answers from the contract scenarios. Tests
 * and, until D17, the app itself run on it; it never touches the disk past
 * reading the scenarios.
 *
 * A read answers its scenario's value. Install, upgrade and uninstall replay
 * the scenario's script: events go to the reporter, questions to the
 * callbacks (which answer, not the file), a cancel is honoured before each
 * step, and then comes the outcome. A declined question is not a cancel: the
 * real core links by its fixed rules or leaves the processes running and goes
 * on, and so does this fake. What installs and uninstalls place and remove is
 * kept, so the lists the store reads afterwards move as they would.
 */
namespace KetchApp {
    public class FakeCore : Object, Core {
        /* The root the scenarios were written against. */
        public const string ROOT = "/Users/me/.ketch";

        /* Slows each replayed step so the app shows progress; tests leave it 0. */
        public uint step_delay_ms { get; set; default = 0; }

        HashTable<string, Scenario> scenarios = new HashTable<string, Scenario> (str_hash, str_equal);
        HashTable<string, string> playing = new HashTable<string, string> (str_hash, str_equal);
        Json.Array packages;
        /* One call a test holds back, named as the store makes it (`search <query>`),
         * so an older reply can be forced to arrive after a newer one. */
        string? delayed_call;
        uint delayed_ms;
        /* Error object `installed` and `outdated` answer with, instead of their scenario. */
        string? read_error;
        /* The delay and the read error are set on the test thread and read on the core's. */
        Object hook_gate = new Object ();

        public FakeCore (string dir) throws Error {
            foreach (var scenario in Scenario.load_dir (dir).data) {
                scenarios[scenario.name] = scenario;
            }
            foreach (var name in new string[] { "installed", "search", "search-browse", "outdated", "doctor",
                                                "changelog-range", "install-ok", "upgrade-stops-processes",
                                                "uninstall-ok" }) {
                if (!scenarios.contains (name)) {
                    throw new ScenarioError.INVALID ("%s has no %s.json", dir, name);
                }
            }
            packages = scenarios["installed"].result.get_array ().ref ();
        }

        /* The scenario names, sorted, for a picker. */
        public string[] names () {
            var all = new GenericArray<string> ();
            scenarios.foreach ((name) => all.add (name));
            all.sort (strcmp);
            return all.steal ();
        }

        /* The operation `scenario` answers, or null for a name that is not one. */
        public string? operation_of (string scenario) {
            var found = scenarios[scenario];
            return found == null ? null : found.operation;
        }

        /* Answers every later call of `scenario`'s operation from it; null goes back to the default. */
        public void play (string operation, string? scenario) {
            lock (playing) {
                if (scenario == null) {
                    playing.remove (operation);
                } else {
                    playing[operation] = scenario;
                }
            }
        }

        /* Makes `call` (`search <query>`, arguments as passed) wait `milliseconds` before it answers. */
        public void delay (string call, uint milliseconds) {
            lock (hook_gate) {
                delayed_call = call;
                delayed_ms = milliseconds;
            }
        }

        /* `installed` and `outdated` answer a `type` error with `message`, so a refresh can fail with nothing else running. */
        public void fail_reads (string type, string message) {
            lock (hook_gate) {
                read_error = "{\"type\": %s, \"message\": %s}".printf (quote (type), quote (message));
            }
        }

        public string root () {
            return ROOT;
        }

        public string installed () {
            var failed = read_failure ();
            if (failed != null) {
                return failed;
            }
            lock (packages) {
                return envelope ("ok", array_node (packages));
            }
        }

        public string search (string query, uint32 limit, EventFunc? report) {
            hold ("search " + query);
            // An empty query browses the registry (search-browse). A typed
            // query filters the search scenario, the same call the core makes.
            var needle = query.strip ().down ();
            var found = pick ("search", needle == "" ? "search-browse" : "search");
            if (found.outcome != "ok") {
                return found.answer ();
            }
            var known = new Json.Array ();
            found.result.get_object ().get_array_member ("known").foreach_element ((_, __, node) => {
                var entry = node.get_object ();
                var text = (entry.get_string_member ("name") + " "
                            + (entry.get_string_member_with_default ("description", "") ?? "")).down ();
                if (needle in text && (limit == 0 || known.get_length () < limit)) {
                    known.add_element (node.copy ());
                }
            });
            var answer = new Json.Object ();
            found.result.get_object ().foreach_member ((_, key, node) => answer.set_member (key, node.copy ()));
            answer.set_array_member ("known", known);
            var node = new Json.Node (Json.NodeType.OBJECT);
            node.set_object (answer);
            return envelope ("ok", node);
        }

        /* The scenario's upgrades still pending: an upgraded package drops out. */
        public string outdated (EventFunc? report) {
            var failed = read_failure ();
            if (failed != null) {
                return failed;
            }
            var found = pick ("outdated", "outdated");
            if (found.outcome != "ok") {
                return found.answer ();
            }
            var pending = new Json.Array ();
            found.result.get_array ().foreach_element ((_, __, node) => {
                var upgrade = node.get_object ();
                if (version_of (upgrade.get_string_member ("name")) == upgrade.get_string_member ("installed")) {
                    pending.add_element (node.copy ());
                }
            });
            return envelope ("ok", array_node (pending));
        }

        public string changelog_range (string package, string? from, string? to, EventFunc? report) {
            return pick ("changelog_range", "changelog-range").answer ();
        }

        public string doctor (EventFunc? report) {
            return pick ("doctor", "doctor").answer ();
        }

        /* An install whose specs only one scenario's call names plays it (`uv` asks which binary, `nope` is not found). */
        public string install (string[] specs, string? options, EventFunc? report,
                               ChooseFunc? choose, StopFunc? stop, Cancellable? cancel) {
            string[] matching = {};
            scenarios.foreach ((name, scenario) => {
                if (scenario.operation == "install" && same (scenario.call.get_array_member ("specs"), specs)) {
                    matching += name;
                }
            });
            var found = pick ("install", matching.length == 1 ? matching[0] : "install-ok");
            var failed = replay (found, report, choose, stop, cancel);
            if (failed != null || found.outcome != "ok") {
                return failed ?? found.answer ();
            }
            found.result.get_array ().foreach_element ((_, __, node) => place (node.get_object ().get_object_member ("package")));
            return found.answer ();
        }

        public string upgrade (string[] names, EventFunc? report,
                               ChooseFunc? choose, StopFunc? stop, Cancellable? cancel) {
            var found = pick ("upgrade", "upgrade-stops-processes");
            var failed = replay (found, report, choose, stop, cancel);
            if (failed != null || found.outcome != "ok") {
                return failed ?? found.answer ();
            }
            found.result.get_array ().foreach_element ((_, __, node) => place (node.get_object ().get_object_member ("package")));
            return found.answer ();
        }

        /* Removes the names asked for, not the scenario's, and answers their records, as the core does. */
        public string uninstall (string[] names, EventFunc? report,
                                 ChooseFunc? choose, StopFunc? stop, Cancellable? cancel) {
            var found = pick ("uninstall", "uninstall-ok");
            var failed = replay (found, report, choose, stop, cancel);
            if (failed != null || found.outcome != "ok") {
                return failed ?? found.answer ();
            }
            var removed = new Json.Array ();
            lock (packages) {
                foreach (var name in names) {
                    if (version_of (name) == null) {
                        return envelope ("error", parse_or_null ("{\"type\": \"not_found\", \"name\": %s}".printf (quote (name))));
                    }
                }
                var kept = new Json.Array ();
                packages.foreach_element ((_, __, node) => {
                    (node.get_object ().get_string_member ("name") in names ? removed : kept).add_element (node.copy ());
                });
                packages = kept;
            }
            return envelope ("ok", array_node (removed));
        }

        /* The stand-in read error, as an envelope; null when reads answer their scenario. */
        string? read_failure () {
            string? error;
            lock (hook_gate) {
                error = read_error;
            }
            if (error == null) {
                return null;
            }
            return envelope ("error", parse_or_null (error));
        }

        /* Waits out a test's delay for `call`. The scenario itself is unchanged. */
        void hold (string call) {
            uint milliseconds = 0;
            lock (hook_gate) {
                if (delayed_call == call) {
                    milliseconds = delayed_ms;
                }
            }
            if (milliseconds > 0) {
                Thread.usleep (milliseconds * 1000);
            }
        }

        Scenario pick (string operation, string fallback) {
            lock (playing) {
                var name = playing[operation];
                return scenarios[name != null && scenarios.contains (name) ? name : fallback];
            }
        }

        /* Plays `scenario`'s script; an error envelope when a cancel stopped it, else null. */
        string? replay (Scenario scenario, EventFunc? report, ChooseFunc? choose, StopFunc? stop, Cancellable? cancel) {
            for (uint i = 0; i < scenario.script.get_length (); i++) {
                if (cancel != null && cancel.is_cancelled ()) {
                    return envelope ("error", parse_or_null ("{\"type\": \"cancelled\"}"));
                }
                var step = scenario.script.get_object_element (i);
                if (step.get_string_member ("type") == "event") {
                    if (report != null) {
                        report (to_json (step.get_member ("event")));
                    }
                } else {
                    var question = step.get_object_member ("question");
                    if (question.get_string_member ("type") == "choose_binary" && choose != null) {
                        choose (question.get_string_member ("package"), to_json (question.get_member ("candidates")));
                    } else if (question.get_string_member ("type") == "stop_processes" && stop != null) {
                        stop (to_json (question.get_member ("holders")));
                    }
                }
                if (step_delay_ms > 0) {
                    Thread.usleep (step_delay_ms * 1000);
                }
            }
            return null;
        }

        string? version_of (string name) {
            string? version = null;
            lock (packages) {
                packages.foreach_element ((_, __, node) => {
                    if (node.get_object ().get_string_member ("name") == name) {
                        version = node.get_object ().get_string_member ("version");
                    }
                });
            }
            return version;
        }

        /* Puts `package` in the installed list in place of its old record, kept sorted by name. */
        void place (Json.Object package) {
            var name = package.get_string_member ("name");
            lock (packages) {
                var next = new Json.Array ();
                var added = false;
                packages.foreach_element ((_, __, node) => {
                    var other = node.get_object ().get_string_member ("name");
                    if (!added && strcmp (name, other) <= 0) {
                        next.add_object_element (package.ref ());
                        added = true;
                    }
                    if (other != name) {
                        next.add_element (node.copy ());
                    }
                });
                if (!added) {
                    next.add_object_element (package.ref ());
                }
                packages = next;
            }
        }

        static bool same (Json.Array? call, string[] specs) {
            if (call == null || call.get_length () != specs.length) {
                return false;
            }
            for (uint i = 0; i < specs.length; i++) {
                if (call.get_string_element (i) != specs[i]) {
                    return false;
                }
            }
            return true;
        }

        static Json.Node array_node (Json.Array array) {
            var node = new Json.Node (Json.NodeType.ARRAY);
            node.set_array (array);
            return node;
        }

        static string quote (string text) {
            var node = new Json.Node (Json.NodeType.VALUE);
            node.set_string (text);
            return to_json (node);
        }

        /* Parses JSON this file wrote itself, so it cannot fail. */
        static Json.Node parse_or_null (string json) {
            try {
                return parse_json (json);
            } catch (Error e) {
                return new Json.Node (Json.NodeType.NULL);
            }
        }
    }
}
