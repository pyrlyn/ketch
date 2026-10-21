---
id: A1
title: Audit fixes already landed
status: done
created_at: 1792583854
updated_at: 1792583854
---

`unplace`/`is_ours` no longer delete a file the user put where a link used to be; `install.sh` no longer links the installed binary to itself when `--install-dir` respells `<root>/bin` (nor resolves a relative `--root` inside its own temp dir); a Windows-built zip whose members have no execute bit installs; `registry validate` refuses a symlinked `ketch.toml` and a `local:` entry and sees two folders that land on one name; a curated manifest is found from a differently-cased `owner/repo`; nested manifest tables reject unknown keys; a lockfile entry with an unrecognised `target` is refused; a locked asset is pinned so its hash stays checkable; an empty `KETCH_ROOT`/`KETCH_APPS_DIR` is treated as unset; client-app text is filtered on its way to the terminal.
