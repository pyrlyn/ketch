#!/usr/bin/env python3
"""The Homebrew cask smoke must give ketch a token the postflight can read.

`run` does not inherit the job environment. Tap run 37154989737 set
GITHUB_TOKEN on the step and still hit the anonymous rate limit, because
the sandboxed `ketch self install` never saw it. The token is written to
KETCH_ROOT/config.toml before brew install (B75).
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def smoke_step(tap: str) -> str:
    start = tap.find("- name: Smoke test the cask")
    if start < 0:
        fail("tap.yml has no cask smoke step")
    nxt = tap.find("\n      - name:", start + 1)
    return tap[start:] if nxt < 0 else tap[start:nxt]


def main() -> None:
    tap = (ROOT / ".github/workflows/tap.yml").read_text(encoding="utf-8")
    cask = (ROOT / "scripts/cask.sh").read_text(encoding="utf-8")
    smoke = smoke_step(tap)
    for key in ("GITHUB_TOKEN:", "GH_TOKEN:", "KETCH_GITHUB_TOKEN:"):
        if key not in smoke:
            fail(f"the smoke step does not set {key}")
    write_at = smoke.find("config.toml")
    install_at = smoke.find("brew install --cask")
    if write_at < 0 or "github_token" not in smoke:
        fail("the smoke step must write github_token into config.toml")
    if install_at < 0 or write_at > install_at:
        fail("config.toml must be written before brew install")
    if "chmod(0o600)" not in smoke:
        fail("the token file must not be left world-readable")
    postflight = cask.split("postflight_steps", 1)[-1].split("uninstall_postflight", 1)[0]
    if "\n    env:" in postflight or "\n      env:" in postflight:
        fail("the cask postflight cannot carry the token; brew style allows only a literal env hash")
    print("cask-smoke: ok")


if __name__ == "__main__":
    main()
