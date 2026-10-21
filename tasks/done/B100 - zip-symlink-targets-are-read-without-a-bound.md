---
id: B100
title: Zip symlink targets are read without a bound
status: done
created_at: 1792583854
updated_at: 1792583854
---

A zip symlink's target is the member body. It was read with an unbounded `read_to_string`, so a small, highly compressed member could expand to gigabytes. The read now stops at 4 KiB plus one byte and refuses anything longer.

Test: `an_overlong_zip_symlink_target_is_refused`.
