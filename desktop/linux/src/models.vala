/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * The records the screens show, read from the core's JSON. They are GObjects
 * so the store can keep them in GLib.ListStores that GTK list views bind to
 * directly. A member the JSON lacks reads as empty rather than failing: the
 * answer came from another component, and a blank cell beats a crash.
 */
namespace KetchApp {
    string text_of (Json.Object o, string key) {
        return o.get_string_member_with_default (key, "") ?? "";
    }

    string? maybe_text (Json.Object o, string key) {
        var text = text_of (o, key);
        return text == "" ? null : text;
    }

    public class Package : Object {
        public string name { get; construct; }
        public string version { get; construct; }
        /* `github:owner/repo`, `local:/path`, … */
        public string source { get; construct; }
        public string prefix { get; construct; }
        public bool pinned { get; construct; }
        /* The links on PATH. */
        public string[] binaries { get; construct; }

        public Package (Json.Object o) {
            string[] links = {};
            var list = o.get_member ("binaries");
            if (list != null && list.get_node_type () == Json.NodeType.ARRAY) {
                list.get_array ().foreach_element ((_, __, node) => {
                    if (node.get_value_type () == typeof (string)) {
                        links += node.get_string ();
                    }
                });
            }
            Object (name: text_of (o, "name"), version: text_of (o, "version"), source: text_of (o, "source"),
                    prefix: text_of (o, "prefix"), pinned: o.get_boolean_member_with_default ("pinned", false),
                    binaries: links);
        }
    }

    public class Upgrade : Object {
        public string name { get; construct; }
        public string installed { get; construct; }
        public string latest { get; construct; }
        /* The ketch.lock holding the package back, or "a pin" for a pin with no lock. */
        public string? held_by { get; construct; }

        public Upgrade (Json.Object o) {
            var pinned = o.get_boolean_member_with_default ("pinned", false);
            Object (name: text_of (o, "name"), installed: text_of (o, "installed"), latest: text_of (o, "latest"),
                    held_by: pinned ? (maybe_text (o, "held_by") ?? "a pin") : null);
        }
    }

    public class RegistryPackage : Object {
        public string name { get; construct; }
        public string source { get; construct; }
        public string? description { get; construct; }
        public string? latest { get; construct; }

        public RegistryPackage (Json.Object o) {
            Object (name: text_of (o, "name"), source: text_of (o, "source"),
                    description: maybe_text (o, "description"), latest: maybe_text (o, "latest"));
        }
    }

    public class Finding : Object {
        public string name { get; construct; }
        /* "ok", "warn" or "fail". */
        public string outcome { get; construct; }
        public string detail { get; construct; }
        /* What fixes it, when the core knows. */
        public string? fix { get; construct; }

        public Finding (Json.Object o) {
            Object (name: text_of (o, "name"), outcome: text_of (o, "outcome"), detail: text_of (o, "detail"),
                    fix: maybe_text (o, "fix"));
        }
    }

    public enum LogLevel { INFO, SUCCESS, WARNING, ERROR }

    public class LogEntry : Object {
        public LogLevel level { get; construct; }
        public string message { get; construct; }
        public DateTime time { get; construct; }

        public LogEntry (LogLevel level, string message) {
            Object (level: level, message: message, time: new DateTime.now_local ());
        }
    }

    /* The running operation: its title, last status and overall progress. */
    public class Activity : Object {
        /* The core's stages in pipeline order; each is an equal share of a package's bar. */
        const string[] STAGES = { "resolving", "downloading", "verifying", "trusting", "extracting", "installing" };

        public string title { get; construct; }
        public string? status { get; set; }
        public bool cancelling { get; set; }
        /* 0..1, the mean of every package's bar. */
        public double fraction { get; private set; }

        HashTable<string, double?> packages = new HashTable<string, double?> (str_hash, str_equal);
        HashTable<string, string> stage_of = new HashTable<string, string> (str_hash, str_equal);

        public Activity (string title) {
            Object (title: title);
        }

        public void stage (string package, string stage) {
            stage_of[package] = stage;
            set_share (package, index (stage), 0);
        }

        /* Download fills its stage's share as bytes arrive, when the total is known. */
        public void downloaded (string package, uint64 done, uint64 total) {
            stage_of[package] = "downloading";
            set_share (package, index ("downloading"), total > 0 ? double.min (1.0, (double) done / total) : 0);
        }

        void set_share (string package, int stage, double within) {
            packages[package] = (stage + within) / STAGES.length;
            double sum = 0;
            packages.foreach ((_, share) => sum += share ?? 0);
            fraction = sum / packages.size ();
        }

        static int index (string stage) {
            for (int i = 0; i < STAGES.length; i++) {
                if (STAGES[i] == stage) {
                    return i;
                }
            }
            return 0;
        }
    }

    /* An answer a question waits for; the core's thread blocks in `wait`. */
    public class Gate : Object {
        AsyncQueue<Gate> answered = new AsyncQueue<Gate> ();
        bool open = false;
        public int64 answer { get; private set; }

        public void reply (int64 value) {
            lock (open) {
                if (open) {
                    return;
                }
                open = true;
                answer = value;
            }
            answered.push (this);
        }

        public int64 wait () {
            return answered.pop ().answer;
        }
    }

    /* Which of several binaries to link; the core waits until `store.answer_choice`. */
    public class BinaryChoice : Gate {
        public string package { get; construct; }
        public string[] candidates { get; construct; }

        public BinaryChoice (string package, string[] candidates) {
            Object (package: package, candidates: candidates);
        }
    }

    /* Processes that hold files the operation replaces, each "pid path". */
    public class StopQuestion : Gate {
        public string[] holders { get; construct; }

        public StopQuestion (string[] holders) {
            Object (holders: holders);
        }
    }

    /* Another ketch process holds the lock; the store keeps what Retry runs. */
    public class BusyState : Object {
        /* -1 when the core could not tell. */
        public int64 pid { get; construct; }

        public string message {
            owned get {
                return pid >= 0 ? "ketch is running in another process (pid %s).".printf (pid.to_string ())
                                : "ketch is running in another process.";
            }
        }

        public BusyState (int64 pid) {
            Object (pid: pid);
        }
    }
}
