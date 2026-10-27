# T7. Sources and resolution — done

Ported `http`, `source` (`mod`, `github`, `local`, `plugin`), `resolve`, `releases`,
`changelog`, `info` and `registry` from ketch-core into `ketch-core/src/{nativeMain,commonMain}`,
with every unit test of those files in `kotlin.test`: 126 Kotlin tests in those packages against
Rust's 117. Every guard came with its test: the
plugin asset URL allow-list and output cap, capped API bodies, checksum hashing while a download
streams, the registry's refusal of local paths, of names that would escape the store, of
symlinked package files and of an empty tree, and the copy-back when renaming the old registry
into place fails.

kotlin-kit gained a streaming GET in `http`, the `loopback` test server, a named temporary file
persisted by rename, link permission bits and `TempDir.createIn`. The shared `fsKitAt` replaced
two private copies in `manifest` and `extra`. The decisions are in `plan/README.md`.

Commits: kotlin-kit fccc20b (http), 1ad2eb2 (loopback), 9a33e0f, f90d9f1, 694c74a (fs);
ketch-kotlin e947139, e02d9e5, 1740e79, 0600323, c6cbb46, b8b3d72, 21239d1, 1d3cf64, acc84a3,
bfc37c4, b729b57, de9ccbd and this close.

Checked on the Mac: `./gradlew build` (537 native tests, ktlint, detekt) is green, `./gradlew
check` in kotlin-kit is green, and `-Pdiffuser.allTargets=true :ketch-core:compileKotlinLinuxArm64
:ketch-core:compileTestKotlinLinuxArm64` compiles. No test reaches the network or the real HOME.
