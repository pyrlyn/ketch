---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T9. Import and registry push

`import` (`mod`, `brew`, `linux`, `winget`, `apply`, `fetch`) and `push` (`ketch registry
push`: forks, refs, contents and pull requests through the GitHub REST API, as octocrab does).

Two wizard tests wait here because they load through `push`:
`the_push_loader_accepts_what_the_wizard_renders` and
`quotes_and_backslashes_in_an_answer_survive_the_round_trip` (`wizard.rs`). Port them with `push`.
