#!/usr/bin/env bash
# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

# Builds ketch-core with none of its optional features, then with each one
# alone, then ketch-ffi on its own (which picks its own set). A workspace build
# unifies features, so every other build has all of them on and would never
# notice a `cfg(feature = ...)` that only compiles beside another feature.
# `just core-features` and CI run this; cargo-hack is not pinned, and the list
# is read from the manifest so a new feature is checked without editing here.

set -euo pipefail

cd "$(dirname "$0")/.."
manifest=crates/ketch-core/Cargo.toml

# Every key of the [features] table but `default`.
features=$(awk '
    /^\[/ { in_features = ($0 == "[features]"); next }
    in_features && /^[A-Za-z0-9_-]+[[:space:]]*=/ {
        name = $1
        sub(/=.*/, "", name)
        if (name != "default") print name
    }
' "$manifest")

if [ -z "$features" ]; then
    echo "no features found in $manifest" >&2
    exit 1
fi

lint() {
    echo "==> ketch-core $*" >&2
    cargo clippy --locked -p ketch-core --lib --tests --no-default-features "$@" -- -D warnings
}

lint
for feature in $features; do
    lint --features "$feature"
done

# The paths a feature leaves behind when it is off (a policy refused, an
# event not recorded) only exist in this build, so its unit tests run here.
# One thread: some tests set process environment (KETCH_LOG_FORMAT, ...),
# which nextest isolates per process and cargo test does not; nextest is not
# installed everywhere this runs.
echo "==> ketch-core tests, no features" >&2
cargo test --locked -p ketch-core --lib --no-default-features -q -- --test-threads=1

echo "==> ketch-ffi alone" >&2
cargo clippy --locked -p ketch-ffi --lib -- -D warnings
