#!/usr/bin/env python3
# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

"""Manifest and version check for the Windows app release.

The release workflow writes `Package.appxmanifest` only on the runner, just
before the MSIX publish. The WinUI project stays unpackaged in git, so the
ordinary `dotnet build` does not grow a packaging manifest it does not use.

An MSIX virtualises `HKCU` unless it declares `unvirtualizedResources`. ketch
writes the real user PATH there; a virtualised write would never show up in
a new terminal. The capability is restricted, which is why the package is
sideloaded with our certificate rather than published to the Store.
"""

from __future__ import annotations

import argparse
import struct
import subprocess
import sys
import zlib
from pathlib import Path

TAG_PREFIX = "desktop-windows-v"
# MSIX identity versions are four numbers, each at most 65535.
_PART_MAX = 65535


def parse_version(text: str) -> tuple[int, int, int]:
    """`X.Y.Z` with no prefix, suffix, or leading zeros."""
    parts = text.strip().split(".")
    if len(parts) != 3:
        raise ValueError(f"version must be X.Y.Z, not {text!r}")
    numbers: list[int] = []
    for part in parts:
        if not part.isdigit() or str(int(part)) != part:
            raise ValueError(f"version must be X.Y.Z, not {text!r}")
        number = int(part)
        if number > _PART_MAX:
            raise ValueError(f"version part {number} is above {_PART_MAX}")
        numbers.append(number)
    return numbers[0], numbers[1], numbers[2]


def msix_version(version: str) -> str:
    major, minor, patch = parse_version(version)
    return f"{major}.{minor}.{patch}.0"


def tag_for(version: str) -> str:
    parse_version(version)
    return f"{TAG_PREFIX}{version}"


def version_of_tag(tag: str) -> tuple[int, int, int] | None:
    if not tag.startswith(TAG_PREFIX):
        return None
    try:
        return parse_version(tag[len(TAG_PREFIX) :])
    except ValueError:
        return None


def assert_newer(version: str, tags: list[str]) -> None:
    """Refuse a version that is not strictly above every Windows app tag."""
    candidate = parse_version(version)
    previous = [parsed for tag in tags if (parsed := version_of_tag(tag.strip()))]
    if not previous:
        return
    latest = max(previous)
    if candidate <= latest:
        shown = ".".join(str(part) for part in latest)
        raise ValueError(
            f"{version} is not above the last Windows app release {shown}"
        )


def git_tags(root: Path) -> list[str]:
    result = subprocess.run(
        ["git", "tag", "--list", f"{TAG_PREFIX}*"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in result.stdout.splitlines() if line]


def xml_attr(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace('"', "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def publisher_text(raw: str) -> str:
    """The certificate subject, one line, with the file's trailing newline gone."""
    publisher = raw.strip()
    if not publisher:
        raise ValueError("the MSIX publisher is empty")
    if any(char in publisher for char in "\r\n"):
        raise ValueError("the MSIX publisher must be one line")
    return publisher


def manifest_xml(version: str, publisher: str) -> str:
    publisher = publisher_text(publisher)
    identity = msix_version(version)
    # The square logos and the store logo are the sizes the packaging
    # targets ask for. They are generated beside this manifest.
    return f"""<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap rescap">
  <Identity
    Name="Pyrlyn.Ketch"
    Publisher="{xml_attr(publisher)}"
    Version="{identity}" />
  <Properties>
    <DisplayName>Ketch</DisplayName>
    <PublisherDisplayName>Pyrlyn</PublisherDisplayName>
    <Logo>Assets\\StoreLogo.png</Logo>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.19041.0" MaxVersionTested="10.0.26100.0" />
  </Dependencies>
  <Resources>
    <Resource Language="en-US" />
  </Resources>
  <Applications>
    <Application Id="App" Executable="$targetnametoken$.exe" EntryPoint="$targetentrypoint$">
      <uap:VisualElements
        DisplayName="Ketch"
        Description="Catch releases straight from GitHub"
        BackgroundColor="transparent"
        Square150x150Logo="Assets\\Square150x150Logo.png"
        Square44x44Logo="Assets\\Square44x44Logo.png" />
    </Application>
  </Applications>
  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
    <rescap:Capability Name="unvirtualizedResources" />
  </Capabilities>
</Package>
"""


def write_png(path: Path, width: int, height: int) -> None:
    """A solid RGBA PNG. The packaging step checks the dimensions, not the art."""
    if width < 1 or height < 1:
        raise ValueError("png size must be positive")
    colour = bytes((0x1F, 0x6F, 0xEB, 0xFF))

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    rows = b"".join(b"\x00" + colour * width for _ in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(rows, 9))
        + chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def write_package(app_dir: Path, version: str, publisher: str) -> None:
    assets = app_dir / "Assets"
    write_png(assets / "StoreLogo.png", 50, 50)
    write_png(assets / "Square44x44Logo.png", 44, 44)
    write_png(assets / "Square150x150Logo.png", 150, 150)
    (app_dir / "Package.appxmanifest").write_text(
        manifest_xml(version, publisher), encoding="utf-8"
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    check = sub.add_parser("check-version")
    check.add_argument("version")
    check.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )

    manifest = sub.add_parser("manifest")
    manifest.add_argument("--version", required=True)
    manifest.add_argument("--publisher")
    manifest.add_argument("--publisher-file", type=Path)
    manifest.add_argument("--app-dir", type=Path, required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "check-version":
            assert_newer(args.version, git_tags(args.root))
            print(tag_for(args.version))
        else:
            # The subject is read from a file so a comma or quote in it is
            # not split by the shell that launches this process.
            if args.publisher_file is not None:
                raw = args.publisher_file.read_text(encoding="utf-8")
            elif args.publisher is not None:
                raw = args.publisher
            else:
                raise ValueError("the MSIX publisher is empty")
            write_package(args.app_dir, args.version, publisher_text(raw))
            print(args.app_dir / "Package.appxmanifest")
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"windows-desktop-release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
