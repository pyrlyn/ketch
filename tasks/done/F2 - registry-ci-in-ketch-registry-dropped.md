---
id: F2
title: Registry CI in ketch-registry — dropped
status: done
created_at: 1792583854
updated_at: 1792583854
---

`ketch registry validate` exists in this repo (tree checks, name/alias collisions, optional `--fixture` / `--changed` offline-install). There is deliberately no registry-side workflow: ketch-registry removed its only workflow (commit `5a9bbd6`, "no CI is wanted in this repo"). The documented substitute is local validation plus a pre-push hook (`docs/REGISTRY.md`). Collisions are fatal in `validate` and warnings on `update` by design.
