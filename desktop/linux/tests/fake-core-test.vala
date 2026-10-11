/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * The contract scenarios decode, and FakeCore answers and replays them the
 * way ketch-capi answers: envelopes, events as JSON, questions to callbacks.
 */
using KetchApp;

string scenario_dir () {
    var dir = Environment.get_variable ("KETCH_SCENARIOS");
    if (dir == null) {
        error ("KETCH_SCENARIOS is not set; run under meson test");
    }
    return dir;
}

FakeCore new_core () {
    try {
        return new FakeCore (scenario_dir ());
    } catch (Error e) {
        error ("no fake core: %s", e.message);
    }
}

Json.Object reply (string answer) {
    try {
        return parse_json (answer).get_object ();
    } catch (Error e) {
        error ("not JSON (%s): %s", e.message, answer);
    }
}

Json.Node ok (string answer) {
    var envelope = reply (answer);
    if (!envelope.has_member ("ok")) {
        error ("expected ok: %s", answer);
    }
    return envelope.get_member ("ok");
}

Json.Object failure (string answer) {
    var envelope = reply (answer);
    if (!envelope.has_member ("error")) {
        error ("expected an error: %s", answer);
    }
    return envelope.get_object_member ("error");
}

/* The names, comma-separated, to compare in one assert. */
string listed (Json.Array packages) {
    return string.joinv (",", names_of (packages));
}

string[] names_of (Json.Array packages) {
    string[] names = {};
    packages.foreach_element ((_, __, node) => names += node.get_object ().get_string_member ("name"));
    return names;
}

string version_of (FakeCore core, string name) {
    string version = "";
    ok (core.installed ()).get_array ().foreach_element ((_, __, node) => {
        if (node.get_object ().get_string_member ("name") == name) {
            version = node.get_object ().get_string_member ("version");
        }
    });
    return version;
}

/* The event types a call reported, in order. */
class Events {
    public string[] types = {};

    public void take (string event) {
        types += reply (event).get_string_member ("type");
    }
}

void every_scenario_file_decodes () {
    GenericArray<Scenario> all;
    try {
        all = Scenario.load_dir (scenario_dir ());
    } catch (Error e) {
        error ("scenarios do not load: %s", e.message);
    }
    assert (all.length >= 18);
    string[] operations = { "installed", "search", "outdated", "install", "upgrade", "uninstall",
                            "changelog_range", "doctor" };
    foreach (var scenario in all.data) {
        assert (scenario.operation in operations);
        assert (reply (scenario.answer ()).has_member (scenario.outcome));
    }
}

void reads_answer_the_scenario_values () {
    var core = new_core ();
    var installed = names_of (ok (core.installed ()).get_array ());
    assert (installed.length == 2 && installed[0] == "fd" && installed[1] == "ripgrep");
    var checks = ok (core.doctor (null)).get_array ();
    assert (checks.get_length () == 3);
    assert (checks.get_object_element (2).get_string_member ("outcome") == "fail");
    var sections = ok (core.changelog_range ("ripgrep", "14.1.0", "14.1.1", null)).get_array ();
    assert (sections.get_object_element (0).get_string_member ("version") == "14.1.1");
    assert (core.root () == FakeCore.ROOT);
}

void search_matches_names_and_descriptions_case_insensitively () {
    var core = new_core ();
    var all = ok (core.search ("", 0, null)).get_object ().get_array_member ("known").get_length ();
    assert (all > 0);
    var byname = ok (core.search ("RIPgrep", 10, null)).get_object ();
    assert (names_of (byname.get_array_member ("known"))[0] == "ripgrep");
    assert (byname.has_member ("repositories"));
    var bydescription = ok (core.search ("line-oriented", 10, null)).get_object ();
    assert (names_of (bydescription.get_array_member ("known"))[0] == "ripgrep");
    assert (ok (core.search ("no such tool", 10, null)).get_object ().get_array_member ("known").get_length () == 0);
    assert (ok (core.search ("", 1, null)).get_object ().get_array_member ("known").get_length () == 1);
}

void outdated_keeps_held_packages_and_drops_upgraded_ones () {
    var core = new_core ();
    var before = ok (core.outdated (null)).get_array ();
    assert (before.get_length () == 2);
    assert (before.get_object_element (1).get_string_member ("held_by") == "/work/app/ketch.lock");
    ok (core.upgrade ({ "ripgrep" }, null, null, null, null));
    assert (listed (ok (core.outdated (null)).get_array ()) == "fd");
}

void a_scripted_install_replays_its_events_and_places_the_package () {
    var core = new_core ();
    var events = new Events ();
    var placed = ok (core.install ({ "ripgrep" }, null, events.take, null, null, null)).get_array ();
    assert (placed.get_length () == 1);
    assert (events.types.length == 11);
    assert (events.types[0] == "step" && events.types[10] == "success");
    assert ("progress" in events.types && "began" in events.types && "ended" in events.types);
    assert (version_of (core, "ripgrep") == "14.1.1");
}

void a_matching_install_asks_which_binary_and_the_callback_answers () {
    var core = new_core ();
    string? asked = null;
    string? candidates = null;
    ok (core.install ({ "uv" }, null, null, (package, list) => {
        asked = package;
        candidates = list;
        return 1;
    }, null, null));
    assert (asked == "uv");
    assert (candidates == "[\"uv\",\"uvx\"]");
    assert (version_of (core, "uv") == "0.9.0");
}

void declining_the_binary_choice_is_not_a_cancel () {
    var core = new_core ();
    ok (core.install ({ "uv" }, null, null, (package, list) => -1, null, null));
    assert (version_of (core, "uv") == "0.9.0");
}

void the_upgrade_asks_before_stopping_the_processes_that_hold_the_files () {
    var core = new_core ();
    string? holders = null;
    ok (core.upgrade ({ "ripgrep" }, null, null, (list) => {
        holders = list;
        return false;
    }, null));
    var held = reply ("{\"h\": %s}".printf (holders)).get_array_member ("h").get_object_element (0);
    assert (held.get_int_member ("pid") == 5150);
    assert (held.get_string_member ("path") == "/Users/me/.ketch/bin/rg");
    // Refusing leaves them running and the upgrade goes on, as in the core.
    assert (version_of (core, "ripgrep") == "14.1.1");
}

void a_cancel_before_the_first_step_stops_with_cancelled () {
    var core = new_core ();
    var cancel = new Cancellable ();
    cancel.cancel ();
    var events = new Events ();
    var error = failure (core.install ({ "ripgrep" }, null, events.take, null, null, cancel));
    assert (error.get_string_member ("type") == "cancelled");
    assert (events.types.length == 0);
    assert (version_of (core, "ripgrep") == "14.1.0");
}

void cancelling_while_a_question_is_open_stops_after_it () {
    var core = new_core ();
    var cancel = new Cancellable ();
    var events = new Events ();
    var error = failure (core.install ({ "uv" }, null, events.take, (package, list) => {
        cancel.cancel ();
        return -1;
    }, null, cancel));
    assert (error.get_string_member ("type") == "cancelled");
    assert (!("success" in events.types));
    assert (version_of (core, "uv") == "");
}

void a_busy_error_carries_the_pid_and_an_unknown_holder_has_none () {
    var core = new_core ();
    core.play ("install", "install-busy");
    var busy = failure (core.install ({ "ripgrep" }, null, null, null, null, null));
    assert (busy.get_string_member ("type") == "busy" && busy.get_int_member ("pid") == 4242);
    core.play ("install", "install-busy-unknown-holder");
    var unknown = failure (core.install ({ "ripgrep" }, null, null, null, null, null));
    assert (unknown.get_string_member ("type") == "busy" && unknown.get_null_member ("pid"));
}

void a_scripted_failure_reports_its_events_then_errs_and_places_nothing () {
    var core = new_core ();
    core.play ("install", "install-network-failure");
    var events = new Events ();
    var error = failure (core.install ({ "ripgrep" }, null, events.take, null, null, null));
    assert (error.get_string_member ("type") == "network");
    assert ("503" in error.get_string_member ("message"));
    assert (events.types.length == 6);
    assert (version_of (core, "ripgrep") == "14.1.0");
    // An unknown spec plays its own scenario when nothing is played.
    core.play ("install", null);
    assert (failure (core.install ({ "nope" }, null, null, null, null, null)).get_string_member ("name") == "nope");
}

void playing_a_scenario_replaces_what_the_fake_answers_until_cleared () {
    var core = new_core ();
    core.play ("upgrade", "upgrade-nothing-to-do");
    assert (ok (core.upgrade ({}, null, null, null, null)).get_array ().get_length () == 0);
    assert (version_of (core, "ripgrep") == "14.1.0");
    core.play ("upgrade", null);
    assert (ok (core.upgrade ({}, null, null, null, null)).get_array ().get_length () == 1);
    assert ("install-ok" in core.names ());
    assert (core.operation_of ("upgrade-stops-processes") == "upgrade" && core.operation_of ("nope") == null);
}

void uninstall_removes_the_names_asked_for () {
    var core = new_core ();
    var removed = ok (core.uninstall ({ "fd" }, null, null, null, null)).get_array ();
    assert (listed (removed) == "fd");
    assert (listed (ok (core.installed ()).get_array ()) == "ripgrep");
    var missing = failure (core.uninstall ({ "fd" }, null, null, null, null));
    assert (missing.get_string_member ("type") == "not_found" && missing.get_string_member ("name") == "fd");
}

int main (string[] args) {
    Test.init (ref args);
    Test.add_func ("/scenario/every-file-decodes", every_scenario_file_decodes);
    Test.add_func ("/fake/reads-answer-the-scenario-values", reads_answer_the_scenario_values);
    Test.add_func ("/fake/search-matches-names-and-descriptions", search_matches_names_and_descriptions_case_insensitively);
    Test.add_func ("/fake/outdated-keeps-held-and-drops-upgraded", outdated_keeps_held_packages_and_drops_upgraded_ones);
    Test.add_func ("/fake/scripted-install-replays-and-places", a_scripted_install_replays_its_events_and_places_the_package);
    Test.add_func ("/fake/install-asks-which-binary", a_matching_install_asks_which_binary_and_the_callback_answers);
    Test.add_func ("/fake/declined-choice-is-not-a-cancel", declining_the_binary_choice_is_not_a_cancel);
    Test.add_func ("/fake/upgrade-asks-to-stop-processes", the_upgrade_asks_before_stopping_the_processes_that_hold_the_files);
    Test.add_func ("/fake/cancel-before-first-step", a_cancel_before_the_first_step_stops_with_cancelled);
    Test.add_func ("/fake/cancel-while-question-open", cancelling_while_a_question_is_open_stops_after_it);
    Test.add_func ("/fake/busy-carries-pid-or-none", a_busy_error_carries_the_pid_and_an_unknown_holder_has_none);
    Test.add_func ("/fake/scripted-failure-places-nothing", a_scripted_failure_reports_its_events_then_errs_and_places_nothing);
    Test.add_func ("/fake/play-replaces-until-cleared", playing_a_scenario_replaces_what_the_fake_answers_until_cleared);
    Test.add_func ("/fake/uninstall-removes-names-asked-for", uninstall_removes_the_names_asked_for);
    return Test.run ();
}
