---
id: R13
title: Stale arm64-only prose left by the cask rewrite
status: done
created_at: 1792583218
updated_at: 1792583218
---

The arm64-only cask rewrite left prose behind: `tap.yml`'s header said "version and both macOS checksums" (one checksum is computed since Intel macOS dropped), `AGENTS.md` said "It builds all five targets" (dist-workspace.toml ships four) and repeated the "both checksums" wording. Found by the 2026-10-07 audit. The README's pyrlyn/ketch badges needed no change: the repository moved to the pyrlyn org, so the links are the current ones. Done means the prose matches the arm64-only reality.
Model: ZCode / GLM-5.3 · Status: done 2026-10-08 · Priority: P3 · Complexity: 1 · Files: `.github/workflows/tap.yml`, `AGENTS.md`
Check: `grep -rn "both checksums|five targets" AGENTS.md .github/workflows/tap.yml` — no matches.
