package io.jclaw.app.http;

/**
 * A minimal browser UI, served by {@link JclawHttpServer} at {@code /}.
 *
 * <p>One HTML page with inline script, embedded as a constant rather than a resource so the
 * native image needs no resource configuration. It uses only the JSON and SSE routes the server
 * already exposes: it posts turns, follows a run's event stream, refreshes the transcript when
 * the run ends, and lists and resolves gates. There is no framework, no build step, and no
 * state beyond the thread name and the token, which stay in the browser's session storage.
 *
 * <p>Deliberately plain. It exists so a second person can use the agent from a browser, not as a
 * design statement; a richer client would sit on the same routes.
 */
final class WebUi {

    private WebUi() {
    }

    static final String INDEX = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>jclaw</title>
            <style>
              :root { color-scheme: light dark; }
              body { font: 14px/1.45 system-ui, sans-serif; margin: 0; display: grid; grid-template-rows: auto 1fr auto; height: 100vh; }
              header { display: flex; gap: .75rem; align-items: center; padding: .6rem 1rem; border-bottom: 1px solid #8884; }
              header input { font: inherit; padding: .3rem .5rem; }
              main { overflow: auto; padding: 1rem; display: grid; grid-template-columns: 1fr 18rem; gap: 1rem; }
              #log { display: flex; flex-direction: column; gap: .6rem; }
              .msg { max-width: 60rem; padding: .5rem .75rem; border-radius: .5rem; white-space: pre-wrap; border: 1px solid #8884; }
              .user { align-self: flex-end; background: #4a90e21a; }
              .assistant { align-self: flex-start; }
              .event { font-family: ui-monospace, monospace; font-size: 12px; opacity: .75; }
              aside h3 { margin: 0 0 .5rem; font-size: 13px; text-transform: uppercase; letter-spacing: .04em; opacity: .7; }
              .gate { border: 1px solid #8884; border-radius: .5rem; padding: .5rem; margin-bottom: .5rem; font-size: 13px; }
              .gate code { word-break: break-all; }
              footer { display: flex; gap: .5rem; padding: .6rem 1rem; border-top: 1px solid #8884; }
              footer textarea { flex: 1; font: inherit; padding: .4rem .6rem; resize: vertical; min-height: 2.6rem; }
              button { font: inherit; padding: .35rem .8rem; }
              #status { margin-left: auto; opacity: .7; font-size: 12px; }
            </style>
            </head>
            <body>
            <header>
              <strong>jclaw</strong>
              <label>thread <input id="thread" value="web" size="14"></label>
              <label>token <input id="token" type="password" size="18" placeholder="if serve-token is set"></label>
              <button id="reload">reload</button>
              <span id="status"></span>
            </header>
            <main>
              <section id="log"></section>
              <aside>
                <h3>Pending gates</h3>
                <div id="gates"><em>none</em></div>
                <h3 style="margin-top:1rem">Events</h3>
                <div id="events" class="event"></div>
              </aside>
            </main>
            <footer>
              <textarea id="text" placeholder="Ask the agent… (Ctrl+Enter to send)"></textarea>
              <button id="send">send</button>
            </footer>
            <script>
            const $ = (id) => document.getElementById(id);
            const token = () => $("token").value.trim();
            const thread = () => $("thread").value.trim() || "web";
            try { $("token").value = sessionStorage.getItem("jclaw-token") || ""; $("thread").value = sessionStorage.getItem("jclaw-thread") || "web"; } catch (e) {}
            const remember = () => { try { sessionStorage.setItem("jclaw-token", token()); sessionStorage.setItem("jclaw-thread", thread()); } catch (e) {} };
            const headers = () => token() ? { "Authorization": "Bearer " + token(), "Content-Type": "application/json" } : { "Content-Type": "application/json" };
            const status = (t) => { $("status").textContent = t; };

            async function api(path, options) {
              const r = await fetch(path, Object.assign({ headers: headers() }, options || {}));
              if (r.status === 401) { status("unauthorized: set the token"); throw new Error("401"); }
              return r.json();
            }

            async function loadMessages() {
              remember();
              const data = await api("/threads/" + encodeURIComponent(thread()) + "/messages");
              const log = $("log"); log.innerHTML = "";
              for (const m of data.messages) {
                const div = document.createElement("div");
                div.className = "msg " + m.role.toLowerCase();
                div.textContent = (m.attachments && m.attachments.length ? "[" + m.attachments.join(", ") + "] " : "") + m.text;
                log.appendChild(div);
              }
              log.lastElementChild && log.lastElementChild.scrollIntoView();
            }

            async function loadGates() {
              const data = await api("/approvals");
              const box = $("gates"); box.innerHTML = "";
              if (!data.gates.length) { box.innerHTML = "<em>none</em>"; return; }
              for (const g of data.gates) {
                const div = document.createElement("div"); div.className = "gate";
                div.innerHTML = "<div><b>" + g.kind.toLowerCase() + "</b> " + g.capability + "</div><code>" + g.prompt + "</code><div style='margin-top:.4rem'></div>";
                const row = div.lastElementChild;
                for (const [label, approved] of [["approve", true], ["deny", false]]) {
                  const b = document.createElement("button"); b.textContent = label;
                  b.onclick = async () => { await api("/approvals/" + g.id, { method: "POST", body: JSON.stringify({ approved }) }); status(label + "d " + g.id); loadGates(); };
                  row.appendChild(b);
                }
                box.appendChild(div);
              }
            }

            function follow(run) {
              const box = $("events"); box.innerHTML = "";
              const url = "/runs/" + run + "/events" + (token() ? "?access_token=" + encodeURIComponent(token()) : "");
              const es = new EventSource(url);
              es.onmessage = (e) => {};
              for (const type of ["turn.submitted", "run.claimed", "model.called", "model.failed", "capability.invoked", "injection.detected", "gate.raised", "gate.resolved", "checkpoint.written", "run.finished"]) {
                es.addEventListener(type, (e) => { const d = document.createElement("div"); d.textContent = type + " " + e.data; box.appendChild(d); if (type === "gate.raised") loadGates(); });
              }
              es.addEventListener("end", () => { es.close(); status("run " + run + " finished"); loadMessages(); loadGates(); });
              es.onerror = () => { es.close(); status("stream closed"); loadMessages(); };
            }

            async function send() {
              const text = $("text").value.trim();
              if (!text) return;
              $("text").value = "";
              status("queuing…");
              const r = await api("/threads/" + encodeURIComponent(thread()) + "/turns", { method: "POST", body: JSON.stringify({ text }) });
              status("run " + r.run + " queued");
              await loadMessages();
              follow(r.run);
            }

            $("send").onclick = send;
            $("text").addEventListener("keydown", (e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) send(); });
            $("reload").onclick = () => { loadMessages(); loadGates(); };
            $("thread").addEventListener("change", () => { loadMessages(); });
            loadMessages().catch(() => {}); loadGates().catch(() => {});
            </script>
            </body>
            </html>
            """;
}
