// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Every task goes through rtok: `tasks/` is the plan, `plan.md`, `todo.md`
//! and `done.md` are its human view.
//!
//! rtok's disk adapter keeps one file per task (`tasks/<id> - <slug>.md`,
//! finished ones under `tasks/done/`) and hands out the ids, so parallel agents
//! never collide. Nothing stops an agent from writing a row into `plan.md` by
//! hand instead, or a task file, or an id of its own choosing, which is how the
//! plan drifted apart before; this test is what makes a pull request that does
//! so fail. rtok is the one way in: every failure names the `rtok task` command
//! that does the job properly.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::Path;

/// Ids `done.md` used for two different tasks before rtok numbered them. Both
/// entries stay, in one `tasks/done/` file; no new id may join this list.
const REUSED_DONE_IDS: &[&str] = &["B60", "F6"];

/// When the tasks from before rtok were written into `tasks/` with the ids
/// they already had. Any later task carries rtok's own prefix; an id with
/// another prefix created after this was picked by hand, and the next
/// `rtok task create` may hand the same one out again.
const MIGRATED_AT: i64 = 1_792_583_854;

/// The front matter keys rtok's disk adapter writes (`Head` in rtok's
/// `tasks/disk.rs`). Anything else was typed by hand.
const RTOK_KEYS: &[&str] = &[
    "id",
    "title",
    "status",
    "parent",
    "created_at",
    "updated_at",
    "priority",
    "assignee",
    "blocked_by",
    "conflicts_with",
    "parallel",
];

/// One task file, as rtok wrote it.
#[derive(Debug, Clone, PartialEq)]
struct TaskFile {
    id: String,
    status: String,
    parent: Option<String>,
    created_at: i64,
    finished_dir: bool,
}

fn is_id(s: &str) -> bool {
    let digits = s.trim_start_matches(|c: char| c.is_ascii_uppercase());
    let prefix = s.len() - digits.len();
    let mut parts = digits.split('.');
    let number =
        |p: Option<&str>| p.is_some_and(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()));
    prefix > 0
        && number(parts.next())
        && parts.clone().count() <= 1
        && parts.all(|p| number(Some(p)))
}

/// The id in a `### <id>. Title` heading (or `- <id>. Title` with `marker` "- ").
fn heading_id<'a>(line: &'a str, markers: &[&str]) -> Option<&'a str> {
    let rest = markers.iter().find_map(|m| line.strip_prefix(m))?;
    let (id, _) = rest.split_once(". ")?;
    is_id(id).then_some(id)
}

/// `(id, status)` for each row of the `| # | Status |` task table.
fn table_rows(plan: &str) -> Vec<(String, String)> {
    plan.lines()
        .skip_while(|l| !l.starts_with("| # | Status |"))
        .skip(1)
        .filter(|l| !l.starts_with("| ---"))
        .take_while(|l| l.starts_with('|'))
        .filter_map(|l| {
            let mut cells = l.trim_matches('|').split('|').map(str::trim);
            Some((cells.next()?.to_string(), cells.next()?.to_string()))
        })
        .collect()
}

fn parse_task(name: &str, text: &str, finished_dir: bool) -> Result<TaskFile, String> {
    let by_hand = |what: String| {
        format!("{name}: {what}; task files are written by `rtok task`, not by hand")
    };
    let (stem_id, _) = name
        .strip_suffix(".md")
        .and_then(|n| n.split_once(" - "))
        .ok_or_else(|| by_hand("not named `<id> - <slug>.md`".into()))?;
    let front = text
        .strip_prefix("---\n")
        .and_then(|t| t.split_once("\n---\n"))
        .map(|(f, _)| f)
        .ok_or_else(|| by_hand("no front matter".into()))?;
    // List items (`- K2` under `blocked_by:`) are indented or dashed; only
    // top-level lines are keys.
    let mut fields = BTreeMap::new();
    for line in front.lines().filter(|l| !l.starts_with([' ', '-'])) {
        let (key, value) = line
            .split_once(':')
            .ok_or_else(|| by_hand(format!("front matter line {line:?} is not `key: value`")))?;
        if !RTOK_KEYS.contains(&key) {
            return Err(by_hand(format!(
                "front matter key `{key}` is not one rtok writes"
            )));
        }
        if fields.insert(key, value.trim()).is_some() {
            return Err(by_hand(format!("front matter key `{key}` twice")));
        }
    }
    let field = |key: &str| {
        fields
            .get(key)
            .map(|v| v.to_string())
            .ok_or_else(|| by_hand(format!("no `{key}`")))
    };
    let time = |key: &str| {
        field(key)?
            .parse::<i64>()
            .map_err(|_| by_hand(format!("`{key}` is not a Unix time")))
    };
    let id = field("id")?;
    if id != stem_id {
        return Err(by_hand(format!(
            "file name says {stem_id}, front matter says {id}"
        )));
    }
    field("title")?;
    let (created_at, updated_at) = (time("created_at")?, time("updated_at")?);
    if updated_at < created_at {
        return Err(by_hand("`updated_at` before `created_at`".into()));
    }
    if let Some(p) = fields.get("priority") {
        if !matches!(*p, "0" | "1" | "3" | "4") {
            return Err(by_hand(format!(
                "priority {p} (rtok writes 0, 1, 3 or 4; 2 is left out)"
            )));
        }
    }
    Ok(TaskFile {
        id,
        status: field("status")?,
        parent: fields.get("parent").map(|p| p.to_string()),
        created_at,
        finished_dir,
    })
}

/// Every way `plan.md`, `todo.md`, `done.md` and the task files disagree.
fn check(plan: &str, todo: &str, done: &str, files: &[TaskFile], prefix: &str) -> Vec<String> {
    let mut errors = Vec::new();
    let mut tasks = BTreeMap::new();
    for f in files {
        let active = matches!(f.status.as_str(), "open" | "in-progress");
        if !active && !matches!(f.status.as_str(), "done" | "closed") {
            errors.push(format!(
                "{}: unknown status {:?}; set it with `rtok task status {} <open|in-progress|done|closed>`",
                f.id, f.status, f.id
            ));
        } else if active == f.finished_dir {
            errors.push(format!(
                "{}: status {} in the wrong directory; `rtok task status {} {}` moves the file",
                f.id, f.status, f.id, f.status
            ));
        }
        if tasks.insert(f.id.as_str(), f).is_some() {
            errors.push(format!(
                "{}: two task files; ids come from `rtok task create`, once each",
                f.id
            ));
        }
        let own_prefix =
            f.id.strip_prefix(prefix)
                .is_some_and(|rest| rest.starts_with(|c: char| c.is_ascii_digit()));
        if !own_prefix && f.created_at > MIGRATED_AT {
            errors.push(format!(
                "{}: created after the move to rtok without rtok's prefix {prefix}; an id picked by hand collides, create it with `rtok task create`",
                f.id
            ));
        }
        // M16.1–M16.8 were split before rtok without an M16 to hang under;
        // only a migrated task may lack the parent its id names.
        let parent_of_id = f.id.rsplit_once('.').map(|(p, _)| p);
        let migrated_orphan = f.parent.is_none() && f.created_at <= MIGRATED_AT;
        if f.parent.as_deref() != parent_of_id && !migrated_orphan {
            errors.push(format!(
                "{}: parent {:?} does not match the id; subtasks come from `rtok task create --parent`",
                f.id, f.parent
            ));
        }
    }
    for f in files {
        if let Some(p) = &f.parent {
            if !tasks.contains_key(p.as_str()) {
                errors.push(format!(
                    "{}: parent {p} has no task file; create it with `rtok task create`",
                    f.id
                ));
            }
        }
    }

    let rows = table_rows(plan);
    let row_ids: BTreeSet<&str> = rows.iter().map(|(id, _)| id.as_str()).collect();
    for (id, status) in &rows {
        let want = match status.as_str() {
            "todo" => "open",
            "in progress" => "in-progress",
            other => {
                errors.push(format!(
                    "{id}: plan.md status {other:?}; a finished task leaves plan.md: `rtok task status {id} done`, then its card moves to done.md"
                ));
                continue;
            }
        };
        match tasks.get(id.as_str()) {
            None => errors.push(format!(
                "{id}: in plan.md but no tasks/ file; create it with `rtok task create`"
            )),
            Some(t) if t.status != want => errors.push(format!(
                "{id}: plan.md says {status}, tasks/ says {}; change it with `rtok task status {id} <status>` and the row with it",
                t.status
            )),
            Some(_) => {}
        }
    }

    let cards: BTreeSet<&str> = plan
        .lines()
        .filter_map(|l| heading_id(l, &["### ", "## "]))
        .collect();
    for id in &cards {
        if !tasks.get(id).is_some_and(|t| !t.finished_dir) {
            errors.push(format!(
                "{id}: plan.md card for a task that is not active in tasks/; plan new work with `rtok task create`, reopen with `rtok task status {id} open`"
            ));
        }
    }
    for t in tasks.values().filter(|t| !t.finished_dir) {
        if !row_ids.contains(t.id.as_str()) && !cards.contains(t.id.as_str()) {
            errors.push(format!(
                "{}: active in tasks/ but not in plan.md; add its row and card (`rtok task show {}`), or drop it with `rtok task status {} closed`",
                t.id, t.id, t.id
            ));
        }
    }

    let todo_ids: BTreeSet<&str> = todo
        .lines()
        .filter_map(|l| heading_id(l, &["- "]))
        .collect();
    for id in row_ids.symmetric_difference(&todo_ids) {
        errors.push(format!(
            "{id}: todo.md and the plan.md table differ; both list the active tasks of `rtok task list`"
        ));
    }

    let mut done_ids = BTreeSet::new();
    for id in done.lines().filter_map(|l| heading_id(l, &["### "])) {
        if !done_ids.insert(id) && !REUSED_DONE_IDS.contains(&id) {
            errors.push(format!(
                "{id}: done.md has it twice; one task, one id, one entry (`rtok task show {id}`)"
            ));
        }
    }
    for id in &done_ids {
        match tasks.get(id) {
            Some(t) if t.finished_dir => {}
            Some(_) => errors.push(format!(
                "{id}: in done.md but still active in tasks/; finish it with `rtok task status {id} done`"
            )),
            None => errors.push(format!(
                "{id}: in done.md but no tasks/done/ file; every task starts with `rtok task create`"
            )),
        }
    }
    for t in tasks.values().filter(|t| t.finished_dir) {
        if !done_ids.contains(t.id.as_str()) {
            errors.push(format!(
                "{}: finished in tasks/ but missing from done.md; move its card there (`rtok task show {}`)",
                t.id, t.id
            ));
        }
    }
    errors
}

fn read_tasks(dir: &Path, finished_dir: bool, out: &mut Vec<TaskFile>, errors: &mut Vec<String>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        // rtok ignores names that are not `<id> - <slug>.md`, so a README or
        // its claim lock can sit beside the tasks.
        let is_task =
            name.ends_with(".md") && name.split_once(" - ").is_some_and(|(id, _)| is_id(id));
        if !is_task {
            continue;
        }
        match fs::read_to_string(entry.path()).map_err(|e| format!("{name}: {e}")) {
            Ok(text) => match parse_task(&name, &text.replace("\r\n", "\n"), finished_dir) {
                Ok(t) => out.push(t),
                Err(e) => errors.push(e),
            },
            Err(e) => errors.push(e),
        }
    }
}

/// The prefix `rtok task create` numbers new tasks with, from `.rtok.toml`.
fn rtok_prefix(config: &str) -> Option<String> {
    config
        .lines()
        .skip_while(|l| l.trim() != "[tasks]")
        .skip(1)
        .take_while(|l| !l.trim_start().starts_with('['))
        .find_map(|l| {
            let (key, value) = l.split_once('=')?;
            (key.trim() == "prefix").then(|| value.trim().trim_matches('"').to_string())
        })
}

#[test]
fn plan_todo_and_done_agree_with_rtok_tasks() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let read = |f: &str| {
        fs::read_to_string(root.join(f))
            .unwrap_or_default()
            .replace("\r\n", "\n")
    };
    let Some(prefix) = rtok_prefix(&read(".rtok.toml")) else {
        panic!("`.rtok.toml` names no `[tasks]` prefix: run `rtok task init --adapter disk --prefix K`");
    };
    let (mut files, mut errors) = (Vec::new(), Vec::new());
    read_tasks(&root.join("tasks"), false, &mut files, &mut errors);
    read_tasks(&root.join("tasks/done"), true, &mut files, &mut errors);
    assert!(
        !files.is_empty(),
        "no tasks/ files: the plan is kept by rtok (`rtok task list`)"
    );
    errors.extend(check(
        &read("plan.md"),
        &read("todo.md"),
        &read("done.md"),
        &files,
        &prefix,
    ));
    assert!(
        errors.is_empty(),
        "tasks/ and plan.md/todo.md/done.md disagree:\n{}",
        errors.join("\n")
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    const PLAN: &str = "# ketch\n\n| # | Status | Priority | Complexity | Readiness | Agent |\n| --- | --- | --- | --- | --- | --- |\n| A1 | todo | P2 | 1 | 0% | |\n| A2 | in progress | P1 | 2 | 50% | x / y |\n\n### A1. One\n\nWhy.\n\n### A2. Two\n\nWhy.\n";
    const TODO: &str = "- A1. One\n- A2. Two\n";
    const DONE: &str = "# done\n\n### A0. Zero\n\nDone.\n";
    const FILE: &str = "---\nid: B60.2\ntitle: \"Fix `a: b`\"\nstatus: in-progress\nparent: B60\ncreated_at: 100\nupdated_at: 200\npriority: 1\nassignee: 389d32cb\nblocked_by:\n- B61\n---\n\nBody.\n";

    fn task(id: &str, status: &str) -> TaskFile {
        TaskFile {
            id: id.into(),
            status: status.into(),
            parent: id.rsplit_once('.').map(|(p, _)| p.into()),
            created_at: MIGRATED_AT,
            finished_dir: matches!(status, "done" | "closed"),
        }
    }

    fn agreeing() -> Vec<TaskFile> {
        vec![
            task("A0", "done"),
            task("A1", "open"),
            task("A2", "in-progress"),
        ]
    }

    fn run(plan: &str, todo: &str, done: &str, files: &[TaskFile]) -> Vec<String> {
        check(plan, todo, done, files, "K")
    }

    /// The first error, which each case below is built to produce alone.
    fn only(errors: Vec<String>) -> String {
        assert_eq!(errors.len(), 1, "{errors:#?}");
        errors.into_iter().next().unwrap_or_default()
    }

    #[test]
    fn matching_files_pass() {
        assert_eq!(run(PLAN, TODO, DONE, &agreeing()), Vec::<String>::new());
    }

    #[test]
    fn a_row_written_by_hand_without_rtok_fails() {
        let plan = PLAN.replace("| A2 |", "| A3 | todo | P2 | 1 | 0% | |\n| A2 |");
        let todo = format!("{TODO}- A3. Three\n");
        let error = only(run(&plan, &todo, DONE, &agreeing()));
        assert!(
            error.starts_with("A3: in plan.md but no tasks/ file"),
            "{error}"
        );
    }

    #[test]
    fn a_status_changed_only_in_plan_md_fails() {
        let plan = PLAN.replace("| A1 | todo |", "| A1 | in progress |");
        let error = only(run(&plan, TODO, DONE, &agreeing()));
        assert!(
            error.starts_with("A1: plan.md says in progress, tasks/ says open"),
            "{error}"
        );
    }

    #[test]
    fn a_done_row_left_in_the_table_fails() {
        let plan = PLAN.replace("| A1 | todo |", "| A1 | done |");
        let errors = run(&plan, TODO, DONE, &agreeing());
        assert!(
            errors
                .iter()
                .any(|e| e.contains("a finished task leaves plan.md")),
            "{errors:?}"
        );
    }

    #[test]
    fn an_active_task_missing_from_plan_md_fails() {
        let mut files = agreeing();
        files.push(task("A4", "open"));
        let error = only(run(PLAN, TODO, DONE, &files));
        assert!(
            error.starts_with("A4: active in tasks/ but not in plan.md"),
            "{error}"
        );
    }

    #[test]
    fn todo_md_must_list_exactly_the_table() {
        let errors = run(PLAN, "- A1. One\n- A9. Nine\n", DONE, &agreeing());
        let ids: Vec<&str> = errors.iter().filter_map(|e| e.split(':').next()).collect();
        assert_eq!(ids, vec!["A2", "A9"]);
        assert!(errors
            .iter()
            .all(|e| e.contains("todo.md and the plan.md table differ")));
    }

    #[test]
    fn a_task_closed_by_rtok_but_still_planned_fails() {
        let mut files = agreeing();
        files[1] = task("A1", "done");
        let errors = run(PLAN, TODO, DONE, &files);
        for want in [
            "A1: finished in tasks/ but missing from done.md",
            "A1: plan.md says todo, tasks/ says done",
        ] {
            assert!(
                errors.iter().any(|e| e.starts_with(want)),
                "{want}: {errors:?}"
            );
        }
    }

    #[test]
    fn done_md_entries_need_a_finished_file() {
        let done = format!("{DONE}\n### A5. Five\n\nDone.\n");
        let error = only(run(PLAN, TODO, &done, &agreeing()));
        assert!(
            error.starts_with("A5: in done.md but no tasks/done/ file"),
            "{error}"
        );
    }

    #[test]
    fn a_repeated_done_entry_fails_unless_it_predates_rtok() {
        let done = format!("{DONE}\n### A0. Zero\n\nDone.\n");
        let error = only(run(PLAN, TODO, &done, &agreeing()));
        assert!(error.starts_with("A0: done.md has it twice"), "{error}");
        let reused = "### F6. One\n\n### F6. Other\n";
        assert!(run("", "", reused, &[task("F6", "done")]).is_empty());
    }

    #[test]
    fn a_status_in_the_wrong_directory_fails() {
        let mut files = agreeing();
        files[0].finished_dir = false;
        let errors = run(PLAN, TODO, DONE, &files);
        assert!(
            errors
                .iter()
                .any(|e| e.starts_with("A0: status done in the wrong directory")),
            "{errors:?}"
        );
    }

    #[test]
    fn a_second_file_for_one_id_fails() {
        let mut files = agreeing();
        files.push(task("A1", "open"));
        let error = only(run(PLAN, TODO, DONE, &files));
        assert!(error.starts_with("A1: two task files"), "{error}");
    }

    #[test]
    fn a_new_task_with_an_id_picked_by_hand_fails() {
        let mut files = agreeing();
        files[1].created_at = MIGRATED_AT + 1;
        let error = only(run(PLAN, TODO, DONE, &files));
        assert!(
            error.starts_with("A1: created after the move to rtok without rtok's prefix K"),
            "{error}"
        );
        // rtok's own prefix is the one way to a new id.
        let plan = PLAN.replace("A1", "K7");
        let todo = TODO.replace("A1", "K7");
        let mut files = agreeing();
        files[1] = TaskFile {
            created_at: MIGRATED_AT + 1,
            ..task("K7", "open")
        };
        assert_eq!(run(&plan, &todo, DONE, &files), Vec::<String>::new());
    }

    #[test]
    fn a_subtask_needs_its_parent() {
        let mut files = agreeing();
        files.push(task("A0.1", "done"));
        let done = format!("{DONE}\n### A0.1. Part\n\nDone.\n");
        assert_eq!(run(PLAN, TODO, &done, &files), Vec::<String>::new());

        files.push(task("A9.1", "done"));
        let done = format!("{done}\n### A9.1. Orphan\n\nDone.\n");
        let error = only(run(PLAN, TODO, &done, &files));
        assert!(
            error.starts_with("A9.1: parent A9 has no task file"),
            "{error}"
        );

        let mut files = agreeing();
        files.push(TaskFile {
            parent: None,
            ..task("A0.2", "done")
        });
        let done = format!("{DONE}\n### A0.2. Migrated\n\nDone.\n");
        assert_eq!(run(PLAN, TODO, &done, &files), Vec::<String>::new());
        // The same shape made today is a subtask rtok would have parented.
        files.push(task("K3", "done"));
        files.push(TaskFile {
            parent: None,
            created_at: MIGRATED_AT + 1,
            ..task("K3.1", "done")
        });
        let done = format!("{done}\n### K3. Now\n\n### K3.1. Now too\n");
        let error = only(run(PLAN, TODO, &done, &files));
        assert!(
            error.starts_with("K3.1: parent None does not match the id"),
            "{error}"
        );

        let mut files = agreeing();
        files[1].parent = Some("A2".into());
        let error = only(run(PLAN, TODO, DONE, &files));
        assert!(
            error.starts_with("A1: parent Some(\"A2\") does not match the id"),
            "{error}"
        );
    }

    #[test]
    fn every_failure_names_the_rtok_command_to_use() {
        let mut files = agreeing();
        files.push(task("A1", "open"));
        files.push(task("A4", "open"));
        files.push(TaskFile {
            status: "later".into(),
            ..task("A6", "open")
        });
        files.push(TaskFile {
            created_at: MIGRATED_AT + 1,
            ..task("A7", "open")
        });
        files.push(task("A9.1", "done"));
        files.push(TaskFile {
            finished_dir: false,
            ..task("A8", "done")
        });
        let plan = PLAN
            .replace("| A1 | todo |", "| A1 | done |")
            .replace("| A2 | in progress |", "| A2 | todo |\n| A3 | todo |")
            + "\n### A0. Zero again\n";
        let done = format!("{DONE}\n### A0. Zero\n\n### A2. Two\n\n### A5. Five\n");
        let errors = run(&plan, "- A1. One\n", &done, &files);
        assert!(errors.len() >= 12, "{errors:#?}");
        for e in &errors {
            assert!(e.contains("`rtok task"), "names no rtok command: {e}");
        }
    }

    #[test]
    fn task_files_are_read_as_rtok_writes_them() {
        assert_eq!(
            parse_task("B60.2 - fix-a-b.md", FILE, false),
            Ok(TaskFile {
                id: "B60.2".into(),
                status: "in-progress".into(),
                parent: Some("B60".into()),
                created_at: 100,
                finished_dir: false
            })
        );
    }

    #[test]
    fn a_task_file_written_by_hand_is_refused() {
        let cases = [
            (
                "B60.2 - x.md",
                FILE.replace("id: B60.2", "id: B61"),
                "file name says B60.2",
            ),
            ("notes.md", FILE.to_string(), "not named"),
            (
                "B60.2 - x.md",
                FILE.replace("---\nid", "id"),
                "no front matter",
            ),
            (
                "B60.2 - x.md",
                FILE.replace("assignee:", "owner:"),
                "key `owner` is not one rtok writes",
            ),
            (
                "B60.2 - x.md",
                FILE.replace("priority: 1\n", "priority: 1\npriority: 3\n"),
                "key `priority` twice",
            ),
            (
                "B60.2 - x.md",
                FILE.replace("title: \"Fix `a: b`\"\n", ""),
                "no `title`",
            ),
            (
                "B60.2 - x.md",
                FILE.replace("created_at: 100", "created_at: today"),
                "not a Unix time",
            ),
            (
                "B60.2 - x.md",
                FILE.replace("updated_at: 200", "updated_at: 50"),
                "before `created_at`",
            ),
            (
                "B60.2 - x.md",
                FILE.replace("priority: 1", "priority: 2"),
                "priority 2",
            ),
            (
                "B60.2 - x.md",
                FILE.replace("priority: 1", "priority 1"),
                "is not `key: value`",
            ),
        ];
        for (name, text, want) in cases {
            let Err(error) = parse_task(name, &text, false) else {
                panic!("accepted: {want}");
            };
            assert!(error.contains(want), "{want}: {error}");
            assert!(error.contains("`rtok task`"), "{error}");
        }
    }

    #[test]
    fn the_prefix_comes_from_rtok_toml() {
        let config = "[other]\nprefix = \"X\"\n\n[tasks]\nadapter = \"disk\"\nprefix = \"K\"\n";
        assert_eq!(rtok_prefix(config).as_deref(), Some("K"));
        assert_eq!(rtok_prefix("[tasks]\nadapter = \"disk\"\n"), None);
    }

    #[test]
    fn ids_follow_rtok_shape() {
        for ok in ["A1", "R19.4", "KETCH12"] {
            assert!(is_id(ok), "{ok}");
        }
        for bad in ["1", "A", "a1", "A1.", "A1.2.3", "A1x", "Config"] {
            assert!(!is_id(bad), "{bad}");
        }
    }
}
