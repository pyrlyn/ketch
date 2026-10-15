# Development

Building, testing and releasing ketch. The contributor checklist is in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Building from source

```bash
cargo build --release                    # target/release/ketch
cargo test --workspace                   # unit tests and the end-to-end suite; no network
cargo clippy --workspace --all-targets   # must be clean
cargo fmt --all --check                  # must be clean
```

The source is a Cargo workspace: the `ketch` binary at the root holds the
command line, and [`crates/ketch-core`](../crates/ketch-core) holds everything it
does — resolving, fetching, verifying, unpacking and linking.

Run the binary against a throwaway tree instead of your real `~/.ketch`:

```bash
KETCH_ROOT=/tmp/ketch-scratch cargo run -- doctor
```

## Releasing

Nothing is typed. Actions → **Bump and release** (`bump.yml`) is the only way
to release: `scripts/release.sh` raises the version and writes the
`CHANGELOG.md` entry in one commit, bump opens a pull request with it, waits for
every required check, rebase-merges it, and only then tags the commit that
landed on `main`, creates the release and dispatches `release.yml`, which
[cargo-dist](https://github.com/axodotdev/cargo-dist) generates: it builds and
signs both macOS architectures (Linux and Windows unsigned), and only once
every target has built does it upload the tarballs and publish the release,
after which the `tap` job bumps `pyrlyn/homebrew-tap`'s cask. Red checks leave
no tag and no release.

CI (`ci.yml`) runs on pushes to `main`, on pull requests that are not drafts,
and on manual `workflow_dispatch`. Before merging a branch, dispatch the gate
on that ref and merge only when it is green:

```bash
gh workflow run ci.yml --ref <branch>
```

```bash
just release minor --dry-run    # the version a release would get; changes nothing
just release minor              # starts Bump and release (gh workflow run bump.yml)
```

ketch is not on crates.io — it ships as a tarball on a GitHub release, so
nothing publishes a crate.
