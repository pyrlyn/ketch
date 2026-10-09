#!/bin/sh
# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

# User-facing English docs have a Russian and a Ukrainian twin, and a
# translation does not exist for a page that was removed in English.
# Maintainer notes stay English: research, QA audits, sonarcloud setup.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOCS="$ROOT/docs"
fail=0

internal() {
    case "$1" in
        research*.md | sonarcloud-setup.md) return 0 ;;
        *) return 1 ;;
    esac
}

for f in "$DOCS"/*.md; do
    base=$(basename "$f")
    if internal "$base"; then
        continue
    fi
    for lang in ru uk; do
        if [ ! -f "$DOCS/$lang/$base" ]; then
            echo "docs-i18n: missing docs/$lang/$base" >&2
            fail=1
        fi
    done
done

for lang in ru uk; do
    for f in "$DOCS/$lang"/*.md; do
        base=$(basename "$f")
        if [ ! -f "$DOCS/$base" ]; then
            echo "docs-i18n: docs/$lang/$base has no English docs/$base" >&2
            fail=1
        fi
        if internal "$base"; then
            echo "docs-i18n: docs/$lang/$base translates a maintainer-only page" >&2
            fail=1
        fi
    done
done

exit "$fail"
