---
id: B81
title: Archive extraction keeps setuid bits
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-08 review: extraction kept `mode & 0o7777`, so setuid, setgid and sticky bits from an archive survived. `payload_mode` now keeps `0o777`, and tar, zip and the directory-mode path all go through it.

Test: `setuid_bits_from_an_archive_are_dropped`.
