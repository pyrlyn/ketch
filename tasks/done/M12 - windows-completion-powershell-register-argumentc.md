---
id: M12
title: "Windows completion: PowerShell `Register-ArgumentCompleter` and doskey macros for cmd"
status: done
created_at: 1792583218
updated_at: 1792583218
---

Today clap_complete emits `Register-ArgumentCompleter -Native -CommandName 'ketch'`, and `ketch completions powershell --install` writes it to `Documents\PowerShell\Completions`. PowerShell does not load that directory by itself, so nothing is active until the user dot-sources it.

Plan:
1. PowerShell: a managed block in the CurrentUserAllHosts profile that dot-sources the script, for both PowerShell 7 (`Documents\PowerShell`) and Windows PowerShell 5.1 (`Documents\WindowsPowerShell`). Resolve Documents through the shell, not a fixed path, because OneDrive may redirect it. Use the same managed-block mechanism as the PATH blocks in `src/shell.rs`, so `self uninstall` removes it. Dynamic values come from M11's completer.
2. cmd: cmd.exe has no programmable argument completion, so ship doskey macros. Generate `share/ketch/ketch.doskey` (the macro list is for Ivan to choose; for example `ki=ketch install $*`, `ku=ketch upgrade $*`, `kl=ketch list $*`) and load it through `HKCU\Software\Microsoft\Command Processor\AutoRun` (`doskey /macrofile=<file>`). Append to an existing AutoRun value rather than replace it. Register the value in B66's inventory so `self uninstall` restores the old value.
3. Optional, only if Ivan wants real Tab completion in cmd: a clink Lua script generated from the CLI.

Check: Windows CI: `pwsh -c "TabExpansion2 'ketch ins' 9"` returns `install`; after install, AutoRun contains the doskey line and `ki` expands in a new cmd; after `self uninstall`, the profile block and the AutoRun addition are gone and an earlier AutoRun value is intact; `just check`.

Creator decisions: the macros are exactly `ki=ketch install $*`, `ku=ketch upgrade $*`, `kl=ketch list $*`, `kun=ketch uninstall $*`; no clink (step 3 skipped).

Result: the PowerShell script `ketch completions powershell` prints is clap_complete's with a package lookup spliced into its completer (`src/complete.rs`): the same command table as bash, answered by `ketch __complete`, written to run on Windows PowerShell 5.1 as well as 7. On Windows, `expose_self_docs` (run by `self install`, `self upgrade` and `completions --install`) now also calls `shell::install_powershell_profiles`, which puts a `# >>> ketch >>>` block dot-sourcing `Documents\PowerShell\Completions\ketch.ps1` into both editions' CurrentUserAllHosts profiles (Documents as PowerShell reports it; a new profile only when that edition is installed and its execution policy runs local scripts; a BOM for a new profile naming a non-ASCII path), and `shell::install_cmd_macros`, which writes `<root>\share\ketch\ketch.doskey` and appends ` & doskey /macrofile="…"` to `HKCU\Software\Microsoft\Command Processor\AutoRun`, keeping the value's kind and `%VAR%`s. Registry values reach PowerShell through environment variables. `self uninstall` (also with `--keep-packages`) lists and removes both: `shell::uninstall_powershell_profile` and `shell::uninstall_cmd_macros`, which removes exactly ketch's command and restores the earlier value or deletes the value when it held only ketch's. The AutoRun functions are small and named so B66's registry inventory can fold them in. Tests: AutoRun add/remove/round-trip, block rendering, quoting, BOM and policy as unit tests in `src/shell.rs`; the spliced script in `src/complete.rs`; `tests/completion_windows.rs` runs `TabExpansion2` in `pwsh` and `powershell`, and checks AutoRun, `doskey /macros` in a new cmd and the profiles after install and after `self uninstall`, saving and restoring the real AutoRun and profiles in a drop guard. Docs: `docs/COMMANDS.md`.

Status: done 2026-09-30
Model: Claude Code / opus-5.5
