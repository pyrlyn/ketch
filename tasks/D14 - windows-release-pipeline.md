---
id: D14
title: "Windows: release pipeline"
status: open
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

How the Windows app reaches users and updates itself, separate from the CLI's release. R10's open decisions on distribution and the Windows App SDK licence come first.

Done when CI builds, on `desktop-windows-v*` tags and without touching `/releases/latest`, both an MSIX and an unpackaged self-contained zip, signed (Artifact Signing or the creator's choice), updates arrive through the chosen route (Velopack or winget), and the creator's answers to R10's decisions are recorded. The creator chose to ship both forms. The MSIX is signed with a certificate held in repository secrets named the way the macOS ones are (`MACOS_CERTIFICATE`, `MACOS_CERTIFICATE_PWD`), and the release fails when they are missing rather than shipping unsigned.

Settings persistence differs between the two: an MSIX has `ApplicationData`, an unpackaged app has none (D12 stores settings in a way that works in both).
