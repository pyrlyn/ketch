---
id: R16
title: Fuzz seed script reads the old builtin path
status: done
created_at: 1792583854
updated_at: 1792583854
---

`fuzz/seed.sh` looked for `src/builtin.toml` after that file moved into `ketch-core`. The script already reads `crates/ketch-core/src/builtin.toml`. The readme still named the old path; it now matches.
