/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * The store on the fake core: its states, the busy banner and Retry, the
 * questions waiting on dialogs, cancel, and what reaches the log. The
 * cases of the Windows app's KetchStoreTests.
 */
using KetchApp;

delegate bool Check ();

/* Spins the main loop until `done`, or fails the test after five seconds. */
void run_until (Check done) {
    var deadline = get_monotonic_time () + 5 * TimeSpan.SECOND;
    while (!done ()) {
        if (get_monotonic_time () > deadline) {
            error ("timed out waiting on the store");
        }
        MainContext.default ().iteration (false);
        Thread.usleep (1000);
    }
}

FakeCore fake;

Store new_store () {
    try {
        fake = new FakeCore (Environment.get_variable ("KETCH_SCENARIOS"));
    } catch (Error e) {
        error ("no fake core: %s", e.message);
    }
    return new Store (fake);
}

void refresh (Store store) {
    var done = false;
    store.refresh.begin ((_, res) => {
        store.refresh.end (res);
        done = true;
    });
    run_until (() => done);
}

/* Starts an install and returns a check that is true once it has finished. */
Check start_install (Store store, string spec) {
    var done = false;
    store.install.begin (spec, (_, res) => {
        store.install.end (res);
        done = true;
    });
    return () => done;
}

string? version_of (Store store, string name) {
    for (uint i = 0; i < store.installed.get_n_items (); i++) {
        var package = (Package) store.installed.get_item (i);
        if (package.name == name) {
            return package.version;
        }
    }
    return null;
}

bool logged (Store store, LogLevel level, string message) {
    for (uint i = 0; i < store.log.get_n_items (); i++) {
        var entry = (LogEntry) store.log.get_item (i);
        if (entry.level == level && entry.message == message) {
            return true;
        }
    }
    return false;
}

void refresh_splits_updates_from_held_packages () {
    var store = new_store ();
    refresh (store);
    assert (store.installed.get_n_items () == 2);
    assert (string.joinv (",", ((Package) store.installed.get_item (0)).binaries) == "/Users/me/.ketch/bin/fd");
    assert (store.updates.get_n_items () == 1 && ((Upgrade) store.updates.get_item (0)).name == "ripgrep");
    var held = (Upgrade) store.held.get_item (0);
    assert (store.held.get_n_items () == 1 && held.name == "fd" && held.held_by == "/work/app/ketch.lock");
}

void install_logs_its_stages_and_refreshes_the_list () {
    var store = new_store ();
    string? toast = null;
    store.finished.connect ((title) => toast = title);
    run_until (start_install (store, "ripgrep"));
    assert (logged (store, LogLevel.INFO, "ripgrep: downloading"));
    assert (logged (store, LogLevel.INFO, "installed ripgrep 14.1.1"));
    assert (logged (store, LogLevel.SUCCESS, "Done: Installing ripgrep"));
    assert (toast == "Installing ripgrep");
    assert (store.activity == null);
    assert (version_of (store, "ripgrep") == "14.1.1");
    assert (store.updates.get_n_items () == 0);
}

void an_unknown_package_shows_the_cores_wording_as_an_alert () {
    var store = new_store ();
    run_until (start_install (store, "nope"));
    assert (store.error_message == "No package named nope.");
    assert (logged (store, LogLevel.ERROR, "No package named nope."));
    fake.play ("install", "install-network-failure");
    run_until (start_install (store, "ripgrep"));
    assert (store.error_message.has_prefix ("Network error: HTTP 503"));
}

void a_held_lock_shows_the_banner_and_retry_runs_the_install_again () {
    var store = new_store ();
    fake.play ("install", "install-busy");
    run_until (start_install (store, "ripgrep"));
    assert (store.busy != null && store.busy.pid == 4242);
    assert (store.busy.message == "ketch is running in another process (pid 4242).");
    assert (store.error_message == null);
    fake.play ("install", null);
    store.retry_busy ();
    assert (store.busy == null);
    run_until (() => version_of (store, "ripgrep") == "14.1.1" && store.activity == null);
}

void a_lock_that_stays_held_brings_the_banner_back_with_the_new_holder () {
    var store = new_store ();
    fake.play ("install", "install-busy");
    run_until (start_install (store, "ripgrep"));
    fake.play ("install", "install-busy-unknown-holder");
    store.retry_busy ();
    run_until (() => store.busy != null);
    assert (store.busy.pid == -1);
    assert (store.busy.message == "ketch is running in another process.");
}

void a_cancelled_operation_is_logged_and_not_alerted () {
    var store = new_store ();
    fake.play ("install", "install-cancelled");
    run_until (start_install (store, "ripgrep"));
    assert (store.error_message == null && store.busy == null);
    assert (logged (store, LogLevel.WARNING, "Cancelled"));
}

void the_binary_choice_waits_for_an_answer_from_the_dialog () {
    var store = new_store ();
    var done = start_install (store, "uv");
    run_until (() => store.pending_choice != null);
    assert (store.pending_choice.package == "uv");
    assert (string.joinv (",", store.pending_choice.candidates) == "uv,uvx");
    // The events before the question reached the store first, in order.
    assert (store.activity.fraction > 0);
    assert (!done ());
    store.answer_choice (1);
    run_until (done);
    assert (store.pending_choice == null);
    assert (version_of (store, "uv") == "0.9.0");
}

void cancelling_while_a_question_is_open_declines_it_and_stops_the_operation () {
    var store = new_store ();
    var done = start_install (store, "uv");
    run_until (() => store.pending_choice != null);
    store.cancel ();
    assert (store.pending_choice == null && store.activity.cancelling);
    run_until (done);
    assert (logged (store, LogLevel.WARNING, "Cancelled"));
    assert (store.error_message == null);
    assert (version_of (store, "uv") == null);
}

void the_processes_question_waits_for_an_answer_from_the_dialog () {
    var store = new_store ();
    var done = false;
    store.upgrade.begin ({ "ripgrep" }, (_, res) => {
        store.upgrade.end (res);
        done = true;
    });
    run_until (() => store.pending_stop != null);
    assert (string.joinv (";", store.pending_stop.holders) == "5150 /Users/me/.ketch/bin/rg");
    store.answer_stop (true);
    run_until (() => done);
    assert (logged (store, LogLevel.SUCCESS, "Done: Upgrading ripgrep"));
    assert (version_of (store, "ripgrep") == "14.1.1");
}

void a_second_operation_while_one_runs_is_ignored () {
    var store = new_store ();
    var first = start_install (store, "uv");
    run_until (() => store.pending_choice != null);
    var second = start_install (store, "ripgrep");
    run_until (second);
    assert (!logged (store, LogLevel.INFO, "Installing ripgrep"));
    store.answer_choice (0);
    run_until (first);
    assert (version_of (store, "ripgrep") == "14.1.0");
}

void doctor_counts_the_findings_that_are_not_ok () {
    var store = new_store ();
    var done = false;
    store.run_doctor.begin ((_, res) => {
        store.run_doctor.end (res);
        done = true;
    });
    run_until (() => done);
    assert (store.findings.get_n_items () == 3);
    assert (store.problem_count == 2);
    assert (((Finding) store.findings.get_item (1)).fix == "ketch doctor --fix");
}

void search_with_an_empty_query_lists_the_registry () {
    var store = new_store ();
    var done = false;
    store.search.begin ("", (_, res) => {
        store.search.end (res);
        done = true;
    });
    run_until (() => done);
    assert (store.search_results.get_n_items () == 3);
    assert (((RegistryPackage) store.search_results.get_item (0)).name == "fd");
    var uv = (RegistryPackage) store.search_results.get_item (2);
    assert (uv.name == "uv" && uv.latest == null);
    done = false;
    store.search.begin ("ugrep", (_, res) => {
        store.search.end (res);
        done = true;
    });
    run_until (() => done);
    assert (store.search_results.get_n_items () == 1);
    var ugrep = (RegistryPackage) store.search_results.get_item (0);
    assert (ugrep.name == "ugrep" && ugrep.description == null && ugrep.latest == null);
}

void the_changelog_reads_as_markdown_headed_by_version () {
    var store = new_store ();
    string? text = null;
    var done = false;
    store.changelog.begin ("ripgrep", "14.1.0", "14.1.1", (_, res) => {
        text = store.changelog.end (res);
        done = true;
    });
    run_until (() => done);
    assert (text.has_prefix ("## 14.1.1\n\nBug fixes:"));
    assert ("\n\n## 14.1.0\n\nPerformance improvements." in text);
}

void a_downloads_share_of_the_bar_grows_with_the_bytes_received () {
    var activity = new Activity ("Installing ripgrep");
    activity.stage ("ripgrep", "downloading");
    var before = activity.fraction;
    activity.downloaded ("ripgrep", 500000, 2000000);
    assert (activity.fraction > before);
    activity.downloaded ("ripgrep", 2000000, 2000000);
    assert (activity.fraction == 2.0 / 6);
    activity.stage ("ripgrep", "installing");
    assert (activity.fraction == 5.0 / 6);
}

int main (string[] args) {
    Test.init (ref args);
    Test.add_func ("/store/refresh-splits-updates-from-held", refresh_splits_updates_from_held_packages);
    Test.add_func ("/store/install-logs-stages-and-refreshes", install_logs_its_stages_and_refreshes_the_list);
    Test.add_func ("/store/unknown-package-is-an-alert", an_unknown_package_shows_the_cores_wording_as_an_alert);
    Test.add_func ("/store/busy-banner-and-retry", a_held_lock_shows_the_banner_and_retry_runs_the_install_again);
    Test.add_func ("/store/busy-again-with-new-holder", a_lock_that_stays_held_brings_the_banner_back_with_the_new_holder);
    Test.add_func ("/store/cancelled-is-logged-not-alerted", a_cancelled_operation_is_logged_and_not_alerted);
    Test.add_func ("/store/binary-choice-waits-for-dialog", the_binary_choice_waits_for_an_answer_from_the_dialog);
    Test.add_func ("/store/cancel-declines-open-question", cancelling_while_a_question_is_open_declines_it_and_stops_the_operation);
    Test.add_func ("/store/processes-question-waits-for-dialog", the_processes_question_waits_for_an_answer_from_the_dialog);
    Test.add_func ("/store/second-operation-is-ignored", a_second_operation_while_one_runs_is_ignored);
    Test.add_func ("/store/doctor-counts-problems", doctor_counts_the_findings_that_are_not_ok);
    Test.add_func ("/store/empty-search-lists-registry", search_with_an_empty_query_lists_the_registry);
    Test.add_func ("/store/changelog-as-markdown", the_changelog_reads_as_markdown_headed_by_version);
    Test.add_func ("/store/download-share-grows", a_downloads_share_of_the_bar_grows_with_the_bytes_received);
    return Test.run ();
}
