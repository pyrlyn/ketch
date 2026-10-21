/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * The contract scenarios in desktop/contract/scenarios, read with json-glib.
 *
 * They are generated from ketch-ffi's Rust values (crates/ketch-ffi/tests/
 * contract.rs) and are the same wire shapes ketch-capi answers with, so the
 * fake keeps them as JSON instead of mapping them onto app types: the store
 * parses what the fake hands it exactly as it will parse the C ABI's answers.
 */
namespace KetchApp {
    public errordomain ScenarioError {
        INVALID
    }

    public Json.Node parse_json (string json) throws Error {
        var parser = new Json.Parser ();
        parser.load_from_data (json);
        var root = parser.get_root ();
        if (root == null) {
            throw new ScenarioError.INVALID ("empty JSON");
        }
        return root;
    }

    public string to_json (Json.Node node) {
        return Json.to_string (node, false);
    }

    /* `{"<key>": <value>}`, the envelope every Core call answers with. */
    public string envelope (string key, Json.Node value) {
        var reply = new Json.Object ();
        reply.set_member (key, value.copy ());
        var node = new Json.Node (Json.NodeType.OBJECT);
        node.set_object (reply);
        return to_json (node);
    }

    public class Scenario : Object {
        public string name { get; private set; }
        public string description { get; private set; }
        public string operation { get; private set; }
        public Json.Object call { get; private set; }
        public Json.Array script { get; private set; }
        /* "ok" or "error". */
        public string outcome { get; private set; }
        /* The returned value when `outcome` is "ok", the KetchError otherwise. */
        public Json.Node result { get; private set; }

        public Scenario.parse (string json) throws Error {
            var root = parse_json (json);
            var file = object_of (root, "scenario");
            name = string_member (file, "name");
            description = string_member (file, "description");
            call = object_of (file.get_member ("call"), "call");
            operation = string_member (call, "operation");
            var steps = file.get_member ("script");
            if (steps == null || steps.get_node_type () != Json.NodeType.ARRAY) {
                throw new ScenarioError.INVALID ("%s: script is not an array", name);
            }
            script = steps.get_array ();
            var end = object_of (file.get_member ("outcome"), "outcome");
            outcome = string_member (end, "type");
            var key = outcome == "ok" ? "value" : "error";
            if (!end.has_member (key) || (outcome != "ok" && outcome != "error")) {
                throw new ScenarioError.INVALID ("%s: outcome %s has no %s", name, outcome, key);
            }
            result = end.get_member (key);
        }

        /* What the core answered, as an envelope. */
        public string answer () {
            return envelope (outcome, result);
        }

        /* Every scenario in `dir`, by file name. */
        public static GenericArray<Scenario> load_dir (string dir) throws Error {
            var names = new GenericArray<string> ();
            var listing = Dir.open (dir);
            for (var entry = listing.read_name (); entry != null; entry = listing.read_name ()) {
                if (entry.has_suffix (".json")) {
                    names.add (entry);
                }
            }
            names.sort (strcmp);
            var all = new GenericArray<Scenario> ();
            foreach (var file in names) {
                string text;
                FileUtils.get_contents (Path.build_filename (dir, file), out text);
                all.add (new Scenario.parse (text));
            }
            return all;
        }

        static Json.Object object_of (Json.Node? node, string what) throws ScenarioError {
            if (node == null || node.get_node_type () != Json.NodeType.OBJECT) {
                throw new ScenarioError.INVALID ("%s is not an object", what);
            }
            return node.get_object ();
        }

        static string string_member (Json.Object o, string key) throws ScenarioError {
            var node = o.get_member (key);
            if (node == null || node.get_value_type () != typeof (string)) {
                throw new ScenarioError.INVALID ("%s is not a string", key);
            }
            return node.get_string ();
        }
    }
}
