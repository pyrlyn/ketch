---
id: D14
title: "Windows: release pipeline"
status: done
created_at: 1792583854
updated_at: 1792583854
---

How the Windows app reaches users and updates itself, separate from the CLI's release. R10's open decisions on distribution and the Windows App SDK licence come first.

Done when CI builds, on `desktop-windows-v*` tags and without touching `/releases/latest`, both an MSIX and an unpackaged self-contained zip, signed (Artifact Signing or the creator's choice), updates arrive through the chosen route (Velopack or winget), and the creator's answers to R10's decisions are recorded. The creator chose to ship both forms. The MSIX is signed with a certificate held in repository secrets named the way the macOS ones are (`MACOS_CERTIFICATE`, `MACOS_CERTIFICATE_PWD`), and the release fails when they are missing rather than shipping unsigned.

Settings persistence differs between the two: an MSIX has `ApplicationData`, an unpackaged app has none (D12 stores settings in a way that works in both).

Status: the dispatch-only workflow `.github/workflows/release-windows-desktop.yml` publishes both artifacts under `desktop-windows-vX.Y.Z` and refuses to become `/releases/latest`. It is not triggered by the tag: the run creates the tag after the zip and the signed MSIX exist, the same way the macOS app release does, so a tag push cannot publish on its own. `select_release` drops `desktop-windows-v*` when a real version tag is present. Signing uses `WINDOWS_CERTIFICATE` (base64 pfx) and `WINDOWS_CERTIFICATE_PWD`, step env only, and the job stops before building when either is missing. Those are not `MACOS_CERTIFICATE`: an Apple Developer ID certificate cannot sign an MSIX, and a Windows job must not see it. The MSIX manifest is written on the runner (`unvirtualizedResources`, so PATH edits hit the real `HKCU`), and the unpackaged project in git stays `WindowsPackageType=None`. x64 only. Still the creator's: place the two Windows secrets (this workflow has not been dispatched, and no release was cut); an update channel (Velopack would change the app and cannot be checked here, winget submission needs a Microsoft account); and R10's open question of the Windows App SDK licence against the GPL-3.0-only build (`docs/research-desktop-windows-linux.md`, open decisions 3 and 4).
