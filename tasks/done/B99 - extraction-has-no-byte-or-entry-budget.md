---
id: B99
title: Extraction has no byte or entry budget
status: done
created_at: 1792583854
updated_at: 1792583854
---

Every extractor shares one `Budget`: a byte cap, an entry cap, and a limit that scales with the archive between a 256 MiB floor and a 32 GiB ceiling (`MAX_EXPANSION_RATIO` 100). Zip, tar and the lone gzip, xz and bzip2 extractors refuse past it before the extra bytes are written.

Tests: `a_lone_gzip_bomb_stops_at_the_budget`, `a_tar_member_larger_than_the_budget_is_refused_before_it_is_written`, `a_tar_with_too_many_members_is_refused`, `a_zip_with_too_many_members_is_refused`, `a_zip_bomb_stops_at_the_budget`, `the_budget_scales_with_the_archive_between_a_floor_and_a_ceiling`.
Tests: `aside_names_cover_the_swap_and_the_flat_install`, `a_previous_swap_backup_is_removed_and_a_missing_one_is_fine`, `doctor_notes_a_stale_aside_and_stays_quiet_without_one`.
