---
id: B56
title: "`--name` help narrower than the flag"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`--name` clap help said it applied only when installing with `--path`, but the flag accepts any single-package install and overrides the installed name.

Widened the doc comment on `InstallArgs::name` in `src/cli.rs` to say it sets the installed name for a single package, with the file-basename default still noted for `--path`.
