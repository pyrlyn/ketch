---
id: B127
title: The C ABI error envelope repeats `message`
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B127 in the 2026-10-12 findings. Done: `ErrorBody` keeps the variant's own `message` and adds the sentence only for `Busy`, `Cancelled` and `NotFound`, which have no such field. A network error is one `"message"` key.
