#!/usr/bin/env python3
# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

"""A truncated `install.sh` must not start installing.

`curl | bash` runs each complete line as it arrives. The installer is one
function, called on the last line, so a download that stops early defines
that function and never calls it.
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INSTALL = ROOT / "install.sh"


def fail(message: str) -> None:
    print(f"install-truncation: {message}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    text = INSTALL.read_text(encoding="utf-8")
    lines = text.splitlines()
    if lines[-1] != 'main "$@"':
        fail("install.sh does not call main on its last line")
    if text.count("\nmain() {") != 1:
        fail("install.sh should define main once")
    # Drop the call. What remains is the function and nothing that runs.
    truncated = "\n".join(lines[:-1]) + "\n"
    with tempfile.TemporaryDirectory() as raw:
        path = Path(raw) / "install.sh"
        path.write_text(truncated, encoding="utf-8")
        result = subprocess.run(
            ["bash", str(path)],
            capture_output=True,
            text=True,
            check=False,
        )
    if result.returncode != 0:
        fail(f"truncated installer exited {result.returncode}: {result.stderr}")
    if result.stdout or result.stderr:
        fail(f"truncated installer produced output: {result.stdout!r} {result.stderr!r}")
    help_run = subprocess.run(
        ["bash", str(INSTALL), "--help"],
        capture_output=True,
        text=True,
        check=False,
    )
    if help_run.returncode != 0 or "--root" not in help_run.stdout:
        fail("install.sh --help did not print usage")
    print("install-truncation: ok")


if __name__ == "__main__":
    main()
