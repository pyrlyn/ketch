#!/usr/bin/env python3
# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

"""The Windows app release must not become GitHub's latest release.

`install.sh`, `install.ps1` and `ketch self upgrade` install whatever
`/releases/latest` points at. A Windows app release has no
`ketch-<target>.tar.gz`, so the workflow is dispatch-only, tags
`desktop-windows-v*`, publishes a prerelease with `--latest=false`, and
checks afterwards that `/releases/latest` did not move.
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "release-windows-desktop.yml"
SCRIPT = ROOT / "scripts" / "windows_desktop_release.py"


def fail(message: str) -> None:
    print(f"desktop-windows-release: {message}", file=sys.stderr)
    sys.exit(1)


def load_script():
    spec = importlib.util.spec_from_file_location("windows_desktop_release", SCRIPT)
    if spec is None or spec.loader is None:
        fail(f"cannot load {SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def need(text: str, needle: str, why: str) -> None:
    if needle not in text:
        fail(f"{WORKFLOW.name} is missing {why}")


def check_workflow(text: str) -> None:
    if not WORKFLOW.is_file():
        fail(f"missing {WORKFLOW}")
    # The only trigger. A tag push would publish on its own, and a pull
    # request must not need the signing certificate.
    head = text.split("permissions:", 1)[0]
    if "pull_request:" in head or "push:" in head:
        fail("the workflow runs on more than workflow_dispatch")
    need(text, "workflow_dispatch:", "the dispatch trigger")
    need(text, "windows-latest", "the existing Windows runner")
    need(text, "desktop-windows-v", "the desktop-windows-v tag")
    need(text, "--latest=false", "--latest=false")
    need(text, "--prerelease", "a prerelease, which /releases/latest skips")
    need(text, "releases/latest", "the check that /releases/latest did not move")
    need(text, "secrets.WINDOWS_CERTIFICATE", "the certificate secret")
    need(
        text,
        "secrets.WINDOWS_CERTIFICATE_PWD",
        "the certificate password secret",
    )
    need(text, "refusing to ship an unsigned MSIX", "the unsigned-ship refusal")
    need(text, "unvirtualizedResources", "the manifest written for the MSIX")
    need(text, "--publisher-file", "the publisher read from a file")
    unpackaged = step_body(text, "Unpackaged self-contained zip")
    if "VERSION:" not in unpackaged:
        fail("the unpackaged zip step does not receive the version")
    if "secrets: inherit" in text:
        fail("the workflow inherits every secret")
    if "MACOS_CERTIFICATE" in text:
        fail("the Apple Developer ID certificate is not a Windows signing cert")
    # The certificate reaches the steps that read it, not the job, so a
    # dependency build does not see the pfx.
    job = text.split("jobs:", 1)[1]
    job_head, _, steps = job.partition("steps:")
    if "WINDOWS_CERTIFICATE" in job_head:
        fail("the certificate is in the job env, not only the signing steps")
    if "echo" in steps and "WINDOWS_CERTIFICATE" in steps:
        # A step may mention the name. It must not print the value.
        for line in steps.splitlines():
            stripped = line.strip()
            if stripped.startswith("echo") and "WINDOWS_CERTIFICATE" in stripped:
                fail(f"the workflow prints the certificate: {stripped}")


def step_body(text: str, name: str) -> str:
    marker = f"- name: {name}"
    start = text.find(marker)
    if start < 0:
        fail(f"missing step {name}")
    rest = text[start + len(marker) :]
    nxt = rest.find("\n      - name:")
    if nxt < 0:
        nxt = len(rest)
    return rest[:nxt]


def check_script(module) -> None:
    assert module.parse_version("1.2.3") == (1, 2, 3)
    assert module.msix_version("1.2.3") == "1.2.3.0"
    assert module.tag_for("1.2.3") == "desktop-windows-v1.2.3"
    for bad in ("v1.2.3", "1.2", "1.2.3-beta", "01.2.3", "1.2.65536"):
        try:
            module.parse_version(bad)
        except ValueError:
            continue
        fail(f"accepted version {bad}")
    module.assert_newer("1.2.4", ["desktop-windows-v1.2.3", "v9.0.0"])
    try:
        module.assert_newer("1.2.3", ["desktop-windows-v1.2.3"])
    except ValueError:
        pass
    else:
        fail("a repeated version was accepted")
    publisher = 'CN=Pyrlyn, O=Pyrlyn & Co "Ketch"'
    xml = module.manifest_xml("0.4.1", publisher)
    if "0.4.1.0" not in xml or "unvirtualizedResources" not in xml:
        fail("the manifest dropped the version or the PATH capability")
    if 'O=Pyrlyn &amp; Co &quot;Ketch&quot;' not in xml:
        fail("the publisher was not escaped")
    try:
        module.manifest_xml("1.0.0", "CN=Bad\nCN=Worse")
    except ValueError:
        pass
    else:
        fail("a multiline publisher was accepted")
    assert module.publisher_text(" CN=Pyrlyn \n") == "CN=Pyrlyn"
    with tempfile.TemporaryDirectory() as raw:
        app = Path(raw)
        module.write_package(app, "2.0.0", "CN=Pyrlyn")
        manifest = (app / "Package.appxmanifest").read_text(encoding="utf-8")
        if 'Version="2.0.0.0"' not in manifest:
            fail("write_package did not record the version")
        for name, size in (
            ("StoreLogo.png", 50),
            ("Square44x44Logo.png", 44),
            ("Square150x150Logo.png", 150),
        ):
            data = (app / "Assets" / name).read_bytes()
            if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                fail(f"{name} is not a png")
            width, height = int.from_bytes(data[16:20], "big"), int.from_bytes(
                data[20:24], "big"
            )
            if (width, height) != (size, size):
                fail(f"{name} is {width}x{height}, not {size}x{size}")


def main() -> None:
    if not SCRIPT.is_file():
        fail(f"missing {SCRIPT}")
    check_workflow(WORKFLOW.read_text(encoding="utf-8"))
    check_script(load_script())
    print("desktop-windows-release: ok")


if __name__ == "__main__":
    main()
