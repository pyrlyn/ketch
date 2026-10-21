---
id: B9
title: "`outdated --json` cannot report failed checks"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The text output prints "N could not be checked"; `--json` printed `[]` at exit 0 for the same run, so a machine consumer could not tell "everything is current" from "the network was down".

`outdated --json` now emits an object `{status, outdated, failed, unreachable}`. `unreachable` is the count of sources that could not be checked. Failed checks still exit non-zero. Tests: `outdated_json_marks_a_total_failure`, `outdated_json_reports_unreachable_count_when_a_source_cannot_be_checked`, `outdated_json_reports_partial_failures_and_still_exits_non_zero`.
