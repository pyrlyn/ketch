# Configuration

## Configuration

`~/.ketch/config.toml`, with environment variables taking precedence:

| Key | Environment | Default |
| --- | --- | --- |
| `apps_dir` | `KETCH_APPS_DIR` | `/Applications` |
| `github_token` | `KETCH_GITHUB_TOKEN`, `GITHUB_TOKEN`, `GH_TOKEN` | none |
| `prerelease` | `KETCH_PRERELEASE` | `false` |
| `allow_emulation` | `KETCH_ALLOW_EMULATION` | `true` |
| `link_apps` | `KETCH_LINK_APPS` | `false` |
| `require_checksums` | `KETCH_REQUIRE_CHECKSUMS` | `false` |
| `strip_quarantine` | `KETCH_STRIP_QUARANTINE` | `true` |
| `auto_update` | `KETCH_AUTO_UPDATE` | `true` |
| `emoji` | `KETCH_EMOJI` | `true` |
| `registry` | `KETCH_REGISTRY` | `pyrlyn/ketch-registry` |
| `self_repo` | `KETCH_SELF_REPO` | `pyrlyn/ketch` |
| `jobs` | `KETCH_JOBS` | `4` (capped at `16`) |
| `log_level` | `KETCH_LOG_LEVEL` | `info` |
| `log_format` | `KETCH_LOG_FORMAT` | `text` |
| `max_download_mib` | `KETCH_MAX_DOWNLOAD_MIB` | `4096` |

`self_repo` and `registry` set to the names these repositories had before they
moved, `listepo/ketch` and `listepo/ketch-registry`, read as the `pyrlyn` ones;
so do the old names in the state file and in lockfiles.

[docs/config.schema.json](https://github.com/pyrlyn/ketch/blob/main/docs/config.schema.json) is the file's JSON Schema,
generated from the types ketch reads it into, for editors and linters.

To start over, `ketch config reset` writes `config.toml` with those defaults
(after asking, unless `--yes`). The existing file is backed up beside itself
as `config.toml.bak-<unix-seconds>`, unless it is missing or already matches a
sibling backup:

```bash
ketch config reset          # ask, back up, write the defaults
ketch config reset --yes    # for scripts and CI
```

`auto_update` (default `true`) runs `ketch update` at the start of `install` and
`upgrade`. Set it to `false`, or `KETCH_AUTO_UPDATE=false`, to skip the registry
refresh.

`emoji` (default `true`) puts an icon in front of each status line on a
terminal: 📦 install, ⏫ upgrade, 🧹 uninstall, ⏬ download, 🔗 link, ⏪ rollback,
✅ success, ❗ warning, ❌ error, 💡 note. Set it to `false`, or
`KETCH_EMOJI=0`, or pass `--no-emoji`, to go without. Icons never reach a pipe,
`TERM=dumb`, `--json` output or the log.

The root itself is `KETCH_ROOT` or `--root`; it cannot be set from the config
file, because the file lives inside it. `KETCH_GITHUB_API` overrides the GitHub
API base URL (for Enterprise); it cannot be set from the config file either.
`jobs` and `--jobs` above `16` are clamped to `16`.

`max_download_mib` is the largest single download ketch writes, in MiB. A
download that announces more, or streams past it without saying, is stopped
and leaves no file behind; raise it for a package that is genuinely larger.

A token is not required, but it raises GitHub's rate limit considerably.
