---
status: in progress
priority: P0
complexity: 4
readiness: 50%
agent: Claude Code / claude-opus-5-5
---

# T7. Sources and resolution

`http`, `source` (`mod`, `github`, `local`, `plugin`), `resolve`, `releases`, `changelog`,
`info`, `registry`. GitHub API through the HTTP adapter of T3, with recorded responses in tests.

## Plan

About 6.2k lines of Rust and 117 tests, ported from 41738f64.

1. kotlin-kit `http`: a streaming GET (status, headers, body read in chunks) so ketch can cap a
   body while it arrives, stop on cancel and see `Content-Length`; Ktor adapter and MockEngine
   tests. Check that a redirect to another host drops `Authorization` (GitHub asset downloads
   redirect to a CDN).
2. `http` (nativeMain): `Http` over the kit client (blocking calls, agent settings of Rust:
   connect 15 s, read 120 s), `getJson`/`getText`/`getAssetText`/`getJsonOpt`, capped API bodies,
   staged downloads hashed while they stream, `classify`, `extractMessage`, `sha256File`. Tests
   with a fake client, plus a loopback server helper for the tests Rust runs over `TcpListener`.
3. `source/mod`: `ListOpts`, `Source`, `pick`, `selectRelease`, `SourceRegistry`.
4. `source/github`, `source/local`, `source/plugin` (protocol v1, discovery, bounded runs through
   kit `process`, asset URL allow-list).
5. `releases`, `resolve` (asset evaluation, explain trace), `changelog`.
6. `info`, `registry` (update, swap-in, check, metadata, fixtures).

Files: `ketch-core/src/nativeMain/kotlin/ketch/core/{http,source,releases,resolve,changelog,info,
registry}/`, tests beside them; kotlin-kit `http`, `http-ktor`. Check: `./gradlew build`, the
linuxArm64 compile with `-Pdiffuser.allTargets=true`, kit `check`, every ported test green on
the Mac.

