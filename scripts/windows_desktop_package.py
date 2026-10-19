#!/usr/bin/env python3
# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

"""Package the Windows app as an unpackaged zip and an MSIX.

`release-windows-desktop.yml` calls this. Pull-request CI calls the same
`package` command without `--sign`, so the zip and the MSIX are built on
every change and a release is the only place a certificate is required.

The signing password stays in the environment. Nothing in this file puts
`WINDOWS_CERTIFICATE` or `WINDOWS_CERTIFICATE_PWD` on a command line or
writes either value to stdout or stderr.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import xml.sax.saxutils
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO / "target" / "windows-desktop"
VERSION_RE = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
THUMB_RE = re.compile(r"^[0-9A-Fa-f]+$")
PUBLISHER_UNSIGNED = "CN=Pyrlyn"
IDENTITY = "Pyrlyn.Ketch"
WINGET_VERSION = "1.9.0"


class PackageError(Exception):
    """A failure whose message is safe to print."""


def validate_version(raw: str) -> str:
    """Return `raw` when it is a plain `X.Y.Z` each component fits in an MSIX."""
    if VERSION_RE.fullmatch(raw) is None:
        raise PackageError("version must be X.Y.Z")
    if any(int(part) > 65535 for part in raw.split(".")):
        raise PackageError("version component exceeds 65535")
    return raw


def version_from_env() -> str:
    raw = os.environ.get("VERSION", "")
    try:
        return validate_version(raw)
    except PackageError:
        raise PackageError("VERSION must be X.Y.Z") from None


def appx_version(version: str) -> str:
    """MSIX identity versions have four components. The fourth stays 0."""
    return f"{validate_version(version)}.0"


def require_signing() -> None:
    """Fail before a build when a signed release would ship unsigned.

    An empty password is missing. A password that is only spaces is still a
    password, so it is not stripped.
    """
    cert = os.environ.get("WINDOWS_CERTIFICATE", "")
    pwd = os.environ.get("WINDOWS_CERTIFICATE_PWD", "")
    if cert.strip() == "" or pwd == "":
        raise PackageError(
            "WINDOWS_CERTIFICATE and WINDOWS_CERTIFICATE_PWD are required; refusing to ship unsigned"
        )
    if os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise PackageError("Windows app releases are cut from main")


def release_repo() -> str:
    repo = os.environ.get("GITHUB_REPOSITORY", "pyrlyn/ketch")
    if REPO_RE.fullmatch(repo) is None:
        raise PackageError("GITHUB_REPOSITORY is not an owner/name")
    return repo


def appx_manifest(version: str, publisher: str) -> str:
    """The manifest makeappx packs beside the published files.

    Logos are generated into `Assets` at pack time. The publisher is the
    certificate subject when signing, and `CN=Pyrlyn` for the unsigned
    package pull-request CI builds.
    """
    safe_publisher = xml.sax.saxutils.escape(publisher, {'"': "&quot;"})
    return f"""<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap rescap">
  <Identity
    Name="{IDENTITY}"
    Publisher="{safe_publisher}"
    Version="{appx_version(version)}"
    ProcessorArchitecture="x64" />
  <Properties>
    <DisplayName>Ketch</DisplayName>
    <PublisherDisplayName>Pyrlyn</PublisherDisplayName>
    <Logo>Assets\\StoreLogo.png</Logo>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.19041.0" MaxVersionTested="10.0.26100.0" />
  </Dependencies>
  <Resources>
    <Resource Language="en-us" />
  </Resources>
  <Applications>
    <Application Id="Ketch" Executable="Ketch.exe" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements
        DisplayName="Ketch"
        Description="Install command-line tools from GitHub releases"
        BackgroundColor="transparent"
        Square150x150Logo="Assets\\Square150x150Logo.png"
        Square44x44Logo="Assets\\Square44x44Logo.png" />
    </Application>
  </Applications>
  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
  </Capabilities>
</Package>
"""


def png_bytes(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """A solid RGB PNG. The MSIX schema requires the logos the manifest names."""

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    red, green, blue = rgb
    row = b"\x00" + bytes((red, green, blue)) * width
    raw = row * height
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def write_logos(assets: Path) -> None:
    assets.mkdir(parents=True, exist_ok=True)
    # A flat brand color. Real store art can replace these without a schema change.
    color = (18, 92, 84)
    for name, size in (
        ("StoreLogo.png", 50),
        ("Square44x44Logo.png", 44),
        ("Square150x150Logo.png", 150),
    ):
        (assets / name).write_bytes(png_bytes(size, size, color))


def ignore_pdb(_directory: str, names: list[str]) -> set[str]:
    return {name for name in names if name.lower().endswith(".pdb")}


def zip_publish(publish: Path, dest: Path) -> None:
    """Zip the publish tree with `Ketch.exe` at the archive root. PDBs stay out."""
    exe = publish / "Ketch.exe"
    if not exe.is_file():
        raise PackageError("publish output has no Ketch.exe")
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        dest.unlink()
    import zipfile

    with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(publish.rglob("*")):
            if not path.is_file() or path.is_symlink():
                continue
            if path.suffix.lower() == ".pdb":
                continue
            archive.write(path, path.relative_to(publish).as_posix())


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _yaml_quote(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def winget_documents(version: str, zip_sha: str, msix_sha: str, repo: str) -> dict[str, str]:
    """The three manifests a winget submission needs. URLs point at this release."""
    validate_version(version)
    tag = f"desktop-windows-v{version}"
    base = f"https://github.com/{repo}/releases/download/{tag}"
    zip_name = f"Ketch-{version}-win-x64.zip"
    msix_name = f"Ketch-{version}-win-x64.msix"
    version_doc = "\n".join(
        [
            f"PackageIdentifier: {IDENTITY}",
            f"PackageVersion: {version}",
            "DefaultLocale: en-US",
            "ManifestType: version",
            f"ManifestVersion: {WINGET_VERSION}",
            "",
        ]
    )
    locale_doc = "\n".join(
        [
            f"PackageIdentifier: {IDENTITY}",
            f"PackageVersion: {version}",
            "PackageLocale: en-US",
            "Publisher: Pyrlyn",
            "PackageName: Ketch",
            "License: GPL-3.0-or-later",
            "ShortDescription: Install command-line tools from GitHub releases",
            "ManifestType: defaultLocale",
            f"ManifestVersion: {WINGET_VERSION}",
            "",
        ]
    )
    installer_doc = "\n".join(
        [
            f"PackageIdentifier: {IDENTITY}",
            f"PackageVersion: {version}",
            "Installers:",
            "  - Architecture: x64",
            "    InstallerType: zip",
            f"    InstallerUrl: {_yaml_quote(f'{base}/{zip_name}')}",
            f"    InstallerSha256: {zip_sha}",
            "  - Architecture: x64",
            "    InstallerType: msix",
            f"    InstallerUrl: {_yaml_quote(f'{base}/{msix_name}')}",
            f"    InstallerSha256: {msix_sha}",
            "ManifestType: installer",
            f"ManifestVersion: {WINGET_VERSION}",
            "",
        ]
    )
    return {
        f"{IDENTITY}.yaml": version_doc,
        f"{IDENTITY}.installer.yaml": installer_doc,
        f"{IDENTITY}.locale.en-US.yaml": locale_doc,
    }


def write_winget(directory: Path, version: str, zip_path: Path, msix_path: Path) -> None:
    documents = winget_documents(version, sha256_file(zip_path), sha256_file(msix_path), release_repo())
    directory.mkdir(parents=True, exist_ok=True)
    for name, text in documents.items():
        (directory / name).write_text(text, encoding="utf-8", newline="\n")


def sdk_tool(exe: str) -> Path | None:
    """The newest Windows SDK copy of `exe`, or None off Windows and when the kit is absent."""
    if sys.platform != "win32":
        return None
    roots = []
    program_files = os.environ.get("ProgramFiles(x86)")
    if program_files:
        roots.append(Path(program_files))
    roots.append(Path(r"C:\Program Files (x86)"))
    found: list[Path] = []
    seen: set[Path] = set()
    for root in roots:
        if root in seen:
            continue
        seen.add(root)
        kit = root / "Windows Kits" / "10" / "bin"
        if kit.is_dir():
            found.extend(kit.glob(f"*/x64/{exe}"))

    def sort_key(path: Path) -> tuple[int, ...]:
        parts = []
        for piece in path.parent.parent.name.split("."):
            parts.append(int(piece) if piece.isdigit() else -1)
        return tuple(parts)

    if not found:
        return None
    return sorted(found, key=sort_key)[-1]


def pack_msix(layout: Path, dest: Path, *, required: bool) -> bool:
    tool = sdk_tool("makeappx.exe")
    if tool is None:
        if required:
            raise PackageError("makeappx.exe was not found; refusing to skip the MSIX")
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        dest.unlink()
    completed = subprocess.run([str(tool), "pack", "/d", str(layout), "/p", str(dest), "/o"], check=False)
    if completed.returncode != 0 or not dest.is_file():
        raise PackageError("makeappx pack failed")
    return True


def signtool_command(signtool: Path, thumbprint: str, msix: Path) -> list[str]:
    """Sign by thumbprint. The password never appears in this argument list."""
    if THUMB_RE.fullmatch(thumbprint) is None:
        raise PackageError("the certificate thumbprint is not hex")
    return [str(signtool), "sign", "/fd", "SHA256", "/sha1", thumbprint, "/s", "My", str(msix)]


def _powershell(script: str, env: dict[str, str]) -> None:
    completed = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
        check=False,
        env=env,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        # The catch block prints a fixed sentence. Discard anything else: a
        # certificate API can echo the password in its own exception text.
        message = (completed.stderr or "").strip().splitlines()
        safe = message[-1] if message else "the signing certificate could not be used"
        pwd = os.environ.get("WINDOWS_CERTIFICATE_PWD", "")
        if "WINDOWS_CERTIFICATE" in safe or (pwd != "" and pwd in safe):
            safe = "the signing certificate could not be used"
        raise PackageError(safe)


def import_certificate(pfx: Path, subject_out: Path, thumb_out: Path) -> str:
    script = r"""
$ErrorActionPreference = "Stop"
try {
  $pwd = ConvertTo-SecureString -String $env:WINDOWS_CERTIFICATE_PWD -Force -AsPlainText
  $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($env:KETCH_PFX, $pwd)
  [System.IO.File]::WriteAllText($env:KETCH_SUBJECT_OUT, $cert.Subject)
  $imported = @(Import-PfxCertificate -FilePath $env:KETCH_PFX -CertStoreLocation Cert:\CurrentUser\My -Password $pwd)
  $leaf = $imported | Select-Object -Last 1
  [System.IO.File]::WriteAllText($env:KETCH_THUMB_OUT, $leaf.Thumbprint)
} catch {
  [Console]::Error.WriteLine("could not read the signing certificate")
  exit 1
}
"""
    env = os.environ.copy()
    env["KETCH_PFX"] = str(pfx)
    env["KETCH_SUBJECT_OUT"] = str(subject_out)
    env["KETCH_THUMB_OUT"] = str(thumb_out)
    _powershell(script, env)
    if not subject_out.is_file() or not thumb_out.is_file():
        raise PackageError("could not read the signing certificate")
    subject = subject_out.read_text(encoding="utf-8").strip()
    thumb = thumb_out.read_text(encoding="utf-8").strip()
    if not subject.startswith("CN=") or THUMB_RE.fullmatch(thumb) is None:
        raise PackageError("could not read the signing certificate")
    return subject


def remove_imported_certificate(thumb_out: Path) -> None:
    # A thumbprint that is not hex must not be interpolated into a cert-store
    # path: an empty one would match the whole personal store.
    thumb = thumb_out.read_text(encoding="utf-8").strip()
    if THUMB_RE.fullmatch(thumb) is None:
        raise PackageError("could not remove the imported signing certificate")
    script = r"""
$ErrorActionPreference = "Stop"
try {
  $thumb = $env:KETCH_THUMB
  if ($thumb -notmatch '^[0-9A-Fa-f]+$') {
    [Console]::Error.WriteLine("could not remove the imported signing certificate")
    exit 1
  }
  Get-ChildItem -Path "Cert:\CurrentUser\My\$thumb" | Remove-Item
} catch {
  [Console]::Error.WriteLine("could not remove the imported signing certificate")
  exit 1
}
"""
    env = os.environ.copy()
    env["KETCH_THUMB"] = thumb
    _powershell(script, env)


def decode_certificate(dest: Path) -> None:
    raw = os.environ.get("WINDOWS_CERTIFICATE", "")
    compact = "".join(raw.split())
    pad = "=" * (-len(compact) % 4)
    try:
        data = base64.b64decode(compact + pad, validate=True)
    except Exception:
        raise PackageError("WINDOWS_CERTIFICATE is not base64") from None
    if not data:
        raise PackageError("WINDOWS_CERTIFICATE is empty")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    try:
        os.chmod(dest, 0o600)
    except OSError:
        pass


def sign_msix(msix: Path, thumbprint: str) -> None:
    tool = sdk_tool("signtool.exe")
    if tool is None:
        raise PackageError("signtool.exe was not found")
    completed = subprocess.run(signtool_command(tool, thumbprint, msix), check=False, capture_output=True)
    if completed.returncode != 0:
        raise PackageError("signtool sign failed")


def run_dotnet_publish(dest: Path) -> None:
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True, exist_ok=True)
    completed = subprocess.run(
        [
            "dotnet",
            "publish",
            str(REPO / "desktop" / "windows" / "Ketch.App" / "Ketch.App.csproj"),
            "-c",
            "Release",
            "-r",
            "win-x64",
            "--self-contained",
            "true",
            "-p:Platform=x64",
            "-p:WindowsPackageType=None",
            "-p:WindowsAppSDKSelfContained=true",
            "-o",
            str(dest),
        ],
        check=False,
        cwd=REPO,
    )
    if completed.returncode != 0:
        raise PackageError("dotnet publish failed")


def assemble(publish: Path, out: Path, version: str, *, require_msix: bool, sign: bool) -> None:
    """Build the zip, the MSIX and the winget manifests under `out`."""
    version = validate_version(version)
    if sign:
        require_msix = True
    if not publish.is_dir():
        raise PackageError("publish directory is missing")
    out.mkdir(parents=True, exist_ok=True)
    pfx = out / "signing.pfx"
    subject_out = out / "signing.subject"
    thumb_out = out / "signing.thumb"
    try:
        publisher = PUBLISHER_UNSIGNED
        if sign:
            require_signing()
            if sys.platform != "win32":
                raise PackageError("signing runs on Windows")
            decode_certificate(pfx)
            publisher = import_certificate(pfx, subject_out, thumb_out)
        zip_path = out / f"Ketch-{version}-win-x64.zip"
        zip_publish(publish, zip_path)
        layout = out / "msix-layout"
        if layout.exists():
            shutil.rmtree(layout)
        shutil.copytree(publish, layout, ignore=ignore_pdb, symlinks=False)
        (layout / "AppxManifest.xml").write_text(appx_manifest(version, publisher), encoding="utf-8", newline="\n")
        write_logos(layout / "Assets")
        msix_path = out / f"Ketch-{version}-win-x64.msix"
        if not pack_msix(layout, msix_path, required=require_msix):
            return
        if sign:
            sign_msix(msix_path, thumb_out.read_text(encoding="utf-8").strip())
        write_winget(out / "winget", version, zip_path, msix_path)
    finally:
        # The thumb file exists only after the certificate was imported.
        if thumb_out.exists():
            try:
                remove_imported_certificate(thumb_out)
            except PackageError as exc:
                print(exc, file=sys.stderr)
        for secret_file in (pfx, subject_out, thumb_out):
            if secret_file.exists():
                secret_file.unlink()


def release_create_argv(version: str, assets: list[Path]) -> list[str]:
    validate_version(version)
    tag = f"desktop-windows-v{version}"
    notes = (
        "Unpackaged self-contained zip and signed MSIX. "
        "Updates are installed with winget; the winget manifests are attached. "
        "Submitting them to microsoft/winget-pkgs is a separate maintainer step."
    )
    return [
        "gh",
        "release",
        "create",
        tag,
        "--latest=false",
        "--prerelease",
        "--title",
        f"Ketch for Windows {version}",
        "--notes",
        notes,
        *[str(path) for path in assets],
    ]


def artifact_paths(out: Path, version: str) -> list[Path]:
    return [
        out / f"Ketch-{version}-win-x64.zip",
        out / f"Ketch-{version}-win-x64.msix",
        out / "winget" / f"{IDENTITY}.yaml",
        out / "winget" / f"{IDENTITY}.installer.yaml",
        out / "winget" / f"{IDENTITY}.locale.en-US.yaml",
    ]


def latest_release_tag() -> str:
    """The tag `/releases/latest` names, or empty when GitHub has no stable release."""
    repo = release_repo()
    completed = subprocess.run(
        ["gh", "api", f"repos/{repo}/releases/latest", "--jq", ".tag_name"],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        err = completed.stderr or ""
        if "404" in err or "Not Found" in err:
            return ""
        raise PackageError("could not read /releases/latest")
    return completed.stdout.strip()


def record_latest(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(latest_release_tag() + "\n", encoding="utf-8", newline="\n")


def restore_previous_latest(new_tag: str, previous: str) -> None:
    subprocess.run(["gh", "release", "edit", new_tag, "--latest=false", "--prerelease"], check=False)
    if previous:
        # Put the CLI release back. `--latest` here is the restore, not a flag
        # this repository's workflows set on an app release.
        subprocess.run(["gh", "release", "edit", previous, "--latest"], check=False)


def publish_release(out: Path, latest_file: Path) -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise PackageError("Windows app releases are cut from main")
    version = version_from_env()
    assets = artifact_paths(out, version)
    missing = [path.name for path in assets if not path.is_file()]
    if missing:
        raise PackageError("missing release asset: " + ", ".join(missing))
    before = latest_file.read_text(encoding="utf-8").strip()
    completed = subprocess.run(release_create_argv(version, assets), check=False)
    if completed.returncode != 0:
        raise PackageError("gh release create failed")
    after = latest_release_tag()
    if after != before:
        restore_previous_latest(f"desktop-windows-v{version}", before)
        raise PackageError("/releases/latest moved")


def cmd_package(args: argparse.Namespace) -> None:
    version = version_from_env()
    out = Path(args.out_dir)
    publish = Path(args.publish_dir) if args.publish_dir else out / "publish"
    if args.publish:
        run_dotnet_publish(publish)
    assemble(publish, out, version, require_msix=args.require_msix, sign=args.sign)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Package or publish the Windows desktop app.")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("require-signing", help="exit if the Windows signing secrets are missing")

    record = sub.add_parser("record-latest", help="write the current /releases/latest tag")
    record.add_argument("--out", required=True)

    package = sub.add_parser("package", help="build the zip, MSIX and winget manifests")
    package.add_argument("--publish", action="store_true", help="dotnet publish a self-contained x64 build first")
    package.add_argument("--publish-dir")
    package.add_argument("--out-dir", default=str(DEFAULT_OUT))
    package.add_argument("--require-msix", action="store_true")
    package.add_argument("--sign", action="store_true")

    publish = sub.add_parser("publish", help="create the desktop-windows release and check /releases/latest")
    publish.add_argument("--out-dir", default=str(DEFAULT_OUT))
    publish.add_argument("--latest-file", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "require-signing":
            require_signing()
        elif args.command == "record-latest":
            record_latest(Path(args.out))
        elif args.command == "package":
            cmd_package(args)
        elif args.command == "publish":
            publish_release(Path(args.out_dir), Path(args.latest_file))
        else:
            raise PackageError("unknown command")
    except PackageError as exc:
        print(exc, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
