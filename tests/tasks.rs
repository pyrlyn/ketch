// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Every task goes through rtok: `tasks/` is the plan, `plan.md`, `todo.md`
//! and `done.md` are its human view.
//!
//! rtok's disk adapter keeps one file per task (`tasks/<id> - <slug>.md`,
//! finished ones under `tasks/done/`) and hands out the ids, so parallel agents
//! never collide. Nothing stops an agent from writing a row into `plan.md` by
//! hand instead, which is how the two drifted apart before; this test is what
//! makes a pull request that does so fail.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::Path;

/// Ids `done.md` used for two different tasks before rtok numbered them. Both
/// entries stay, in one `tasks/done/` file; no new id may join this list.
const REUSED_DONE_IDS: &[&str] = &["F6"];

/// One task file, as rtok wrote it.
#[derive(Debug, Clone, PartialEq)]
struct TaskFile {
    id: String,
    status: String,
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
    let (stem_id, _) = name
        .strip_suffix(".md")
        .and_then(|n| n.split_once(" - "))
        .ok_or_else(|| format!("{name}: not named `<id> - <slug>.md`"))?;
    let front = text
        .strip_prefix("---\n")
        .and_then(|t| t.split_once("\n---\n"))
        .map(|(f, _)| f)
        .ok_or_else(|| format!("{name}: no front matter"))?;
    let field = |key: &str| {
        front
            .lines()
            .find_map(|l| l.strip_prefix(key)?.strip_prefix(": "))
            .map(str::to_string)
    };
    let id = field("id").ok_or_else(|| format!("{name}: no id"))?;
    if id != stem_id {
        return Err(format!(
            "{name}: file name says {stem_id}, front matter says {id}"
        ));
    }
    let status = field("status").ok_or_else(|| format!("{name}: no status"))?;
    Ok(TaskFile {
        id,
        status,
        finished_dir,
    })
}

/// Every way `plan.md`, `todo.md`, `done.md` and the task files disagree.
fn check(plan: &str, todo: &str, done: &str, files: &[TaskFile]) -> Vec<String> {
    let mut errors = Vec::new();
    let mut tasks = BTreeMap::new();
    for f in files {
        let active = matches!(f.status.as_str(), "open" | "in-progress");
        if !active && !matches!(f.status.as_str(), "done" | "closed") {
            errors.push(format!("{}: unknown status {:?}", f.id, f.status));
        } else if active == f.finished_dir {
            errors.push(format!(
                "{}: status {} in the wrong directory",
                f.id, f.status
            ));
        }
        if tasks.insert(f.id.as_str(), f).is_some() {
            errors.push(format!("{}: two task files", f.id));
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
                    "{id}: plan.md status {other:?}; a finished task leaves plan.md"
                ));
                continue;
            }
        };
        match tasks.get(id.as_str()) {
            None => errors.push(format!(
                "{id}: in plan.md but no tasks/ file; create it with `rtok task create`"
            )),
            Some(t) if t.status != want => errors.push(format!(
                "{id}: plan.md says {status}, tasks/ says {}",
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
                "{id}: plan.md card for a task that is not active in tasks/"
            ));
        }
    }
    for t in tasks.values().filter(|t| !t.finished_dir) {
        if !row_ids.contains(t.id.as_str()) && !cards.contains(t.id.as_str()) {
            errors.push(format!("{}: active in tasks/ but not in plan.md", t.id));
        }
    }

    let todo_ids: BTreeSet<&str> = todo
        .lines()
        .filter_map(|l| heading_id(l, &["- "]))
        .collect();
    for id in row_ids.symmetric_difference(&todo_ids) {
        errors.push(format!("{id}: todo.md and the plan.md table differ"));
    }

    let mut done_ids = BTreeSet::new();
    for id in done.lines().filter_map(|l| heading_id(l, &["### "])) {
        if !done_ids.insert(id) && !REUSED_DONE_IDS.contains(&id) {
            errors.push(format!("{id}: done.md has it twice"));
        }
    }
    for id in &done_ids {
        match tasks.get(id) {
            Some(t) if t.finished_dir => {}
            Some(_) => errors.push(format!("{id}: in done.md but still active in tasks/")),
            None => errors.push(format!("{id}: in done.md but no tasks/done/ file")),
        }
    }
    for t in tasks.values().filter(|t| t.finished_dir) {
        if !done_ids.contains(t.id.as_str()) {
            errors.push(format!(
                "{}: finished in tasks/ but missing from done.md",
                t.id
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

#[test]
fn plan_todo_and_done_agree_with_rtok_tasks() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let read = |f: &str| {
        fs::read_to_string(root.join(f))
            .unwrap_or_default()
            .replace("\r\n", "\n")
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

    fn task(id: &str, status: &str) -> TaskFile {
        let finished_dir = matches!(status, "done" | "closed");
        TaskFile {
            id: id.into(),
            status: status.into(),
            finished_dir,
        }
    }

    fn agreeing() -> Vec<TaskFile> {
        vec![
            task("A0", "done"),
            task("A1", "open"),
            task("A2", "in-progress"),
        ]
    }

    #[test]
    fn matching_files_pass() {
        assert_eq!(check(PLAN, TODO, DONE, &agreeing()), Vec::<String>::new());
    }

    #[test]
    fn a_row_written_by_hand_without_rtok_fails() {
        let plan = PLAN.replace("| A2 |", "| A3 | todo | P2 | 1 | 0% | |\n| A2 |");
        let todo = format!("{TODO}- A3. Three\n");
        let errors = check(&plan, &todo, DONE, &agreeing());
        assert!(
            errors
                .iter()
                .any(|e| e.starts_with("A3: in plan.md but no tasks/ file")),
            "{errors:?}"
        );
    }

    #[test]
    fn a_status_changed_only_in_plan_md_fails() {
        let plan = PLAN.replace("| A1 | todo |", "| A1 | in progress |");
        let errors = check(&plan, TODO, DONE, &agreeing());
        assert_eq!(
            errors,
            vec!["A1: plan.md says in progress, tasks/ says open"]
        );
    }

    #[test]
    fn a_done_row_left_in_the_table_fails() {
        let plan = PLAN.replace("| A1 | todo |", "| A1 | done |");
        let errors = check(&plan, TODO, DONE, &agreeing());
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
        assert_eq!(
            check(PLAN, TODO, DONE, &files),
            vec!["A4: active in tasks/ but not in plan.md"]
        );
    }

    #[test]
    fn todo_md_must_list_exactly_the_table() {
        let errors = check(PLAN, "- A1. One\n- A9. Nine\n", DONE, &agreeing());
        assert_eq!(
            errors,
            vec![
                "A2: todo.md and the plan.md table differ",
                "A9: todo.md and the plan.md table differ"
            ]
        );
    }

    #[test]
    fn a_task_closed_by_rtok_but_still_planned_fails() {
        let mut files = agreeing();
        files[1] = task("A1", "done");
        let errors = check(PLAN, TODO, DONE, &files);
        assert!(
            errors.contains(&"A1: finished in tasks/ but missing from done.md".to_string()),
            "{errors:?}"
        );
        assert!(
            errors.contains(&"A1: plan.md says todo, tasks/ says done".to_string()),
            "{errors:?}"
        );
    }

    #[test]
    fn done_md_entries_need_a_finished_file() {
        let done = format!("{DONE}\n### A5. Five\n\nDone.\n");
        assert_eq!(
            check(PLAN, TODO, &done, &agreeing()),
            vec!["A5: in done.md but no tasks/done/ file"]
        );
    }

    #[test]
    fn a_repeated_done_entry_fails_unless_it_predates_rtok() {
        let done = format!("{DONE}\n### A0. Zero\n\nDone.\n");
        assert_eq!(
            check(PLAN, TODO, &done, &agreeing()),
            vec!["A0: done.md has it twice"]
        );
        let reused = "### F6. One\n\n### F6. Other\n";
        assert!(check("", "", reused, &[task("F6", "done")]).is_empty());
    }

    #[test]
    fn a_status_in_the_wrong_directory_fails() {
        let mut files = agreeing();
        files[0].finished_dir = false;
        let errors = check(PLAN, TODO, DONE, &files);
        assert!(
            errors.contains(&"A0: status done in the wrong directory".to_string()),
            "{errors:?}"
        );
    }

    #[test]
    fn task_files_are_read_as_rtok_writes_them() {
        let text = "---\nid: B60.2\ntitle: \"Fix `a: b`\"\nstatus: in-progress\nparent: B60\n---\n\nBody.\n";
        assert_eq!(
            parse_task("B60.2 - fix-a-b.md", text, false),
            Ok(TaskFile {
                id: "B60.2".into(),
                status: "in-progress".into(),
                finished_dir: false
            })
        );
        assert!(parse_task("B60.2 - x.md", &text.replace("id: B60.2", "id: B61"), false).is_err());
        assert!(parse_task("notes.md", text, false).is_err());
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
