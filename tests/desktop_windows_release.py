#!/usr/bin/env python3
# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

"""The Windows app release stays off /releases/latest and ships both packages.

Runs on any OS: it checks the workflow text and the packager's pure
functions. `makeappx` and `dotnet publish` run in the Windows CI job.
"""

from __future__ import annotations

import importlib.util
import os
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "windows_desktop_package.py"
WORKFLOW = ROOT / ".github" / "workflows" / "release-windows-desktop.yml"


def load_packager():
    spec = importlib.util.spec_from_file_location("windows_desktop_package", SCRIPT)
    if spec is None or spec.loader is None:
        raise SystemExit("desktop-windows-release: cannot load the packager")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fail(message: str) -> None:
    raise SystemExit(f"desktop-windows-release: {message}")


def expect_error(module, call, text: str) -> None:
    try:
        call()
    except module.PackageError as exc:
        if text not in str(exc):
            fail(f"expected {text!r} in {exc}")
        return
    fail(f"expected an error containing {text!r}")


def test_version(module) -> None:
    if module.validate_version("1.2.3") != "1.2.3":
        fail("1.2.3 was rewritten")
    if module.appx_version("1.2.3") != "1.2.3.0":
        fail("appx version was not stamped as four components")
    for bad in ("", "1", "1.2", "01.2.3", "v1.2.3", "1.2.3-pre", "1.2.3\n", "1.2.3 ", "65536.0.0"):
        expect_error(module, lambda bad=bad: module.validate_version(bad), "version")


def test_manifest(module) -> None:
    xml = module.appx_manifest("1.2.3", 'CN=Pyrlyn "Quoted" & Co')
    if 'Version="1.2.3.0"' not in xml:
        fail("manifest version was not stamped")
    if "Pyrlyn.Ketch" not in xml or "runFullTrust" not in xml:
        fail("manifest is missing the identity or full-trust capability")
    if "Windows.FullTrustApplication" not in xml or "Ketch.exe" not in xml:
        fail("manifest entry point is not the published executable")
    if "&quot;Quoted&quot;" not in xml or "&amp; Co" not in xml:
        fail("publisher was not escaped")
    png = module.png_bytes(44, 44, (18, 92, 84))
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        fail("logo is not a png")


def test_zip_skips_pdb(module) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        publish = Path(tmp) / "publish"
        (publish / "nested").mkdir(parents=True)
        (publish / "Ketch.exe").write_bytes(b"MZ")
        (publish / "Ketch.pdb").write_bytes(b"pdb")
        (publish / "nested" / "a.dll").write_bytes(b"dll")
        dest = Path(tmp) / "out.zip"
        module.zip_publish(publish, dest)
        with zipfile.ZipFile(dest) as archive:
            names = set(archive.namelist())
        if names != {"Ketch.exe", "nested/a.dll"}:
            fail(f"zip members are {sorted(names)}")


def test_winget_lists_both(module) -> None:
    docs = module.winget_documents(
        "1.2.3",
        "ab" * 32,
        "cd" * 32,
        "pyrlyn/ketch",
    )
    installer = docs["Pyrlyn.Ketch.installer.yaml"]
    if "InstallerType: zip" not in installer or "InstallerType: msix" not in installer:
        fail("winget installer manifest does not list both packages")
    if "desktop-windows-v1.2.3" not in installer:
        fail("winget urls are not under the desktop-windows tag")
    if "ab" * 32 not in installer or "cd" * 32 not in installer:
        fail("winget manifest dropped a sha256")
    if docs["Pyrlyn.Ketch.yaml"].splitlines()[1] != "PackageVersion: 1.2.3":
        fail("winget version manifest was not stamped")


def test_release_argv_is_not_latest(module) -> None:
    argv = module.release_create_argv("1.2.3", [Path("Ketch.zip")])
    if "--latest=false" not in argv or "--prerelease" not in argv:
        fail("release create does not keep the release off latest")
    if "--latest=true" in argv:
        fail("release create marks the app release latest")
    if "desktop-windows-v1.2.3" not in argv:
        fail("release tag is not desktop-windows-v")


def test_signtool_omits_the_password(module) -> None:
    argv = module.signtool_command(Path("signtool.exe"), "ABCDEF", Path("Ketch.msix"))
    if "/p" in argv or "/f" in argv:
        fail("signtool is invoked with a password or a pfx path")
    if "ABCDEF" not in argv:
        fail("signtool is not pointed at the thumbprint")
    expect_error(
        module,
        lambda: module.signtool_command(Path("signtool.exe"), "nope!", Path("Ketch.msix")),
        "thumbprint",
    )


def test_require_signing_fails_closed(module) -> None:
    saved = {key: os.environ.get(key) for key in ("WINDOWS_CERTIFICATE", "WINDOWS_CERTIFICATE_PWD", "GITHUB_ACTIONS", "GITHUB_REF")}
    try:
        os.environ.pop("WINDOWS_CERTIFICATE", None)
        os.environ.pop("WINDOWS_CERTIFICATE_PWD", None)
        os.environ.pop("GITHUB_ACTIONS", None)
        expect_error(module, module.require_signing, "refusing to ship unsigned")
        os.environ["WINDOWS_CERTIFICATE"] = "Y2VydA=="
        os.environ["WINDOWS_CERTIFICATE_PWD"] = "sentinel-password"
        os.environ["GITHUB_ACTIONS"] = "true"
        os.environ["GITHUB_REF"] = "refs/heads/topic"
        try:
            module.require_signing()
        except module.PackageError as exc:
            if "sentinel-password" in str(exc) or "Y2VydA==" in str(exc):
                fail("the signing error included a secret")
            if "main" not in str(exc):
                fail(f"a non-main ref was accepted or misreported: {exc}")
        else:
            fail("a non-main ref was accepted")
    finally:
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def test_assemble_without_makeappx(module) -> None:
    if sys.platform == "win32":
        return
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        publish = root / "publish"
        publish.mkdir()
        (publish / "Ketch.exe").write_bytes(b"MZ")
        (publish / "Ketch.pdb").write_bytes(b"pdb")
        out = root / "out"
        module.assemble(publish, out, "0.0.1", require_msix=False, sign=False)
        if not (out / "Ketch-0.0.1-win-x64.zip").is_file():
            fail("assemble did not write the zip")
        manifest = (out / "msix-layout" / "AppxManifest.xml").read_text(encoding="utf-8")
        if 'Version="0.0.1.0"' not in manifest:
            fail("layout manifest was not stamped")
        if (out / "msix-layout" / "Ketch.pdb").exists():
            fail("the MSIX layout kept a pdb")
        winget = out / "winget"
        if winget.exists() and list(winget.glob("*.yaml")):
            fail("winget manifests were written without an MSIX")


def on_block(text: str) -> str:
    start = text.find("\non:")
    if start < 0:
        fail("workflow has no on: block")
    end = text.find("\npermissions:", start)
    if end < 0:
        fail("workflow has no permissions block after on:")
    return text[start:end]


def test_workflow() -> None:
    if not WORKFLOW.is_file():
        fail(f"missing {WORKFLOW}")
    text = WORKFLOW.read_text(encoding="utf-8")
    block = on_block(text)
    if "workflow_dispatch:" not in block:
        fail("release is not workflow_dispatch")
    for banned in ("push:", "pull_request:", "schedule:", "release:"):
        if banned in block:
            fail(f"release workflow trigger includes {banned}")
    if "version:" not in block:
        fail("workflow has no version input")
    for need in (
        "windows-latest",
        "WINDOWS_CERTIFICATE",
        "WINDOWS_CERTIFICATE_PWD",
        "require-signing",
        "--latest=false",
        "desktop-windows-v",
        "--require-msix",
        "--sign",
        "winget",
        "refs/heads/main",
        "dotnet python",
    ):
        if need not in text:
            fail(f"workflow is missing {need}")
    if "--latest=true" in text or "make_latest: true" in text:
        fail("workflow marks the app release latest")
    for line in text.splitlines():
        if "${{ inputs.version }}" in line and "VERSION:" not in line:
            fail("version is interpolated outside the VERSION environment binding")
        if line.strip().startswith("run:") and "${{" in line:
            fail("a run: line contains a GitHub expression")
    source = SCRIPT.read_text(encoding="utf-8")
    if "--latest=true" in source:
        fail("packager marks a release latest")
    if "print(os.environ" in source or "WINDOWS_CERTIFICATE_PWD}" in source:
        fail("packager interpolates the signing password")
    for name in ("release.yml", "bump.yml"):
        body = (ROOT / ".github" / "workflows" / name).read_text(encoding="utf-8")
        if "desktop-windows" in body:
            fail(f"{name} mentions the Windows app release")
    cliff = (ROOT / "cliff.toml").read_text(encoding="utf-8")
    if 'tag_pattern = "^v[0-9]' not in cliff:
        fail("cliff.toml no longer anchors CLI tags at v")
    release_sh = (ROOT / "scripts" / "release.sh").read_text(encoding="utf-8")
    if "refs/tags/v$current" not in release_sh:
        fail("release.sh no longer looks up the v tag exactly")


def main() -> None:
    module = load_packager()
    test_version(module)
    test_manifest(module)
    test_zip_skips_pdb(module)
    test_winget_lists_both(module)
    test_release_argv_is_not_latest(module)
    test_signtool_omits_the_password(module)
    test_require_signing_fails_closed(module)
    test_assemble_without_makeappx(module)
    test_workflow()
    print("desktop-windows-release: ok")


if __name__ == "__main__":
    main()
