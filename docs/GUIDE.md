# Guide

How ketch stores, tracks and links what it installs. For every command with an example, see [COMMANDS.md](COMMANDS.md).

## The store and rollback

Everything lives under `~/.ketch`: versioned payloads in `store/`, links in
`bin/`, a `state.json` recording what is installed, and a `stats.db` recording
what happened. Nothing is written outside that tree except the `.app` bundles
that belong in `/Applications`, the user man and completion directories `ketch
doctor` reports, the shell startup file `ketch path install` edits when you
ask it to, `./ketch.lock` and `./ketch.toml` when you ask for them, and the
running binary itself when `ketch self upgrade` replaces it in place outside the
store.

An upgrade keeps the previous prefix on disk and records it under `retained`
in `state.json`, together with a `retention` policy (`keep`, default 1).
`ketch rollback <pkg>` relinks that prefix — it never re-downloads. `ketch prune`
is the only command that deletes retained prefixes; it leaves `keep` previous
versions. `ketch list` and `ketch info` show what is still retained. State files
written before this field existed still load: missing `retention` means keep 1,
and missing `retained` means none.

## Installing from disk

`ketch install --path PATH` (or `ketch install local:PATH`) installs something
already on this machine: a bare binary, a symlink to one, an archive, or an
`.app` bundle. The path is recorded in absolute form, the original is never
touched, and uninstalling removes only ketch's copy. A single file is linked
under the package name, which comes from the file name unless `--name` says
otherwise:

```bash
ketch install --path ./target/release/mytool --name mytool
```

A local package has no releases: it records the version `0.0.0-local`,
`outdated` and `upgrade` skip it, and installing the same path again with
`--force` picks up a rebuilt file. There is no published checksum to require,
so `require_checksums` does not apply to it. `ketch lock` records the hash of
what was there, and `sync` refuses the path if its contents have changed since.

`--name` works for any single package, not only a local one, and `upgrade` and
`sync` keep the name it chose.

## What you had, and when

```bash
ketch history              # every install, upgrade and removal, newest first
ketch history rg           # just this package's versions, including old ones
ketch stats                # how many installs, how long they take
```

`state.json` says what is installed now and is rewritten on every change, so it
cannot answer which version you had in March. `stats.db` is the other half: an
append-only record that outlives the packages themselves, which is why
`ketch history rg` still works after `ketch uninstall rg`. Delete the file and
you lose the history, never the packages.

## Seeing what changed

```bash
ketch changelog rg            # the entry for the version you have
ketch changelog rg --latest   # the release you would get by upgrading
ketch changelog rg --release  # the notes on the release, not the shipped file
```

Most releases carry their history twice: a `CHANGELOG.md` inside the archive
and the notes attached to the release. ketch prefers the file — it is already
on disk, so this works with no network — and falls back to the notes when the
file has no entry for that version, which is what happens whenever a project
tags before writing the heading.

The changelog goes to stdout and everything else to stderr, so
`ketch changelog rg > NOTES.md` leaves nothing but the markdown.

## Installing several at once

```bash
ketch install rg fd bat jq      # four downloads at a time, four progress bars
ketch install rg fd --jobs 1    # one at a time
```

Downloads run concurrently by default and spend their time waiting, so a batch
takes about as long as its slowest package rather than the sum of all of them.
Only the downloading and unpacking overlap: packages are placed into the store
one at a time, in whatever order each download finishes — not necessarily the
order you asked for them. Exit status and printed results still follow your
request order.

`upgrade` and `sync` work the same way. `--jobs N` sets the width for any of
them; `jobs` in the config file sets the default.

## The log

Every run is written to `~/.ketch/logs/ketch.log` — including the lines
`--quiet` swallowed and the debug detail `--verbose` would have shown. A failed
command prints where to find it.

```
2026-08-27T09:12:33Z [4218] INFO  ketch 0.1.0 · install rg fd
2026-08-27T09:12:34Z [4218] ERROR HTTP 404 from https://api.github.com/...
```

Set `log_format = "json"` for JSON Lines instead, `log_level` to `debug` for
everything or `off` for nothing. The file rotates to `ketch.log.1` at 5 MiB, so
it never needs pruning by hand. `ketch doctor` prints where it is and how big
it has grown.

## Reproducing a machine

```bash
ketch lock            # write ./ketch.lock from what is installed
ketch lock --check    # has anything drifted?
ketch sync            # make this machine match the lockfile
```

Commit `ketch.lock` next to your dotfiles and a new machine is one command
behind the old one. The tag is what reproduces everywhere; the recorded
checksum is enforced on a machine of the same target, and elsewhere ketch picks
the asset that fits and verifies it against the source. `--prune` removes what
the lockfile does not name, `--dry-run` shows the plan first. See
[docs/LOCKFILE.md](LOCKFILE.md).

## Getting on PATH

```bash
ketch path              # show PATH setup (same as `path status`)
ketch path status       # which shells are set up, and where
ketch path install      # edit the ones you use
ketch path uninstall    # take the block back out
```

On Unix it detects bash, zsh and fish — the shell `$SHELL` names, plus any whose
startup file you already keep — and writes one block between markers, so it can
rewrite it if the root moves and remove it cleanly later. On Windows it writes
the user PATH, which every new terminal reads. `--shell <name>` picks a Unix
shell, `--all` takes every target ketch knows, `--dry-run` shows the edit
without making it, and `--print` gives you the line to paste somewhere ketch
does not know about.

A line you added yourself is left alone rather than duplicated. `ketch doctor`
reports the PATH as a failure when no shell knows about it, as a warning when a
startup file has it but the current shell predates the edit, and `ketch doctor
--fix` does the setup for you.

## How a package is found

A name is resolved against four tiers, in order:

1. your own manifests in `~/.ketch/manifests/`
2. the fetched package registry — see [docs/REGISTRY.md](REGISTRY.md)
3. the registry compiled into the binary, so common tools work offline
4. inference from `owner/repo`, which is what makes an uncurated repository
   installable with no manifest at all

Inference picks the release asset that matches your machine — architecture,
OS, and libc — and explains its choice under `ketch info --assets`.

When it guesses wrong, a manifest says what to do instead: which asset, which
binaries, under what names. See [docs/MANIFESTS.md](MANIFESTS.md).

Sources other than GitHub are added as plugins — a single executable, no ketch
release required. See [docs/PLUGINS.md](PLUGINS.md).

## Platform support

macOS, Linux and Windows. Each OS has a `Platform` backend that picks a release
asset, unpacks it and places binaries; everything above that trait is shared.
`cargo test` runs the suite for the host OS; CI does that on macOS, Linux and
Windows. Install paths: `curl | bash` via `install.sh` on macOS and Linux,
`irm | iex` via `install.ps1` on Windows, or `brew install --cask pyrlyn/tap/ketch`
on macOS. `ketch path install` puts `~/.ketch/bin` on PATH (shell startup files
on Unix; the user PATH on Windows). Releases publish a tarball per target;
`install.sh` and `install.ps1` fetch the one for the machine they run on. The
Homebrew tap is [`pyrlyn/homebrew-tap`](https://github.com/pyrlyn/homebrew-tap);
the package registry is [`pyrlyn/ketch-registry`](https://github.com/pyrlyn/ketch-registry).
