---
id: F1
title: Notarisation — done (ketch side)
status: done
created_at: 1792583218
updated_at: 1792583218
---

Done on the ketch side: `release.yml` has a `Notarise` step for the two signed targets, gated on `KETCH_NOTARIZE == 'true'`. Once on, it fails on a missing `APPSTORE_CONNECT_KEY` (the `.p8`, base64), `APPSTORE_CONNECT_KEY_ID` or `APPSTORE_CONNECT_ISSUER_ID`. It zips the packed binary with `ditto`, runs `xcrun notarytool submit --wait`, and fails unless the status is `Accepted`. The smoke test requires `spctl` to report `source=Notarized Developer ID`. `AGENTS.md` Releasing documents the switch and the three secrets. `tests/release-yml-notarize.sh` is wired into `just lint-shell` and CI.

Not done (creator step, needs the App Store Connect key): add the secrets, set `KETCH_NOTARIZE=true`, and run a release.
