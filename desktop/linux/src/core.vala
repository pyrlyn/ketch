/*
 * Copyright (c) 2026 Ivan Tugay
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html
 *
 * ketch's core as the Linux app calls it.
 *
 * The shape is ketch-capi's (crates/ketch-capi/vapi/ketch.vapi) on purpose:
 * the same operation names, every answer one JSON envelope, {"ok": <value>}
 * or {"error": <KetchError>}, events and questions as JSON strings. The
 * store runs on FakeCore now and on an adapter over Ketch.Core when D17
 * wires it, and nothing above this interface changes when that happens.
 * GLib.Cancellable stands in for Ketch.Cancel: the adapter trips the C flag
 * from its `cancelled` signal.
 */
namespace KetchApp {
    /* One event, a JSON `Event`. Called on the core's thread. */
    public delegate void EventFunc (string event);

    /* Which of `candidates` (a JSON array of strings) to link for `package`:
     * an index, or a negative number to decline. Blocks the core's thread. */
    public delegate int64 ChooseFunc (string package, string candidates);

    /* Whether to stop `holders` (a JSON array of `Holder`). */
    public delegate bool StopFunc (string holders);

    /* Every call blocks, so callers run it off the main loop. */
    public interface Core : Object {
        public abstract string root ();
        public abstract string installed ();
        public abstract string search (string query, uint32 limit, EventFunc? report);
        public abstract string outdated (EventFunc? report);
        public abstract string install (string[] specs, string? options, EventFunc? report,
                                        ChooseFunc? choose, StopFunc? stop, Cancellable? cancel);
        /* An empty `names` upgrades everything outdated. */
        public abstract string upgrade (string[] names, EventFunc? report,
                                        ChooseFunc? choose, StopFunc? stop, Cancellable? cancel);
        public abstract string uninstall (string[] names, EventFunc? report,
                                          ChooseFunc? choose, StopFunc? stop, Cancellable? cancel);
        public abstract string changelog_range (string package, string? from, string? to, EventFunc? report);
        public abstract string doctor (EventFunc? report);
    }
}
