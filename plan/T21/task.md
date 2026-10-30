---
status: in progress
priority: P2
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T21. Make the desktop app localizable, English only for now

Every word the desktop app shows is a literal in Kotlin, repeated across the four looks, so a translation would mean
editing the code. Done means the app's text lives in one resource file, English is its only language, the app looks the
same as before, and adding a language is adding a file plus one line in the app's Info.plist.

The `ketch` command line is out of scope: its output must stay interchangeable with Rust ketch. So is text the app
passes on from `ketch serve` (error messages, event verbs, doctor checks), which stays English until the core says it
otherwise. What is not language stays a literal: commands, tags, paths, font names, the Ketch name.

## Execution plan

1. Add Compose resources to `desktop/` (`compose.components.resources`), with the generated `Res` in a package of ours.
   `stringResource` serves composables; a small `tr` serves the code outside composition (enums, the tray, the store),
   because Compose's own read suspends.
2. Write `desktop/src/main/composeResources/values/strings.xml`: one key per meaning, not per look, `<plurals>` wherever
   a number picks the wording. The key convention and the glossary of ketch's terms head the file.
3. Replace the literals in `desktop/src/main/kotlin`; `plural()` takes a plural resource rather than an English noun.
4. List `en` in `CFBundleLocalizations`. Record the decision and how to add a language in `plan/README.md`, the rule in
   `AGENTS.md`, the dependency in `toolchain.md`.

Check: `./gradlew build` passes with the existing tests unchanged, which assert the English text; a throwaway
`values-ru` shows the app in Russian under `-Duser.language=ru`, and is not committed; the packaged app starts.
