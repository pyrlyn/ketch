---
id: B126
title: C ABI yes/no callbacks return a full int
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B126 in the 2026-10-12 findings. `KetchStopFn` and `KetchConfirmFn` returned C `bool`, while a Vala `bool` is `gboolean` (an `int`). GCC 14 rejects that function-pointer mismatch, and the return ABI does not agree.

Done: both callbacks return `int32_t`, and any non-zero answer is yes. The Vala test asks which of two binaries to link and passes a stop callback, so the binding is compiled against those types.
