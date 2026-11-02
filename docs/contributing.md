# Contributing

The full rules are in [AGENTS.md](../AGENTS.md). In short:

- Code, docs, comments, commits and PR text in English.
- Port behaviour, not syntax: every Rust unit and integration test of a ported file is ported to
  `kotlin.test`.
- Everything a release, an archive, a manifest or a registry contains is untrusted input; port
  every guard the Rust code has together with its test.
- Text the desktop app shows lives in `desktop/src/main/composeResources/values/strings.xml`, never
  as a literal in Kotlin. The command line stays English.
- ktlint and detekt must be clean: `./gradlew ktlintCheck detekt`, or `./gradlew ktlintFormat` to fix
  formatting.
- Every change ends in a pull request.
