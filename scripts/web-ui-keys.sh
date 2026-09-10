#!/bin/bash
# Drives the real served browser UI in headless Chrome and checks every way a
# message can be sent or deliberately not sent.
#
# The page is one embedded constant with no build step and no framework, so a
# regression in its key handling is invisible to the rest of the project.
# WebUiTest pins the decisions in the source; this pins the behaviour, by
# dispatching actual KeyboardEvents at the page a running `jclaw serve` hands a
# browser. It is not part of `mvn test` because it needs a Chrome on the machine.
#
# Synthetic events test the handler, not the OS: what this proves is that each
# modifier combination reaches the right branch. Whether macOS delivers
# Ctrl+Return to the page at all is the question binding plain Enter removes.
#
#   ./scripts/web-ui-keys.sh            # exits non-zero if any check fails
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
[ -x "$chrome" ] || { echo "no Chrome at: $chrome (set CHROME=...)"; exit 77; }

jar="jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar"
[ -f "$jar" ] || { echo "build first: mvn -q install -DskipTests"; exit 1; }

work="$(mktemp -d)"
port="${PORT:-8099}"
token="uikeys"
serve_pid=""
cleanup() {
  [ -n "$serve_pid" ] && kill "$serve_pid" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT

java -jar "$jar" serve --port "$port" \
  --jclaw.provider=mock --jclaw.mock-script='text:pong' \
  --jclaw.serve-token="$token" --jclaw.state-dir="$work/state" \
  > "$work/serve.log" 2>&1 &
serve_pid=$!
for _ in $(seq 1 60); do
  curl -sf "http://localhost:$port/health?access_token=$token" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "http://localhost:$port/?access_token=$token" > "$work/page.html" \
  || { echo "serve did not come up:"; tail -20 "$work/serve.log"; exit 1; }

# The fakes are at least as strict as what they stand in for: a Response carries
# `ok` for 2xx only, which is exactly the field the page now checks.
cat > "$work/stub.html" <<'STUB'
<script>
window.__posts = [];
window.__failNext = false;
window.EventSource = function(){ this.close=function(){}; this.addEventListener=function(){}; };
window.fetch = function(path, opts){
  opts = opts || {};
  const reply = (status, body) => Promise.resolve({ status, ok: status >= 200 && status < 300, json: () => Promise.resolve(body) });
  if (opts.method === "POST" && /turns$/.test(path)) {
    window.__posts.push(JSON.parse(opts.body).text);
    return window.__failNext ? reply(409, { error: "THREAD_BUSY" }) : reply(200, { run: "r-1" });
  }
  if (/messages$/.test(path)) return reply(200, { messages: [] });
  if (/approvals$/.test(path)) return reply(200, { gates: [] });
  return reply(200, {});
};
</script>
STUB

cat > "$work/checks.html" <<'CHECKS'
<script>
const ta = document.getElementById("text");
const press = (init) => {
  const e = new KeyboardEvent("keydown", Object.assign({ key: "Enter", bubbles: true, cancelable: true }, init));
  ta.dispatchEvent(e);
  return e;
};
const settle = async () => { for (let i = 0; i < 50; i++) await Promise.resolve(); };
const check = (name, cond) => console.log("JCLAWUI " + (cond ? "PASS" : "FAIL") + " :: " + name);
(async () => {
 try {
  let e;
  ta.value = "one"; window.__posts = [];
  e = press({}); await settle();
  check("plain Enter sends the turn", window.__posts.length === 1 && window.__posts[0] === "one");
  check("plain Enter prevents the default, so no stray newline", e.defaultPrevented === true);
  check("the box is cleared after a successful send", ta.value === "");

  ta.value = "two"; window.__posts = [];
  e = press({ shiftKey: true }); await settle();
  check("Shift+Enter does not send", window.__posts.length === 0);
  check("Shift+Enter keeps its default newline", e.defaultPrevented === false);
  check("Shift+Enter leaves the text alone", ta.value === "two");

  ta.value = "three"; window.__posts = [];
  press({ ctrlKey: true }); await settle();
  check("Ctrl+Enter still sends (the chord this page used to advertise)", window.__posts.length === 1 && window.__posts[0] === "three");

  ta.value = "four"; window.__posts = [];
  press({ metaKey: true }); await settle();
  check("Cmd+Enter sends (the macOS chord)", window.__posts.length === 1 && window.__posts[0] === "four");

  ta.value = "five"; window.__posts = [];
  e = press({ isComposing: true }); await settle();
  check("Enter while an IME is composing does not send", window.__posts.length === 0);
  check("Enter while composing keeps its default", e.defaultPrevented === false);

  ta.value = "five"; window.__posts = [];
  press({ keyCode: 229 }); await settle();
  check("Enter with legacy IME keyCode 229 does not send", window.__posts.length === 0);

  ta.value = "   "; window.__posts = [];
  press({}); await settle();
  check("Enter on blank input does not send", window.__posts.length === 0);

  ta.value = "six"; window.__posts = [];
  press({}); press({}); await settle();
  check("two fast Enters queue exactly one turn", window.__posts.length === 1);

  window.__failNext = true; ta.value = "kept"; window.__posts = [];
  press({}); await settle();
  check("the typed text survives a refused turn", ta.value === "kept");
  check("a refused turn is reported in the status line", /not sent/.test(document.getElementById("status").textContent));
 } catch (err) {
  check("harness ran without throwing", false);
  console.log("JCLAWUI threw " + err);
 }
 console.log("JCLAWUI DONE");
})();
</script>
CHECKS

python3 - "$work" <<'PY'
import pathlib, sys
work = pathlib.Path(sys.argv[1])
page = (work / "page.html").read_text()
page = page.replace("<head>", "<head>" + (work / "stub.html").read_text(), 1)
page = page.replace("</body>", (work / "checks.html").read_text() + "</body>", 1)
(work / "harness.html").write_text(page)
PY

"$chrome" --headless=new --disable-gpu --no-sandbox --no-first-run \
  --no-default-browser-check --disable-extensions --enable-logging=stderr --v=0 \
  --virtual-time-budget=4000 --user-data-dir="$work/profile" \
  --dump-dom "file://$work/harness.html" >/dev/null 2>"$work/chrome.err" &
chrome_pid=$!
for _ in $(seq 1 45); do
  grep -q 'JCLAWUI DONE' "$work/chrome.err" 2>/dev/null && break
  kill -0 "$chrome_pid" 2>/dev/null || break
  sleep 1
done
kill "$chrome_pid" 2>/dev/null || true

grep -o 'JCLAWUI [A-Z]* :: [^"]*' "$work/chrome.err" | sed 's/^JCLAWUI //' || true
# grep -c prints a count and still exits 1 on no match, so `|| echo 0` would
# append a second number and make the comparison below a syntax error.
pass=$(grep -c 'JCLAWUI PASS' "$work/chrome.err" 2>/dev/null || true)
fail=$(grep -c 'JCLAWUI FAIL' "$work/chrome.err" 2>/dev/null || true)
echo "--- $pass passed, $fail failed"
[ "$fail" -eq 0 ] && [ "$pass" -gt 0 ]
