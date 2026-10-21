---
id: B82
title: Duplicate checksum basenames overwrite each other
status: done
created_at: 1792583854
updated_at: 1792583854
---

An aggregate checksum list is keyed by file name. Two lines that name the same file with different hashes used to let the later one win. Conflicting names are dropped, so neither hash is trusted.

Test: `an_ambiguous_basename_is_dropped_rather_than_overwritten`.
