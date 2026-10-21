---
id: B15
title: Questionnaire accepts answers that fail validation
status: done
created_at: 1792583218
updated_at: 1792583218
---

`cmd/config.rs` validated nothing while asking, and `Manifest::validate` ran once at the end, so every other answer was thrown away.

Added prompt-time validators in `wizard.rs` (`validate_package_name`, `validate_alias_list`, `validate_extra_path_list`, `validate_bin_entry`, `prompt_until_valid`) that reuse the same `usable_file_name` / `contained_path` predicates as `Manifest::validate`. `cmd/config.rs` now re-asks on failure for package name, `provides`, `extra_paths`, and `bin` entries; `Manifest::validate` remains the final backstop. Unit test `invalid_answers_are_reasked_before_accepting` verifies an invalid name is rejected and the question is asked again.
