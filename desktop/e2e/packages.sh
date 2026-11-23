#!/bin/sh
# The workspace's packages/, found as settings.gradle.kts finds it: KOTLIN_KIT_DIR, or any ancestor of the checkout.
if [ -n "${KOTLIN_KIT_DIR:-}" ]; then
    dirname "$KOTLIN_KIT_DIR"
    exit
fi
dir=$PWD
until [ -d "$dir/packages/pyrlyn-kit" ]; do
    [ "$dir" != / ] || { echo "packages/pyrlyn-kit not found above $PWD; set KOTLIN_KIT_DIR" >&2; exit 1; }
    dir=$(dirname "$dir")
done
echo "$dir/packages"
