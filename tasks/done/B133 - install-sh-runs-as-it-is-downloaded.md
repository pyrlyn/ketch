---
id: B133
title: "`install.sh` runs as it is downloaded"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B133 in the 2026-10-12 findings. Done: the body is `main`, called on the last line. A truncated `curl | bash` defines the function and never calls it. `tests/install_truncation.py` drops that call and expects silence.
