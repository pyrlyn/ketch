# Cookbook

Short, runnable recipes. Each was run against ketch 0.10.0 built from `main`, in a throwaway
root so your real install is untouched: `--root ./sandbox` points ketch at another tree, and
`--yes` answers its confirmation prompts. Every command and flag is listed in
[COMMANDS.md](COMMANDS.md).

## Try ketch without touching `~/.ketch`

```bash
ketch --root ./sandbox install sharkdp/fd@v10.2.0
./sandbox/bin/fd --version        # fd 10.2.0
rm -rf ./sandbox                  # everything ketch installed lived under it
```

ketch warns that `./sandbox/bin` is not on your `PATH`; for a real root, `ketch path install`
fixes that.

## Upgrade, then go back

```bash
ketch --root ./sandbox upgrade fd --yes     # fd v10.2.0 -> v10.5.0
ketch --root ./sandbox rollback fd --to v10.2.0
./sandbox/bin/fd --version                  # fd 10.2.0
```

Without `--to`, `rollback` restores the previous retained version. An upgrade keeps the
previous prefix on disk until `ketch prune`; `ketch prune --keep 2` keeps two previous versions
per package.

## Hold a version

```bash
ketch --root ./sandbox pin fd               # pinned fd v10.5.0
ketch --root ./sandbox upgrade --yes        # leaves fd alone
ketch --root ./sandbox unpin fd
```

## See what exists before installing

```bash
ketch releases sharkdp/fd                   # newest first; the installed one is marked *
ketch why BurntSushi/ripgrep                # how it would resolve: manifest, version, target
```

## Install a file from your own machine

```bash
printf '#!/bin/sh\necho hello\n' > hello && chmod +x hello
ketch --root ./sandbox install --path ./hello
ketch --root ./sandbox info hello           # source, prefix, channel, "verified first use"
```

A local file has no published checksum, so ketch warns and trusts it on first use.

## Reproduce a machine from a lockfile

```bash
ketch lock                                  # writes ./ketch.lock (commit it)
ketch lock --check                          # exit 0 when the tree matches the lockfile
ketch --root ./other sync --dry-run         # + fd v10.5.0: what sync would install
ketch --root ./other sync                   # installs every package at the locked tag
```

The lockfile records the tag, asset name and SHA-256 for the machine that wrote it; on another
platform `sync` picks the asset that fits and verifies it against the source
([LOCKFILE.md](LOCKFILE.md)).
