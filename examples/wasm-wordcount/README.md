# wordcount — a WebAssembly extension for jclaw

A complete, working example of jclaw's third extension kind. It takes a workspace-relative path,
reads that file **through the host**, and answers with JSON:

```json
{"path":"sample.txt","lines":3,"words":6,"bytes":35}
```

The module is about 2.5 KB and the whole of it is in [`src/lib.rs`](src/lib.rs). It is `no_std`
Rust, which is not austerity for its own sake: jclaw's calling convention hands the module a
pointer and a length and expects a pointer and a length back, so the module manages its own
memory either way, and without `std` there is nothing between the source and the ABI.

## Build and install

```bash
./build.sh                                   # needs Rust; writes dist/
jclaw extensions install ./dist
jclaw tools | grep wordcount
#   wasm.wordcount.count    NETWORK    COMMUNITY    needs approval
```

Then call it. The mock provider scripts the tool call, so no model or API key is involved:

```bash
printf 'hello world\nsecond line here\nthird\n' > sample.txt

jclaw run "count sample.txt" \
  '--jclaw.mock-script[0]=tool:wasm.wordcount.count:path=sample.txt' \
  '--jclaw.mock-script[1]=text:done'
# jclaw: run parked awaiting approval (gate gate_...)

jclaw approvals approve gate_...
```

The result the model receives is the host output and the JSON, in that order:

```
wordcount: read 35 bytes from sample.txt
{"path":"sample.txt","lines":3,"words":6,"bytes":35}
```

## Why it parks, even in `trusted` mode

The manifest claims `"effect": "read_local"`, and that claim is **not believed**. An unsigned
package installs as `COMMUNITY`, whose trust ceiling is `PURE`, so its tools are `NETWORK`
whatever the manifest says and every call gates. Sign the package and have the operator trust the
publisher, and the declared effect is honoured:

```bash
jclaw extensions keygen --out keys
jclaw extensions sign ./dist --key keys/publisher.key
jclaw extensions install ./dist --jclaw.trusted-publishers.me="$(cat keys/publisher.pub)"
```

Note also that approving one call does not approve the next. Approvals are keyed by the exact
invocation, so a grant for `path=sample.txt` does nothing for `path=../../etc/passwd` — that is a
second question, asked separately.

## The calling convention

Three exports, and that is the whole of it:

| Export | Signature | Purpose |
|---|---|---|
| `memory` | — | the module's linear memory, which the host reads and writes |
| `jclaw_alloc` | `(i32) -> i32` | give the host somewhere to put the arguments; must return a non-zero pointer |
| `jclaw_call` | `(i32, i32) -> i64` | the entry point: UTF-8 JSON in, and a result packing **pointer in the high 32 bits, length in the low 32** |

Host functions are imported from the module named `jclaw`, and each must be listed in the
manifest's `permissions`:

| Import | Signature | Granted by |
|---|---|---|
| `log` | `(ptr, len)` | `"log"` |
| `read_file` | `(path_ptr, path_len, out_ptr, out_cap) -> i32` | `"read_file"` — returns the length written, or `-1` |
| `http_get` | `(url_ptr, url_len, out_ptr, out_cap) -> i32` | `"http_get"` — same shape |

## What bounds this module

Four things, and only the last is a check anyone could forget:

- **Memory.** Capped in 64 KiB pages by `jclaw.wasm-max-memory-pages` (256 by default, so 16 MiB).
  The module keeps the initial size it declared — this one asks for 21 pages, which is Rust's
  default 1 MiB shadow stack plus its 256 KiB buffer — and a module declaring more than the cap
  is refused at load rather than started and trapped.
- **Instructions.** Every one is counted against `jclaw.wasm-max-instructions`. A loop costs a
  bounded amount of someone's afternoon, and the module is stopped with `wasm_out_of_fuel`.
- **Time and output.** `jclaw.wasm-timeout` and `jclaw.wasm-max-output-bytes` as backstops.
- **Reach.** The module can only call host functions the operator granted. This is the part with
  no check in it: an ungranted permission is not a function that refuses, it is a name that does
  not resolve, so the module fails to instantiate and never runs. Try it — drop `"read_file"`
  from the manifest's `permissions` and reinstall, and the tool disappears rather than failing
  when called.

Two layers still sit underneath, which is the point of running untrusted code this way. The
capability host decides whether the call happens at all, and `read_file` goes through the
workspace guard, so a path climbing out of the workspace comes back as `-1` **even when the call
was approved**:

```
{"error":"wordcount: the host would not read that path"}
```

A module is instantiated per call and thrown away, so no tool call can leave state for the next.

## Where to go next

The module deliberately hand-rolls a twenty-line JSON field reader instead of pulling in a crate,
so that the example is about the integration rather than about parsing. A real extension should
use `serde_json` — the cost is module size, which is instruction budget.

See the [Extensions](../../README.md#extensions-extensions) section of the main README for
packaging, signing, registries and profiles.
