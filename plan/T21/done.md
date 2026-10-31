# T21. Done

The desktop app's words moved out of Kotlin into `desktop/src/main/composeResources/values/strings.xml`: 295 strings and
13 plurals, in English, with the key convention and the glossary at the head of the file. The four looks share a key
where they say the same thing. Compose resources were taken because they are part of the Compose the app already uses
and give a typed key per string, so a missing one fails to compile; no version changed.

- `stringResource` serves composables. `tr` and `plural` (`ketch.desktop.strings`) serve the enums, the tray, the store
  and the core's errors, since Compose's own read suspends.
- `plural()` takes a plural resource rather than an English noun. Sentences glued from a count and a word became whole
  plurals, because other languages inflect the word.
- Swatches, window materials and the Settings panes had names derived from values; they have their own strings now.
- `CFBundleLocalizations` lists `en`.

Out of scope, and still English: the command line, whose output must stay interchangeable with Rust ketch, and what
`ketch serve` sends. Commands, tags, paths, date patterns, the exported log's format and the Ketch name stay literals.

Commit: `7092f19` on `main`; the repository has no remote, so there is no PR.

Checked:

- `./gradlew :desktop:check ktlintCheck` passes, 234 tests with no test changed, so the English text is the same as
  before. detekt accepts the wildcard import of the strings package.
- A throwaway `values-ru` under `-Duser.language=ru`, not committed: the app rendered Russian on Discover, enums
  included; `plural` gave 1 пакет, 2 пакета, 5 пакетов, 21 пакет; a key the file left out fell back to English.
- `tr` takes about 7 µs a call once the file is read.
- `:desktop:createDistributable` builds, its `Info.plist` lists `en`, and the packaged `Ketch.app` ran for 12 seconds on
  a scratch home with nothing on stdout or stderr. That shows the trimmed runtime loads the resources' code; nobody
  looked at that window.
- The checks ran against an export of `packages` at `origin/main`, because the local checkout of `packages/` is on
  branch `kotlin-glass` and has no `kotlin-adwaita`.
