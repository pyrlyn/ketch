---
id: B40
title: Three tests weaker than their names
status: done
created_at: 1792583218
updated_at: 1792583218
---

`check_tree_treats_name_collisions_as_errors` passed against the old name-only `collisions()` too. The four Mach-O asserts in `recognises_program_headers` passed on 32-bit-only detection. `registry_validate_rejects_a_bin_name_that_would_escape` accepted either `.zshrc` or any `file name` mention.

`check_tree_treats_name_collisions_as_errors` now uses the `foo`/`foo.git` path-keyed collision and asserts the error is reported against the registry root. `recognises_program_headers` loops the four on-disk Mach-O prefixes, requires 64-bit variants to exceed 32-bit-only matching, covers universal LE, and rejects a near-miss prefix. `registry_validate_rejects_a_bin_name_that_would_escape` requires both `binary name` and `not usable as a file name` in stdout.
