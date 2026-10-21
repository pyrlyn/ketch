---
id: B97
title: Release builds expose the signing certificate to every build script
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: the certificate and its password reach only the macOS signing step, and `GH_TOKEN` only where needed. Plan: patch the env in `scripts/dist-generate.sh`, regenerate `release.yml` with `just dist-generate`.
Model: Claude Code / opus-5.5 · Status: done, merged in #311 · Priority: P2 · Complexity: 3
Check: the generated-file CI check passes and a grep shows no job-level `CODESIGN_*`.
