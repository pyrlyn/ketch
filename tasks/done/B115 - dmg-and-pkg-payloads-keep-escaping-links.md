---
id: B115
title: DMG and PKG payloads keep escaping links
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: after the copy every link goes through the B114 containment check. Plan: walk the tree in `extract/macos.rs`.
Model: Claude Code / opus-5.5 · Status: done, merged in #305 · Priority: P3 · Complexity: 3
Check: a macOS-gated test with a nested escaping link.
