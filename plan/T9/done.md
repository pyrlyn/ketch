# T9. Import and registry push — done

Ported `import` (`mod`, `fetch`, `brew`, `linux`, `winget`, `apply`) into `ketch.core.importer`
and `push` into `ketch.core.push`, with their tests in `kotlin.test`: 90 Kotlin tests (import 14,
fetch 2, brew 15, linux 13, winget 14, apply 11, push 21) for Rust's 90 test functions and
`rstest` cases, one for one by name, plus the two wizard tests that load through `push`
(`WizardPushTest`). The catalogue tests run on the recorded fixtures copied to
`fixtures/rust/import`, through a recorded `Fetch`; the push tests run on a fake `Api`. Every guard
came with its test: names checked before they reach a URL, only GitHub release assets accepted
(one off-GitHub download rejects the package), non-SHA-256 checksums dropped rather than trusted,
foreign package formats and installers refused by name, a hand-written manifest never
overwritten, a contents-API file over 1 MB refused instead of read as empty, and a pull request
into another base not taken for this proposal's.

kotlin-kit `http` gained PUT and PATCH (Ktor adapter included, with a test); ketch's `Http`
gained `sendJson`. The winget YAML goes through kit `yaml-aot`. See `plan/README.md` for the
decisions.

Commits: kotlin-kit 9cbb047; ketch-kotlin 8b658e5, 182b02f, ba00936, 97cf5c0, 8bee4b2,
fbb7b07, 67696a4 and this close.

Checked on the Mac: `./gradlew build` (776 native tests, ktlint, detekt) is green, `./gradlew
check` in kotlin-kit is green, and `-Pdiffuser.allTargets=true :ketch-core:compileKotlinLinuxArm64
:ketch-core:compileTestKotlinLinuxArm64` compiles. No test reaches the network or the real HOME.
