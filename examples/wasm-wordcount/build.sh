#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
#
# Builds the module and lays out a package directory `jclaw extensions install` accepts.
# Output: dist/{jclaw-extension.json,module.wasm}
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

if ! command -v cargo >/dev/null; then
  echo "build.sh: cargo not found — install Rust from https://rustup.rs" >&2
  exit 1
fi

# The bare wasm target: no WASI, no host runtime, nothing but the module and the imports jclaw
# supplies. wasm32-wasip1 would also build, but it would import WASI functions jclaw does not
# implement, and a module that imports what it was not granted never instantiates.
rustup target add wasm32-unknown-unknown >/dev/null 2>&1 || true
cargo build --release --target wasm32-unknown-unknown

rm -rf dist && mkdir -p dist
cp jclaw-extension.json dist/
cp target/wasm32-unknown-unknown/release/jclaw_wasm_wordcount.wasm dist/module.wasm

echo "build.sh: dist/ ready ($(wc -c < dist/module.wasm) bytes)"
echo "  jclaw extensions install $here/dist"
