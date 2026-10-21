#!/usr/bin/env python3
"""One-time migration of plan.md and done.md into rtok's disk adapter (tasks/).

rtok hands out new ids only, so the tasks that predate it are written here with
the ids they already carry in commits, branches and pull requests. After this
runs once on the final plan.md, new tasks come from `rtok task create` and this
script is deleted. It writes tasks/ only; problems it cannot settle (a done row
still in the table, an id reused for two tasks) are printed for a person.
"""

import json
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ID = r"[A-Z]+[0-9]+(?:\.[0-9]+)?"
CARD = re.compile(rf"^(#{{2,3}}) ({ID})\. (.+)$")
STATUS = {"todo": "open", "in progress": "in-progress", "done": "done"}


def sections(text):
    """Yield (id, title, body) for every `### <id>. Title` heading."""
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        m = CARD.match(lines[i])
        if not m:
            i += 1
            continue
        j = i + 1
        while j < len(lines) and not re.match(r"^#{1,3} ", lines[j]):
            j += 1
        yield m.group(2), m.group(3).strip(), "\n".join(lines[i + 1 : j]).strip()
        i = j


def table(text):
    """The task table: rows under the `| # | Status |` header."""
    rows, inside = [], False
    for line in text.splitlines():
        if line.startswith("| # | Status |"):
            inside = True
            continue
        if inside and line.startswith("| ---"):
            continue
        if inside and line.startswith("|"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            rows.append((cells[0], cells[1], cells[2]))
        elif inside:
            break
    return rows


def slug(title):
    s = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return s[:48].strip("-") or "task"


def scalar(value):
    # rtok quotes a title only when YAML would misread it; json.dumps is a valid
    # YAML double-quoted scalar for everything a heading holds.
    if re.search(r'^[\s"\'`@&*!|>%#\[\]{},-]|: | #|"', value) or value != value.strip():
        return json.dumps(value, ensure_ascii=False)
    return value


def write(dir_, id_, title, status, body, priority, parent, now):
    lines = ["---", f"id: {id_}", f"title: {scalar(title)}", f"status: {status}"]
    if parent:
        lines.append(f"parent: {parent}")
    lines += [f"created_at: {now}", f"updated_at: {now}"]
    if priority is not None and priority != 2:
        lines.append(f"priority: {priority}")
    lines.append("---")
    (dir_ / f"{id_} - {slug(title)}.md").write_text("\n".join(lines) + "\n\n" + (body + "\n" if body else ""))


def main():
    plan = (ROOT / "plan.md").read_text()
    done_md = (ROOT / "done.md").read_text()
    tasks, finished = ROOT / "tasks", ROOT / "tasks" / "done"
    finished.mkdir(parents=True, exist_ok=True)
    now = int(time.time())
    problems = []

    cards = {}
    for id_, title, body in sections(plan):
        cards.setdefault(id_, (title, body))
    done = {}
    for id_, title, body in sections(done_md):
        if id_ in done:
            if body != done[id_][1].split("\n\n---\n\n")[0]:
                problems.append(f"{id_}: done.md has two different entries; both bodies kept")
                done[id_] = (done[id_][0], done[id_][1] + "\n\n---\n\n" + body)
            else:
                problems.append(f"{id_}: done.md repeats the same entry; drop the copy")
            continue
        done[id_] = (title, body)

    written = {}
    for id_, status, prio in table(plan):
        if status not in STATUS:
            problems.append(f"{id_}: table status {status!r} is not todo or in progress")
            continue
        if id_ not in cards:
            problems.append(f"{id_}: table row has no card; skipped")
            continue
        state = STATUS[status]
        if state == "done":
            problems.append(f"{id_}: done row still in the plan table; move it to done.md")
        title, body = cards[id_]
        priority = int(prio[1]) if re.fullmatch(r"P[0-4]", prio) else None
        written[id_] = (title, state, body, priority)
    # A parent shown only as a card (R19 over R19.1–R19.4) is active while a subtask is.
    for id_, (title, body) in cards.items():
        if id_ not in written and any(k.startswith(id_ + ".") and v[1] != "done" for k, v in written.items()):
            written[id_] = (title, "in-progress", body, None)
    for id_, (title, body) in done.items():
        if id_ in written:
            if written[id_][1] != "done":
                problems.append(f"{id_}: active in plan.md and also in done.md")
            continue
        written[id_] = (title, "done", body, None)

    for id_, (title, state, body, priority) in sorted(written.items()):
        parent = id_.rsplit(".", 1)[0] if "." in id_ else None
        if parent and parent not in written:
            parent = None
        write(finished if state == "done" else tasks, id_, title, state, body, priority, parent, now)

    print(f"wrote {len(written)} tasks", file=sys.stderr)
    for p in problems:
        print(f"problem: {p}", file=sys.stderr)


if __name__ == "__main__":
    main()
