#!/bin/sh
# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

# ci.yml must parse, and its triggers and the /review gate must stay load-bearing.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
wf="$ROOT/.github/workflows/ci.yml"

if [ ! -f "$wf" ]; then
    echo "ci-triggers: missing $wf" >&2
    exit 1
fi

# B136: brew style keys off a directory named Casks/, and that file must not
# land in the repo root, where it dirties the tree release.sh refuses.
if ! grep -q 'target/cask/Casks/ketch.rb' "$wf"; then
    echo "ci-triggers: cask lint must write target/cask/Casks/ketch.rb (B136)" >&2
    exit 1
fi
if grep 'cask/Casks' "$wf" | grep -v 'target/cask/Casks' | grep -q .; then
    echo "ci-triggers: cask lint must write under target/cask/Casks (B136)" >&2
    exit 1
fi
justfile="$ROOT/Justfile"
if ! grep -q 'target/cask/Casks/ketch.rb' "$justfile"; then
    echo "ci-triggers: Justfile lint-cask must write under target/ (B136)" >&2
    exit 1
fi
if grep 'cask/Casks' "$justfile" | grep -v 'target/cask/Casks' | grep -q .; then
    echo "ci-triggers: Justfile still writes cask/Casks at the repo root (B136)" >&2
    exit 1
fi

if command -v ruby >/dev/null 2>&1; then
    ruby -ryaml -e "YAML.load_file(ARGV[0])" "$wf"
else
    echo "ci-triggers: ruby is required to parse the workflow" >&2
    exit 1
fi

need() {
    if ! grep -q "$1" "$wf"; then
        echo "ci-triggers: missing $2" >&2
        exit 1
    fi
}

# Triggers: push to main, PRs to main (never drafts), manual dispatch,
# and issue comments for the /review gate.
need 'branches: \[main\]' 'the main branch trigger'
need 'pull_request:' 'the pull_request trigger'
need 'ready_for_review' 'the ready_for_review type (drafts run on ready)'
need 'issue_comment:' 'the issue_comment trigger for /review'
need 'workflow_dispatch:' 'the manual dispatch trigger'
# Draft PRs report green without running: review happens when marked ready.
need 'github.event.pull_request.draft != true' 'the draft skip'
# /review is members-only and dispatches CI on the PR branch.
need "startsWith(github.event.comment.body, '/review')" 'the /review command gate'
need 'OWNER", "MEMBER", "COLLABORATOR' 'the /review author gate'
need 'gh workflow run ci.yml --ref' 'the /review dispatch'