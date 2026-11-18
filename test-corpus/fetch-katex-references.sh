#!/usr/bin/env bash
# Downloads RaTeX's KaTeX reference renders for the formulas in ratex/, and RaTeX's own scores
# against them, into build/katex-references.
# The commit must match the one the corpus was copied from (see README.md).
set -euo pipefail

COMMIT=c98ae9d128a3c0a26176b3f3541f3411c4e7c3d6
root=$(cd "$(dirname "$0")/.." && pwd)
dest="$root/build/katex-references"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

git -C "$tmp" init -q
git -C "$tmp" remote add origin https://github.com/erweixin/RaTeX.git
git -C "$tmp" sparse-checkout set --no-cone '/tests/golden/fixtures*/' '/tests/golden/baseline.json'
git -C "$tmp" fetch -q --depth 1 --filter=blob:none origin "$COMMIT"
git -C "$tmp" checkout -q FETCH_HEAD

rm -rf "$dest"
mkdir -p "$dest"
cp -R "$tmp"/tests/golden/fixtures* "$dest"/
cp "$tmp"/tests/golden/baseline.json "$dest"/ratex-scores.json
echo "KaTeX references in $dest:"
for suite in "$dest"/fixtures*; do echo "  $(basename "$suite"): $(find "$suite" -name '*.png' | wc -l | tr -d ' ') images"; done
