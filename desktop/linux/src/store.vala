/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * What every screen binds to: the installed packages, the updates, the
 * running operation, the log and the questions the core is waiting on. It
 * ports KetchStore.cs (and the macOS KetchStore) without settings or links.
 *
 * The core blocks, so each call runs on its own thread. Events and questions
 * arrive on that thread and are handed to the main context with Idle.add,
 * so every property changes on the main loop in the order the core sent
 * them. A question blocks the core's thread on a Gate until a dialog answers.
 */
namespace KetchApp {
    public delegate void RetryFunc ();

    public class Store : Object {
        const uint LOG_LIMIT = 500;

        public Core core { get; construct; }
        public ListStore installed { get; default = new ListStore (typeof (Package)); }
        /* What `upgrade` would apply. */
        public ListStore updates { get; default = new ListStore (typeof (Upgrade)); }
        /* What a lock file or a pin holds back. */
        public ListStore held { get; default = new ListStore (typeof (Upgrade)); }
        public ListStore search_results { get; default = new ListStore (typeof (RegistryPackage)); }
        public ListStore findings { get; default = new ListStore (typeof (Finding)); }
        public ListStore log { get; default = new ListStore (typeof (LogEntry)); }
        public uint problem_count { get; private set; }
        public Activity? activity { get; private set; }
        public BusyState? busy { get; private set; }
        /* The alert to show; the screen clears it when dismissed. */
        public string? error_message { get; set; }
        public BinaryChoice? pending_choice { get; private set; }
        public StopQuestion? pending_stop { get; private set; }

        /* An operation succeeded; the window shows a toast. */
        public signal void finished (string title);

        Cancellable? cancellable;
        RetryFunc? retry;
        HashTable<int64?, string> downloads = new HashTable<int64?, string> (int64_hash, int64_equal);

        public Store (Core core) {
            Object (core: core);
        }

        /* Re-reads installed and outdated packages; on launch and after every operation. */
        public async void refresh () {
            var now = take (yield background (() => core.installed ()), () => refresh.begin ());
            var outdated = take (yield background (() => core.outdated (null)), () => refresh.begin ());
            if (now != null) {
                fill (installed, now, (o) => new Package (o));
            }
            if (outdated != null) {
                fill (updates, outdated, (o) => new Upgrade (o));
                fill (held, outdated, (o) => new Upgrade (o));
                filter (updates, true);
                filter (held, false);
            }
        }

        public async void search (string query) {
            var found = take (yield background (() => core.search (query, 50, null)), null);
            if (found != null && found.get_node_type () == Json.NodeType.OBJECT) {
                fill (search_results, found.get_object ().get_member ("known"), (o) => new RegistryPackage (o));
            }
        }

        /* The changelog between two versions as Markdown, newest first; null when the core could not read it. */
        public async string? changelog (string name, string? from, string? to) {
            var sections = take (yield background (() => core.changelog_range (name, from, to, null)), null);
            if (sections == null || sections.get_node_type () != Json.NodeType.ARRAY) {
                return null;
            }
            var text = new StringBuilder ();
            sections.get_array ().foreach_element ((_, __, node) => {
                var o = node.get_object ();
                text.append_printf ("%s## %s\n\n%s", text.len > 0 ? "\n\n" : "",
                                    maybe_text (o, "heading") ?? text_of (o, "version"), text_of (o, "body"));
            });
            return text.str;
        }

        public async void run_doctor () {
            var checks = take (yield background (() => core.doctor (null)), () => run_doctor.begin ());
            if (checks != null) {
                fill (findings, checks, (o) => new Finding (o));
                uint problems = 0;
                for (uint i = 0; i < findings.get_n_items (); i++) {
                    problems += ((Finding) findings.get_item (i)).outcome != "ok" ? 1 : 0;
                }
                problem_count = problems;
            }
        }

        public async void install (string spec) {
            yield operate ("Installing %s".printf (spec), "install", { spec }, () => install.begin (spec));
        }

        /* Upgrades `names`, or everything outdated when it is empty. */
        public async void upgrade (string[] names) {
            var title = names.length == 0 ? "Upgrading all packages" : "Upgrading " + string.joinv (", ", names);
            yield operate (title, "upgrade", names, () => upgrade.begin (names));
        }

        public async void uninstall (string[] names) {
            yield operate ("Uninstalling " + string.joinv (", ", names), "uninstall", names, () => uninstall.begin (names));
        }

        /* Stops the running operation at its next step, declining any open question so the core is not left waiting. */
        public void cancel () {
            if (cancellable == null) {
                return;
            }
            if (activity != null) {
                activity.cancelling = true;
            }
            cancellable.cancel ();
            answer_choice (-1);
            answer_stop (false);
        }

        /* Runs again what the held lock refused; the banner comes back if it is still held. */
        public void retry_busy () {
            if (busy == null || retry == null) {
                return;
            }
            busy = null;
            retry ();
        }

        /* An index into the candidates, or a negative number to decline. */
        public void answer_choice (int64 index) {
            var pending = pending_choice;
            if (pending != null) {
                pending_choice = null;
                pending.reply (index);
            }
        }

        public void answer_stop (bool stop) {
            var pending = pending_stop;
            if (pending != null) {
                pending_stop = null;
                pending.reply (stop ? 1 : 0);
            }
        }

        async void operate (string title, string operation, string[] args, owned RetryFunc again) {
            if (activity != null) {
                return;
            }
            var cancel = new Cancellable ();
            cancellable = cancel;
            activity = new Activity (title);
            busy = null;
            append (LogLevel.INFO, title);
            EventFunc report = (event) => post (() => apply (event));
            ChooseFunc choose = (package, candidates) => ask (new BinaryChoice (package, strings (candidates)), cancel);
            StopFunc stop = (holders) => ask (new StopQuestion (holder_lines (holders)), cancel) == 1;
            var answer = yield background (() => {
                switch (operation) {
                case "install":
                    return core.install (args, null, report, choose, stop, cancel);
                case "upgrade":
                    return core.upgrade (args, report, choose, stop, cancel);
                default:
                    return core.uninstall (args, report, choose, stop, cancel);
                }
            });
            var done = take (answer, (owned) again) != null;
            cancellable = null;
            activity = null;
            pending_choice = null;
            pending_stop = null;
            downloads.remove_all ();
            if (done) {
                append (LogLevel.SUCCESS, "Done: " + title);
                finished (title);
            }
            yield refresh ();
        }

        /* Puts a question in front of the person and blocks the core's thread until it is answered. */
        int64 ask (Gate question, Cancellable cancel) {
            if (cancel.is_cancelled ()) {
                return -1;
            }
            post (() => {
                if (cancel.is_cancelled ()) {
                    question.reply (-1);
                } else if (question is BinaryChoice) {
                    answer_choice (-1);
                    pending_choice = (BinaryChoice) question;
                } else {
                    answer_stop (false);
                    pending_stop = (StopQuestion) question;
                }
            });
            return question.wait ();
        }

        void apply (string event) {
            Json.Object? e = null;
            try {
                e = parse_json (event).get_object ();
            } catch (Error error) {
                e = null;
            }
            if (e == null) {
                return;
            }
            var package = text_of (e, "package");
            var detail = text_of (e, "detail");
            var id = e.get_int_member_with_default ("id", -1);
            switch (text_of (e, "type")) {
            case "step":
                if (activity != null) {
                    activity.stage (package, text_of (e, "stage"));
                }
                append (LogLevel.INFO, "%s: %s".printf (package, text_of (e, "stage")));
                break;
            case "began":
                var task = e.get_member ("task");
                if (task != null && task.get_node_type () == Json.NodeType.OBJECT) {
                    downloads[id] = text_of (task.get_object (), "label");
                }
                break;
            case "progress":
                if (downloads.contains (id) && activity != null) {
                    activity.downloaded (downloads[id], e.get_int_member_with_default ("done", 0),
                                          e.get_int_member_with_default ("total", 0));
                }
                break;
            case "ended":
            case "abandoned":
                downloads.remove (id);
                break;
            case "status":
            case "success":
                var line = (text_of (e, "verb") + " " + detail).strip ();
                if (activity != null) {
                    activity.status = line;
                }
                append (LogLevel.INFO, line);
                break;
            case "warn":
                append (LogLevel.WARNING, detail);
                break;
            case "note":
                append (LogLevel.INFO, detail);
                break;
            }
        }

        /* `ok`'s value, or null after showing the error: Busy as the banner with Retry, Cancelled in the log, the rest as an alert. */
        Json.Node? take (string answer, owned RetryFunc? again) {
            Json.Object? reply = null;
            try {
                reply = parse_json (answer).get_object ();
            } catch (Error e) {
                reply = null;
            }
            if (reply != null && reply.has_member ("ok")) {
                return reply.get_member ("ok");
            }
            var error = reply != null && reply.has_member ("error") && reply.get_member ("error").get_node_type () == Json.NodeType.OBJECT
                        ? reply.get_object_member ("error") : new Json.Object ();
            var message = text_of (error, "message");
            switch (text_of (error, "type")) {
            case "busy":
                var state = new BusyState (error.get_int_member_with_default ("pid", -1));
                append (LogLevel.WARNING, state.message);
                if (again != null) {
                    retry = (owned) again;
                    busy = state;
                }
                return null;
            case "cancelled":
                append (LogLevel.WARNING, "Cancelled");
                return null;
            case "not_found":
                message = "No package named %s.".printf (text_of (error, "name"));
                break;
            case "network":
                message = "Network error: " + message;
                break;
            case "verification":
                message = "Verification failed: " + message;
                break;
            case "other":
                break;
            default:
                message = "The core answered something this app cannot read.";
                break;
            }
            append (LogLevel.ERROR, message);
            error_message = message;
            return null;
        }

        void append (LogLevel level, string message) {
            log.append (new LogEntry (level, message));
            if (log.get_n_items () > LOG_LIMIT) {
                log.remove (0);
            }
        }

        delegate Object Make (Json.Object o);

        static void fill (ListStore list, Json.Node? array, Make make) {
            Object[] items = {};
            if (array != null && array.get_node_type () == Json.NodeType.ARRAY) {
                array.get_array ().foreach_element ((_, __, node) => {
                    if (node.get_node_type () == Json.NodeType.OBJECT) {
                        items += make (node.get_object ());
                    }
                });
            }
            list.splice (0, list.get_n_items (), items);
        }

        /* Keeps the upgrades that are, or are not, held back. */
        static void filter (ListStore list, bool free) {
            for (uint i = list.get_n_items (); i > 0; i--) {
                if ((((Upgrade) list.get_item (i - 1)).held_by == null) != free) {
                    list.remove (i - 1);
                }
            }
        }

        static string[] strings (string json) {
            string[] all = {};
            array_of (json).foreach_element ((_, __, node) => {
                all += node.get_value_type () == typeof (string) ? node.get_string () : "";
            });
            return all;
        }

        static string[] holder_lines (string json) {
            string[] lines = {};
            array_of (json).foreach_element ((_, __, node) => {
                if (node.get_node_type () == Json.NodeType.OBJECT) {
                    var o = node.get_object ();
                    lines += "%s %s".printf (o.get_int_member_with_default ("pid", 0).to_string (), text_of (o, "path"));
                }
            });
            return lines;
        }

        /* `json` as an array; empty when it is not one. */
        static Json.Array array_of (string json) {
            try {
                var node = parse_json (json);
                if (node.get_node_type () == Json.NodeType.ARRAY) {
                    return node.dup_array ();
                }
            } catch (Error e) {
                return new Json.Array ();
            }
            return new Json.Array ();
        }

        delegate void Job ();

        static void post (owned Job work) {
            Idle.add (() => {
                work ();
                return Source.REMOVE;
            });
        }

        delegate string Work ();

        /* Runs a blocking core call on its own thread: one that waits on a question would starve a pool. */
        static async string background (owned Work work) {
            string answer = "";
            SourceFunc resume = background.callback;
            new Thread<bool> ("ketch-core", () => {
                answer = work ();
                Idle.add ((owned) resume);
                return true;
            });
            yield;
            return answer;
        }
    }
}
