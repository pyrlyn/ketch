/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * ketch-capi as the Vala app will call it: through vapi/ketch.vapi, with
 * json-glib reading the answers, against a scratch root. Run by Meson
 * (`just capi-test`, and the ketch-capi job on Linux CI).
 */

/* The parsed envelope, or a failed test. */
Json.Object envelope (string answer) {
    var parser = new Json.Parser ();
    try {
        parser.load_from_data (answer);
    } catch (Error e) {
        error ("not JSON (%s): %s", e.message, answer);
    }
    return parser.get_root ().get_object ();
}

/* What `ok` holds, or a failed test when the call answered an error. */
Json.Node ok (string answer) {
    var reply = envelope (answer);
    if (!reply.has_member ("ok")) {
        error ("expected ok: %s", answer);
    }
    return reply.get_member ("ok");
}

void expect (bool claim, string what) {
    if (!claim) {
        error ("failed: %s", what);
    }
}

int main () {
    string dir;
    try {
        dir = DirUtils.make_tmp ("ketch-capi-XXXXXX");
    } catch (Error e) {
        error ("no scratch dir: %s", e.message);
    }
    var core = new Ketch.Core (Path.build_filename (dir, "root"));
    expect (ok (core.installed ()).get_array ().get_length () == 0, "a scratch root has nothing installed");
    expect (ok (core.doctor (null)).get_array ().get_length () > 0, "doctor reports its checks");

    var payload = Path.build_filename (dir, "hello");
    try {
        FileUtils.set_contents (payload, "#!/bin/sh\necho hi\n");
    } catch (Error e) {
        error ("no payload: %s", e.message);
    }
    string[] specs = { "local:" + payload };

    var cancel = new Ketch.Cancel ();
    cancel.cancel ();
    expect (cancel.is_cancelled (), "a tripped flag says so");
    var refused = envelope (core.install (specs, null, null, null, null, cancel));
    expect (refused.has_member ("error"), "a cancelled install is an error");
    expect (refused.get_object_member ("error").get_string_member ("type") == "cancelled",
            "the error is cancelled");
    expect (ok (core.installed ()).get_array ().get_length () == 0, "a cancelled install places nothing");

    // The same install, not cancelled, with a closure for a reporter: the
    // delegate's target is how the C user_data crosses.
    int steps = 0;
    var placed = ok (core.install (specs, "{\"link\": false}", (event) => {
        if (envelope_event_type (event) == "step") {
            steps++;
        }
    }, null, null, null)).get_array ();
    expect (placed.get_length () == 1, "the install placed one package");
    expect (steps > 0, "the reporter saw its steps");
    var name = placed.get_object_element (0).get_object_member ("package").get_string_member ("name");
    var installed = ok (core.installed ()).get_array ();
    expect (installed.get_object_element (0).get_string_member ("name") == name,
            "installed lists what install placed");

    // A folder an earlier uninstall left behind goes only when `confirm`
    // agrees; the name is still not installed either way.
    var leftover = Path.build_filename (dir, "root", "store", "ghost");
    DirUtils.create_with_parents (Path.build_filename (leftover, "1.0.0.old"), 0755);
    string[] ghost = { "ghost" };
    string asked = "";
    var kept = envelope (core.uninstall (ghost, null, null, null, (path) => {
        asked = path;
        return false;
    }, null));
    expect (kept.get_object_member ("error").get_string_member ("type") == "not_found",
            "a name with no record is not found");
    expect (asked.has_suffix (Path.build_filename ("store", "ghost")),
            "confirm is asked about the leftover folder");
    expect (FileUtils.test (leftover, FileTest.IS_DIR), "a decline keeps the leftover");
    envelope (core.uninstall (ghost, null, null, null, (path) => true, null));
    expect (!FileUtils.test (leftover, FileTest.EXISTS), "a confirmation removes the leftover");

    // Two binaries that share the package name, and neither is that name:
    // the install has to ask. The stop delegate is passed too, so the Vala
    // binding is compiled against the int32_t callback (B126).
    var family = Path.build_filename (dir, "family");
    DirUtils.create_with_parents (family, 0755);
    var cli = Path.build_filename (family, "rtok-cli");
    var hook = Path.build_filename (family, "rtok-hook");
    try {
        FileUtils.set_contents (cli, "#!/bin/sh\necho cli\n");
        FileUtils.set_contents (hook, "#!/bin/sh\necho hook\n");
    } catch (Error e) {
        error ("no family payload: %s", e.message);
    }
    FileUtils.chmod (cli, 0755);
    FileUtils.chmod (hook, 0755);
    var archive = Path.build_filename (dir, "rtok");
    string tar_err;
    int waited;
    try {
        Process.spawn_command_line_sync (
            "tar -czf %s -C %s rtok-cli rtok-hook".printf (Shell.quote (archive), Shell.quote (family)),
            null, out tar_err, out waited);
    } catch (Error e) {
        error ("could not run tar: %s", e.message);
    }
    expect (Process.exit_status (waited) == 0, "tar packed the two binaries: " + tar_err);
    string[] rtok = { "local:" + archive };
    string offered = "";
    var chosen = ok (core.install (rtok, null, null, (package, candidates) => {
        expect (package == "rtok", "the choice is for rtok, not " + package);
        offered = candidates;
        var parser = new Json.Parser ();
        try {
            parser.load_from_data (candidates);
        } catch (Error e) {
            error ("candidates are not JSON (%s): %s", e.message, candidates);
        }
        var list = parser.get_root ().get_array ();
        for (int i = 0; i < (int) list.get_length (); i++) {
            if (list.get_string_element (i) == "rtok-cli") {
                return (int64) i;
            }
        }
        error ("rtok-cli was not offered: %s", candidates);
    }, (_holders) => {
        return false;
    }, null)).get_array ();
    expect (chosen.get_length () == 1, "the chosen binary was installed");
    expect (offered.contains ("rtok-cli") && offered.contains ("rtok-hook"), offered);
    expect (FileUtils.test (Path.build_filename (dir, "root", "bin", "rtok-cli"), FileTest.EXISTS),
            "the answer's binary is linked");
    expect (!FileUtils.test (Path.build_filename (dir, "root", "bin", "rtok-hook"), FileTest.EXISTS),
            "the other family member is not linked");

    print ("capi: installed, doctor, cancelled install, a reported install, a leftover and a binary choice all answered\n");
    return 0;
}

/* An event's `type`, from the JSON a reporter receives. */
string envelope_event_type (string event) {
    var parser = new Json.Parser ();
    try {
        parser.load_from_data (event);
    } catch (Error e) {
        error ("event is not JSON (%s): %s", e.message, event);
    }
    return parser.get_root ().get_object ().get_string_member ("type");
}
