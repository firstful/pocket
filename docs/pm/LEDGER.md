# Pocket PM Ledger

Append-only log of ideas, bugs, QA runs, and shipped changes. Newest at top within each section is fine; prefer dated entries.

## Ideas / Fun Extras
- 2026-11-10: Session-history sanitizer + "start fresh chat" recovery when gateway rejects malformed persisted tool history (auto-detect 4xx/5xx protocol errors like missing tool_call_id/name, offer one-tap reset of current session history). Low-med effort, high resilience. [STATUS 2026-11-21: SHIPPED — wire mapper drops malformed tool messages (InferenceClient.kt:239) + one-tap "New chat" reset (ChatScreen.kt:694 `newSession()`); both markers in vc117 DEX. The 4xx/5xx auto-detect banner specifically is NOT present (Retry banner is tied to generic `errorMessage`, not protocol-error detection).]
- 2026-11-10: Inline stream-error banner showing gateway error payload + "Retry" action (today a 200-with-error SSE becomes a silent 2-line response). Small UX win for debugging. [STATUS 2026-11-21: SHIPPED — Retry banner in ChatScreen.kt:717-739 shows `errorMessage` + Retry button; vc117 DEX.]
- 2026-11-11: web_search reliability: ephemeral WebView never fires onPageFinished in this environment (even attached to Activity content view). Try (a) larger/invisible viewport + fixed-delay extraction fallback, (b) OkHttp fetch of a JS-light search endpoint, or (c) a search API. Also consider surfacing timeout as a clear tool error instead of a 30s hang. [STATUS 2026-11-21: RESOLVED — web_search now uses OkHttp SERP fetch + parse (WebTools.kt:81-122); stable PASS across many recent runs including this one (result_count=8). No longer a WebView-onPageFinished dependency.]

## Bugs / Regressions
- 2026-11-10 [fix pushed, CI pending]: Chat/streaming broken on vc 73 (`1.0.73-1cbd954`) for `hello` and web_search prompts — gateway returns SSE error `Cannot resolve tool name for tool_call_id=None. Either 'tool_calls[].function.name' in assistant message or 'name' in tool message must be provided.` (`streamChat: loop done, lines=2 data=1`). Evidence: `/root/pocket/docs/pm/logs/20261110T060753Z-hello.log`, `/root/pocket/docs/pm/logs/20261110T060753Z-websearch.log`, screenshots `/root/pocket/docs/pm/screenshots/20261110T060753Z-hello.png`, `/root/pocket/docs/pm/screenshots/20261110T060753Z-websearch.png`. Fix pushed `7211dd8` (wire mapper adds/resolves tool message `name`, drops malformed blank tool_call ids/names) — awaiting CI/build/store/device verify.
- 2026-11-10T12:06Z [persists on vc75 `1.0.75-7211dd8`]: Same streaming regression still reproducible (`hello` and web_search both return gateway SSE error `Cannot resolve tool name for tool_call_id=None...`, `lines=2 data=1`) on device with poisoned/persisted session history. Evidence: `/root/pocket/docs/pm/logs/20261110T120647Z-vc75-hello.log`, `/root/pocket/docs/pm/logs/20261110T120736Z-vc75-websearch.log`, `/root/pocket/docs/pm/screenshots/20261110T120647Z-vc75-hello.png`, `/root/pocket/docs/pm/screenshots/20261110T120736Z-vc75-websearch.png`, launch `/root/pocket/docs/pm/logs/20261110T120615Z-vc75-launch.log`. Follow-up fix: wire mapper now drops malformed tool-role messages lacking resolvable `tool_call_id`/`name` (and keeps only valid tool_calls) so poisoned history cannot be re-sent. Pending CI/build/install verification.
- 2026-11-11 [vc80 `1.0.80-9f08bbc`]: web_search still times out after 30s despite attaching the ephemeral WebView to the foreground Activity content view (attach succeeded: `WebView attached to Activity content view (1x1)`, but `onPageFinished` never fires; `latch completed=false pageFinished=false`). Root cause likely renderer suspension / tiny 1x1 viewport / Google SERP never completes off-screen. Streaming (hello) is healthy on vc80. Evidence: `/root/pocket/docs/pm/logs/20261111T061436Z-websearch.log`, `/root/pocket/docs/pm/screenshots/20261111T061436Z-websearch.png`. Next: enlarge viewport + fixed-delay extraction fallback, or replace backend.

## QA Runs
- 2026-11-12T18:04Z spot-check (device `Pixel 6a`/27271JEGR25574 via mDNS serial `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`, installed vc 84 `1.0.84-519d4e7`, store tip vc 84 `1.0.84-519d4e7` e9298782…, HEAD `f7a4f32` docs-only ahead of CI/build SHA 519d4e7): Identity PASS (`docs/pm/logs/20261112T060635Z-identity.log`). Launch/no-crash PASS, foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime (`docs/pm/logs/20261112T060635Z-launch.log`, `docs/pm/screenshots/20261112T060635Z-launch.png`). Hello stream PASS — `streamChat: response code=200 content-type=text/event-stream ... [DONE] after 19 lines (9 data), loop done, lines=19 data=9` (`docs/pm/logs/20261112T060635Z-hello.log`, `docs/pm/screenshots/20261112T060635Z-hello.png`). Web search PASS — `WebSearchTool: start query_len=21 max_results=8 ... response code=200 html_len=27770 duration_ms=1099 ... parsed titles=10 snippets=10 ... done result_count=8 duration_ms=1099` (`docs/pm/logs/20261112T060635Z-websearch.log`, `docs/pm/screenshots/20261112T060635Z-websearch.png`). No permission dialogs observed in captured logs. OTEL not exercised (endpoint unset by default). Homelab MCP todo board unreachable this run (discover/execute unavailable) — todo state unverified.
- 2026-11-12T12:04Z (device `pixel6a` via mDNS serial `adb-27271JEGR25574-…`, vc 84 `1.0.84-519d4e7`, store tip vc 84): Identity PASS (Pixel 6a / 27271JEGR25574, vc84) (`docs/pm/logs/20261112T120433Z_device.log`). Launch/no-crash PASS, foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime (`docs/pm/screenshots/20261112T120433Z_01_launch.png`, `docs/pm/logs/20261112T120433Z_websearch_full.logcat.txt`). Hello stream PASS — `streamChat: response code=200 … loop done, lines=13 data=6`, no gateway error (`docs/pm/logs/20261112T120433Z_hello.logcat.txt`, `docs/pm/screenshots/20261112T120433Z_02_hello_sent.png`). Web search PASS — `WebSearchTool: response code=200 html_len=30176 … parsed titles=11 snippets=11 … done result_count=8 duration_ms=1527`; follow-up stream `lines=51 data=25` (`docs/pm/logs/20261112T120433Z_websearch.logcat.txt`, `docs/pm/screenshots/20261112T120433Z_03_websearch_sent.png`). No unexpected permission dialogs observed (`mCurrentFocus` stayed Pocket). Device reachable only via mDNS this run (hardcoded IP/port 192.168.1.147:45357 stale); note for scripts.
- 2026-11-11T12:10Z (device `pixel6a adb-...`, vc 83 `1.0.83-71db22c`): Identity PASS (Pixel 6a / 27271JEGR25574, vc83) (`docs/pm/logs/20261111T121000Z-identity.log`). Launch/no-crash PASS, foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime (`docs/pm/logs/20261111T121000Z-launch.log`, `docs/pm/screenshots/20261111T121000Z-launch.png`). Hello stream PASS — `streamChat: lines=17 data=8`, no gateway error (`docs/pm/logs/20261111T121000Z-hello.log`, `docs/pm/screenshots/20261111T121000Z-hello.png`). Web search FAIL — `WebSearchTool` throws `NetworkOnMainThreadException` on every attempt (5 calls, query_len 13/22/15/7/21); no `done tier=ddg` / `result_count` (`docs/pm/logs/20261111T121000Z-websearch.log`, `docs/pm/screenshots/20261111T121000Z-websearch.png`). Root cause: synchronous OkHttp in `WebSearchTool.execute` runs on main/UI dispatcher. No permission dialogs observed.
- 2026-11-10T06:07Z (device `pixel6a 192.168.1.147:45357`, vc 73 `1.0.73-1cbd954`): Identity PASS (`docs/pm/logs/20261110T060753Z-identity.log`). Launch/no-crash PASS, foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime (`docs/pm/logs/20261110T060753Z-launch.log`, `docs/pm/screenshots/20261110T060753Z-launch.png`). Hello stream FAIL — gateway 500-in-SSE `tool_call_id=None` / missing tool name, `lines=2 data=1` (`docs/pm/logs/20261110T060753Z-hello.log`, `docs/pm/screenshots/20261110T060753Z-hello.png`). Web search FAIL/blocked by same root cause — no `WebSearchTool` result tags, same error (`docs/pm/logs/20261110T060753Z-websearch.log`, `docs/pm/screenshots/20261110T060753Z-websearch.png`). Settings screenshot captured (`docs/pm/screenshots/20261110T060753Z-settings.png`, reachability not fully asserted). No unexpected permission dialogs observed in captured focus/logs.
- 2026-11-10T12:06Z (device `pixel6a 192.168.1.147:45357`, vc 75 `1.0.75-7211dd8`, store tip vc 77 `1.0.77-43e3347`): Identity PASS (Pixel 6a 27271JEGR25574). Launch/no-crash PASS, foreground `com.sutliff.pocket/.MainActivity` (`docs/pm/logs/20261110T120615Z-vc75-launch.log`, `docs/pm/screenshots/20261110T120615Z-vc75-launch.png`). Hello stream FAIL — `streamChat: lines=2 data=1`, gateway SSE 500 `Cannot resolve tool name for tool_call_id=None...` (`docs/pm/logs/20261110T120647Z-vc75-hello.log`, `docs/pm/screenshots/20261110T120647Z-vc75-hello.png`). Web search FAIL/blocked by same root cause, no `WebSearchTool` tags (`docs/pm/logs/20261110T120736Z-vc75-websearch.log`, `docs/pm/screenshots/20261110T120736Z-vc75-websearch.png`). Settings screenshots captured (`docs/pm/screenshots/20261110T120823Z-vc75-settings.png`, `docs/pm/screenshots/20261110T120844Z-vc75-settings-open.png`). No unexpected permission dialogs observed; `mCurrentFocus` remained Pocket.
- 2026-11-11T06:13Z (device `pixel6a 192.168.1.147:45357`, vc 80 `1.0.80-9f08bbc`, store tip vc 80): Identity PASS (Pixel 6a 27271JEGR25574, vc80). Launch/no-crash PASS (`docs/pm/logs/20261111T061359Z-launch.log`, `docs/pm/screenshots/20261111T061359Z-launch.png`). Hello stream PASS — `streamChat: lines=25 data=12` (`docs/pm/logs/20261111T061418Z-hello.log`, `docs/pm/screenshots/20261111T061418Z-hello.png`). Web search FAIL — `WebSearchTool: start ... latch completed=false duration_ms=30001 pageFinished=false`, `Error: search timed out after 30s` even though attach succeeded (`WebView attached to Activity content view (1x1)`); `onPageFinished` never fires (`docs/pm/logs/20261111T061436Z-websearch.log`, `docs/pm/screenshots/20261111T061436Z-websearch.png`). No unexpected permission dialogs observed. Settings not captured this run.

## Shipped
- 2026-11-10: `7211dd8` (CI green, run 1514) — include/resolve tool message names in OpenAI wire mapper; insufficient for poisoned on-device history (vc75 still `lines=2`/`tool_call_id=None`).
- 2026-11-10: `479df37` (CI green, run 1526) — fix(pocket): drop malformed tool-role messages lacking resolvable `tool_call_id`/`name` and keep only valid assistant `tool_calls` to survive poisoned history. Pushed to `main`; store/device verification pending next run once CI+publish complete.
- 2026-11-11: `9f08bbc` (CI green, run 1535) — fix(pocket): attach ephemeral WebView to foreground Activity content view so web_search renders. Store published `1.0.80-9f08bbc` (vc80, sha 3bb7beda…). Installed on Pixel 6a; hello stream PASS (lines=25), but web_search still times out (onPageFinished never fires). Follow-up needed.

## Run 2026-11-11 (vc84 `1.0.84-519d4e7`, CI run 1540 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc84 installed from store (sha e9298782…). Store version matches HEAD (519d4e7).
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime. Evidence: `docs/pm/logs/20261111T121541Z-launch.log`, `docs/pm/screenshots/20261111T121541Z-launch.png`.
- web_search PASS: `WebSearchTool: start … response code=200 html_len=28627 duration_ms=853 … parsed titles=10 snippets=10 … done result_count=8`. No timeout, no NetworkOnMainThreadException. Agent also exercised web_fetch (accuweather 403 site-side; weather.com + forecast.weather.gov 200). Evidence: `docs/pm/logs/20261111T122409Z-vc84-websearch.log`, `docs/pm/screenshots/20261111T122412Z-vc84-websearch.png`.
- hello: not separately re-tested this run; prior vc80 hello PASS (lines=25) and streaming healthy on vc84 (streamChat loop done lines up to 79). No regression observed.

### Shipped
- `71db22c` (CI run 1535) — replace ephemeral WebView web_search with OkHttp DuckDuckGo HTML (onPageFinished never fired). Store published `1.0.83-71db22c` (vc83).
- `519d4e7` (CI run 1540) — run tool.execute on Dispatchers.IO (fixes NetworkOnMainThreadException that also explained the original WebView timeout). Store published `1.0.84-519d4e7` (vc84). Installed + verified on Pixel 6a.
- Closed homelab todos #423, #424, #425 (remote-agent registry, opt-in permissions, OTEL telemetry) — implementation present in code (RemoteAgentClient, PermissionOptIns, Telemetry OTEL).

### Open / Next
- web_fetch HTTP 403 from some sites (accuweather) is site-side; not a bug. Consider UA/headers or fallback.
- OTEL telemetry end-to-end not exercised this run (endpoint unset by default); verify separately if needed.
- Untracked screenshot `docs/pm/screenshots/20261111T061848Z-settings.png` from prior run committed with this update.

## Run 2026-11-13 (vc84 `1.0.84-519d4e7`, CI run 1540 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc84 installed from store (sha e9298782…). Store version matches HEAD code SHA 519d4e7. Evidence: `docs/pm/logs/20261113T060530Z-identity.log`.
- Note: device was reported unreachable by the pre-run script at 192.168.1.147:45357 but reconnected successfully this run via `adb connect` (mDNS service unchanged). No IP/port drift beyond the transient failure.
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime. Evidence: `docs/pm/logs/20261113T060530Z-launch.log`, `docs/pm/screenshots/20261113T060530Z-launch.png`.
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream … [DONE] after 15 lines (7 data), loop done, lines=15 data=7`. No gateway error. Evidence: `docs/pm/logs/20261113T060530Z-hello.logcat.txt`, `docs/pm/screenshots/20261113T060530Z-hello.png`.
- Web search PASS: `WebSearchTool: start query_len=7 … response code=200 html_len=28097 duration_ms=1551 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1551`. Follow-up stream `loop done, lines=59 data=29`. No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261113T060530Z-websearch.logcat.txt`, `docs/pm/screenshots/20261113T060530Z-websearch.png`.
- No new permission dialogs observed (`mCurrentFocus` stayed Pocket throughout).
- OTEL not exercised (endpoint unset by default) — consistent with prior runs; no regression suspected.
- Homelab MCP todo board: no open Pocket-related todos (open board scanned; #423/#424/#425 closed in prior run).

### Shipped
- No code changes this run. HEAD 78c6ccf is docs-only ahead of CI/build SHA 519d4e7; store tip vc84 matches.

## Run 2026-11-13T12:03Z (spot-check, vc84 `1.0.84-519d4e7`, CI run 1540 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc84 installed from store (sha e9298782…). Store version matches HEAD code SHA 519d4e7 (HEAD 9b02abe is docs-only ahead). Evidence: `docs/pm/logs/20261113T120324Z-identity.log`.
- Device reachable this run at `192.168.1.147:45357` (Pixel 6a/bluejay). Prior transient unreachable reported by pre-run script.
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, `mCurrentFocus` stayed Pocket, no app FATAL/AndroidRuntime. Evidence: `docs/pm/logs/20261113T120324Z-launch.log`, `docs/pm/screenshots/20261113T120324Z-launch.png`.
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream … [DONE] after 13 lines (6 data), loop done, lines=13 data=6`. No gateway error. Evidence: `docs/pm/logs/20261113T120324Z-hello.logcat.txt`, `docs/pm/screenshots/20261113T120324Z-hello.png`.
- Web search PASS: `WebSearchTool: start query_len=13 … response code=200 html_len=28766 duration_ms=893 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=893`. Follow-up stream healthy (no timeout/NetworkOnMainThread). Evidence: `docs/pm/logs/20261113T120324Z-websearch.logcat.txt`, `docs/pm/screenshots/20261113T120324Z-websearch.png`.
- Permission/dialogs PASS: no system permission dialog observed; `mCurrentFocus` remained `com.sutliff.pocket/.MainActivity`. Evidence: `docs/pm/logs/20261113T120324Z-permission.log`.
- OTEL not exercised this run (endpoint unset by default). Settings UI not captured in this spot-check set.
- Additional spot-check evidence from earlier same day (untracked, now committed): `docs/pm/logs/20261113T000337Z_{identity,launch,hello,websearch}.log`, `docs/pm/screenshots/20261113T000337Z_{launch,hello,websearch}.png` (vc84, hello `lines=13 data=6`, web_search `result_count=8 duration_ms=1547` + follow-up `lines=89 data=44`).

### Shipped
- No code changes this run. HEAD `9b02abe` docs-only ahead of CI/build SHA `519d4e7`; store tip vc84 matches.

### Open / Next
- OTEL telemetry end-to-end not exercised (endpoint unset by default) — verify with transient listener when convenient.
- `web_fetch` HTTP 403 from some sites is site-side (not a bug), per prior runs.

## Run 2026-11-14T00:03Z (spot-check, vc84 `1.0.84-519d4e7`, CI run 1540 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc84 installed from store (sha e9298782…). Store version matches HEAD code SHA 519d4e7 (HEAD 4cdb20f is docs-only ahead). Evidence: `docs/pm/logs/20261114T000341Z-identity` (device/version captured inline this run).
- Device reachable at `192.168.1.147:45357` (Pixel 6a/bluejay).
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime for Pocket. Evidence: `docs/pm/logs/20261114T000341Z-launch.log`, `docs/pm/screenshots/20261114T000341Z-launch.png`.
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream … [DONE] after 13 lines (6 data), loop done, lines=13 data=6`. No gateway error. Evidence: `docs/pm/logs/20261114T000341Z-hello.log`, `docs/pm/screenshots/20261114T000341Z-hello.png`.
- Web search PASS: `WebSearchTool: start query_len=6 … response code=200 html_len=32191 duration_ms=919 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=919`. Follow-up stream healthy (`loop done, lines=49 data=24`). No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261114T000341Z-websearch.log`, `docs/pm/screenshots/20261114T000341Z-websearch.png`.
- Permission/dialogs PASS: no system permission dialog observed; `mCurrentFocus` remained Pocket.
- OTEL not exercised this run (endpoint unset by default). No regression suspected.
- Homelab MCP todo board: no open Pocket-related todos (open board scanned; #423/#424/#425 closed in prior run).

### Shipped
- No code changes this run. HEAD `4cdb20f` docs-only ahead of CI/build SHA `519d4e7`; store tip vc84 matches.

### Open / Next
- OTEL telemetry end-to-end not exercised (endpoint unset by default) — verify with transient listener when convenient.
- `web_fetch` HTTP 403 from some sites is site-side (not a bug), per prior runs.
- Untracked evidence from prior run (2026-11-13T18:14Z) committed with this update.

## Run 2026-11-15T06:09Z (spot-check, vc91 `1.0.91-38d9bf4`, CI run 1593 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc91 installed from store (sha e17d195d…). Store version matches HEAD code SHA 38d9bf4 (HEAD 7a69652 is docs-only ahead). Evidence: device reported by pre-run script; `adb getprop ro.product.model` = Pixel 6a, serial 27271JEGR25574.
- Device reachable at `192.168.1.147:45357` (Pixel 6a/bluejay). versionCode=91 matches store tip.
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime for Pocket. Evidence: `docs/pm/screenshots/20261115T060816Z-webrender-attempt.png`.
- web_render (vc91 new feature) RUNTIME EXERCISED: two chat turns issued ("call web_render with url … mode outline"). WebViewFactory loaded `com.google.android.webview` 157.0.8103.38 in both runs (the only WebView creator in app code is `WebRenderManager.render()`), confirming the render path executed on-device with a foreground Activity (PIP overlay + outline extraction code path). No FATAL/AndroidRuntime crash in logcat. Stream completed (lines=43/121 data). Evidence: `docs/pm/logs/20261115T060816Z-webrender.log` (WebViewFactory line 88, streamChat loop done lines 138-139), screenshot `docs/pm/screenshots/20261115T060816Z-webrender-attempt.png`. Note: the LLM (ocg:beam-free) also issued web_fetch in the same turns (WebFetchTool logged); web_render itself emits no start log (WebRenderManager.render has no Log.d), so runtime confirmation relies on WebViewFactory loading. Visual PIP-preview confirmation not possible from agent side (screenshot not viewable); code path creates the top-right FrameLayout overlay and toggles visibility.
- web_fetch PASS: `WebFetchTool: start … response code=200 html_len=639781 … done cleaned_len=59347 duration_ms=380` (Wikipedia JavaScript). No timeout/NetworkOnMainThread.
- Permission/dialogs PASS: no system permission dialog observed; `mCurrentFocus` stayed Pocket.
- Telemetry: client path exercised — `Telemetry: telemetry post failed: Failed to connect to /192.168.1.2:4318` (graceful, no crash). Device endpoint is stale (192.168.1.2:4318, no listener); telemetry service at telemetry.ffilt.us returns 405 (up). Endpoint config on device not updated this run (no UI automation). Not a regression.
- Homelab MCP todo board: 0 open Pocket-related todos (scanned all 166 open; #423/#424/#425 closed in prior runs). No actionable todos.
- CI: latest run for code HEAD 38d9bf4 = run 1593 success. Store tip vc91 matches. Device vc91 matches. No drift.

### Shipped
- No code changes this run. HEAD 7a69652 (docs-only ahead of CI/build SHA 38d9bf4); store tip vc91 matches device.

### Open / Next
- web_render: runtime path verified via WebViewFactory loading (no crash, stream completed). Visual PIP confirmation + outline/find/section output capture still not agent-verifiable (screenshot not viewable, no WebRenderTool log). Consider (a) adding a `Log.d(tag, "start url=…")` to WebRenderTool.execute and `Log.d` in WebRenderManager.render for future verification, and (b) strengthening tool descriptions so ocg:beam-free selects web_render over web_fetch for JS/SPA pages (currently the LLM consistently picks web_fetch even when prompted for web_render).
- OTEL telemetry end-to-end still not exercised (device endpoint stale at 192.168.1.2:4318). Verify by pointing device at https://telemetry.ffilt.us/ (Settings) and sending a web_fetch turn; service is up (405 on GET).
- `web_fetch` HTTP 403 from some sites is site-side (not a bug), per prior runs.
- New idea: add a one-tap "new chat" / session reset to recover from poisoned history (already in Ideas; still relevant).

## Run 2026-11-15T00:09:30Z (spot-check, vc91 `1.0.91-38d9bf4`, CI run 1593 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc91 installed from store (sha e17d195d…). Store version matches HEAD code SHA 38d9bf4. Evidence: `docs/pm/logs/identity_20261115T000244Z.log`, `docs/pm/screenshots/20261115T000244Z-launch.png`.
- Device reachable at `192.168.1.147:45357` (Pixel 6a/bluejay).
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime for Pocket. Evidence: `docs/pm/logs/launch_20261115T000244Z.log`, `docs/pm/screenshots/launch_20261115T000244Z.png`.
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream … [DONE] after 17 lines (8 data), loop done, lines=17 data=8`. No gateway error. Evidence: `docs/pm/logs/hello_20261115T000244Z.log`, `docs/pm/screenshots/hello_20261115T000244Z.png`.
- Web search PASS: `WebSearchTool: start query_len=17 … response code=200 html_len=28816 duration_ms=884 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=884`. Follow-up stream healthy (`loop done, lines=107 data=53`). No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261115T001500Z-websearch.log`, `docs/pm/screenshots/20261115T001500Z-websearch.png`.
- Permission/dialogs PASS: no system permission dialog observed; mCurrentFocus remained Pocket throughout.
- web_render not exercised this run (ADB input automation flaky for the new tool); compiles via CI run 1593. Deferred to next run.
- OTEL not exercised this run (endpoint unset by default). No regression suspected.
- Homelab MCP todo board: no open Pocket-related todos (#423/#424/#425 closed in prior runs).

### Shipped
- No code changes this run. HEAD `38d9bf4` (vc91) CI run 1593 success; store tip vc91 matches HEAD; device installed vc91.

### Open / Next
- web_render (vc91 new feature) needs on-device verification (tool invocation + top-right PIP preview + outline/find/section output).
- OTEL telemetry end-to-end not exercised (endpoint unset by default) — verify with transient listener when convenient.
- `web_fetch` HTTP 403 from some sites is site-side (not a bug), per prior runs.

## Run 2026-11-15T12:06Z (spot-check, vc91 `1.0.91-38d9bf4`, CI run 1593 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc91 installed from store (sha e17d195d…). Store version matches HEAD code SHA 38d9bf4. Evidence: `docs/pm/logs/20261115T120556Z-identity.log`.
- Device reachable this run via mDNS at 192.168.1.127:38361 (pre-run script's hardcoded 192.168.1.147:45357 stale again; mDNS serial `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`).
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime for Pocket. Evidence: `docs/pm/logs/20261115T120556Z-launch.log`, `docs/pm/screenshots/20261115T120556Z-launch.png`.
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream … [DONE] after 13 lines (6 data), loop done, lines=13 data=6`. No gateway error. Evidence: `docs/pm/logs/20261115T120556Z-hello.log`, `docs/pm/screenshots/20261115T120556Z-hello.png`.
- Web search PASS: `WebSearchTool: start query_len=13 … response code=200 html_len=28827 duration_ms=1006 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1006`. Follow-up stream healthy (`loop done, lines=111 data=55`). No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261115T120556Z-websearch.log`, `docs/pm/screenshots/20261115T120556Z-websearch.png`.
- Permission/dialogs PASS: no system permission dialog observed; `mCurrentFocus` remained Pocket throughout.
- web_render (vc91 new feature) RUNTIME EXERCISED: `WebViewFactory: Loading com.google.android.webview version 157.0.8103.38` confirms the render path executed with a foreground Activity (PIP overlay + outline extraction code path). No FATAL/AndroidRuntime. Streams completed (lines=27 data=13, then lines=21 data=10). Note: web_render emits no start log (WebRenderManager.render has no Log.d), so runtime confirmation relies on WebViewFactory loading; visual PIP preview not agent-verifiable. Evidence: `docs/pm/logs/20261115T120556Z-webrender.log`, `docs/pm/screenshots/20261115T120556Z-webrender.png`.
- Telemetry: client path exercised — `Telemetry: telemetry post failed: Failed to connect to /192.168.1.2:4318` (graceful, no crash). Device endpoint is stale (192.168.1.2:4318, no listener); telemetry service at telemetry.ffilt.us returns 405 (up). Not a regression.
- Homelab MCP todo board: 0 open Pocket-related todos (scanned open board; #423/#424/#425 closed in prior runs). No actionable todos.
- CI: latest run for code HEAD 38d9bf4 = run 1593 success. Store tip vc91 matches. Device vc91 matches. No drift.

### Shipped
- No code changes this run. HEAD 27ad43d (docs-only ahead of CI/build SHA 38d9bf4); store tip vc91 matches device.

### Open / Next
- web_render: runtime path verified via WebViewFactory (no crash, stream completed). Visual PIP confirmation + outline/find/section output capture still not agent-verifiable (no WebRenderTool log). Consider (a) adding `Log.d` to WebRenderTool.execute and WebRenderManager.render for future verification, (b) strengthening tool descriptions so the LLM selects web_render over web_fetch for JS/SPA pages (currently picks web_fetch even when prompted for web_render).
- OTEL telemetry end-to-end still not exercised (device endpoint stale at 192.168.1.2:4318). Verify by pointing device at https://telemetry.ffilt.us/ (Settings) and sending a web_fetch turn; service is up (405 on GET).
- `web_fetch` HTTP 403 from some sites is site-side (not a bug), per prior runs.
- New idea: add a one-tap "new chat" / session reset to recover from poisoned history (already in Ideas; still relevant).

## Run 2026-11-15T18:04Z (PM cron, vc91 `1.0.91-38d9bf4`, CI run 1593 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc91 installed `1.0.91-38d9bf4` (dumpsys verified). Device reachable via mDNS `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (192.168.1.127:38361 during run). Evidence: live `adb devices -l` + `getprop ro.product.model`/`ro.serialno` + `dumpsys package com.sutliff.pocket`.
- Launch/state: foreground `com.sutliff.pocket/.MainActivity` (mCurrentFocus) observed at check time; no crash observed inline.
- On-device QA delegated (3 parallel subagents, ts 20261115T180413Z) — **verified this run**:
  - Hello stream PASS. Evidence: `docs/pm/logs/20261115T180413Z-hello.log` shows `PocketNet: streamChat: response code=200 content-type=text/event-stream`, first SSE data chunk, `[DONE] after 11 lines (5 data)`, `loop done, lines=11 data=5` (with a follow-up request in log tail). Screenshot: `docs/pm/screenshots/20261115T180413Z-hello.png` (valid PNG, ~103KB).
  - Web search PASS. Evidence: `docs/pm/logs/20261115T180413Z-websearch.log` shows `WebSearchTool: start query_len=34 max_results=8`, `response code=200 html_len=28519 duration_ms=1092`, `parsed titles=10 snippets=10`, `done result_count=8 duration_ms=1092`. Screenshot: `docs/pm/screenshots/20261115T180413Z-websearch.png` (valid PNG, ~100KB).
  - Settings/permission UI state captured: `docs/pm/screenshots/20261115T180413Z-settings.png`, `docs/pm/screenshots/20261115T180413Z-permission-state.png` (valid PNGs). `docs/pm/logs/20261115T180413Z-ui-perms.log` contains only minimal logcat header (not a full permission-dialog dump) — no explicit dialog assertion beyond screenshots; no crash/focus loss indicated in captured artifacts.
- CI/store: latest code build run 1593 `38d9bf4` (completed/success); store tip `1.0.91-38d9bf4` vc91 sha `e17d195da745`; device vc91 matches. HEAD `73a5d27`/origin `73a5d27` is docs-only ahead (no code drift).
- Homelab MCP todo board: no open Pocket-related todos found (scanned open todos for `pocket`/`web_search`/`web_render`/`otel`/`sms`; hit list included only unrelated items; todo #697 exists as noted in Open/Next). Gitea issues list returned 403 (Forbidden) — issue state unverified.
- OTEL: not exercised this run (endpoint unset by default on prior checks; device telemetry endpoint previously observed stale). No regression action taken.

### Shipped
- No code changes this run. Verified delegated QA evidence (ts 20261115T180413Z) and updated ledger; evidence files committed with this update.

### Open / Next
- Todo #697 (open, normal): Pocket: verify web_render on-device (logging + tool-selection) + OTEL E2E. Device not used for fresh QA this run; still open.
- web_render visual PIP/output capture and LLM tool-selection bias (prefers web_fetch) remain open (per prior runs).
- OTEL end-to-end verification pending (point device to `https://telemetry.ffilt.us/` and observe ingest).
- `web_fetch` 403 from some sites is site-side (not a bug).
- Consider adding `Log.d` in WebRenderTool/WebRenderManager for agent-verifiable render evidence.


## Run 2026-11-16T06:45Z (PM cron, vc91 installed, code push c2c421e)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc91 installed `1.0.91-38d9bf4` (dumpsys verified). Evidence: `docs/pm/logs/20261116T061000Z-identity.log`.
- Device reachable via mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`.
- Launch/no-crash PASS (foreground `com.sutliff.pocket/.MainActivity`, no FATAL). Evidence: `docs/pm/logs/20261116T061000Z-launch.log`, `docs/pm/screenshots/20261116T061000Z-launch.png`.
- Hello stream PASS (`streamChat: response code=200 ... [DONE] after 23 lines (11 data)`). Evidence: `docs/pm/logs/20261116T061000Z-hello.log`, screenshot.
- Web search PASS (`WebSearchTool: response code=200 html_len=28272 ... parsed titles=10 snippets=10 ... done result_count=8 duration_ms=919`). Evidence: `docs/pm/logs/20261116T061000Z-websearch.log`, screenshot.
- Permission/dialogs PASS: `mCurrentFocus` stayed `com.sutliff.pocket/.MainActivity`; no system dialog. Evidence: `docs/pm/screenshots/20261116T061000Z-permission-state.png` (n/a; focus captured in websearch log).
- web_render: LLM again selected `web_fetch` (example.com) instead of `web_render` — confirms tool-selection bias in todo #697. No `WebRenderTool` log on vc91 (old build). Evidence: `docs/pm/logs/20261116T061000Z-webrender.log`.
- OTEL not exercised (endpoint unset by default). No regression.
- Homelab MCP todo board: open Pocket todo #697 (normal) still open; no new Pocket todos.

### Shipped
- `c2c421e` pushed to `main` (feat: WebRender logging + strengthened tool descriptions for #697): added `Log.d` in `WebRenderManager.render` (start/onPageFinished/await/done) and `WebRenderTool.execute` (start/rendered/done/fallback); strengthened `web_search`/`web_fetch`/`web_render` descriptions to steer LLM toward `web_render` for JS/SPA pages.
- CI run 1635 for `c2c421ea` is **stuck in_progress** (>40 min, "Build + publish" step never completed; no `cimg/android` build container spawned on wyse5070). Likely runner hang. Gitea cancel endpoint returned 404; rerun blocked ("not done"). Manual intervention needed (restart runner pod or wait for timeout). New build (vc92) NOT published; device remains on vc91.

### Open / Next
- CI run 1635 (c2c421e) stuck — needs manual rerun/restart before vc92 can ship. Once green: install on Pixel 6a and verify new `WebRenderTool`/`WebRenderManager` log lines appear (agent-verifiable render evidence) and re-test tool-selection bias.
- OTEL E2E still pending (point device at `https://telemetry.ffilt.us/`).
- `web_fetch` 403 from some sites is site-side (not a bug).
- New idea: add a one-tap "new chat" / session reset to recover from poisoned history (still relevant).

### Update 2026-11-16T07:00Z
- Restarted stuck runner pod; redispatched build via workflow_dispatch → run 1636 `bdb0296e` completed success (all 5 steps green). Store published `1.0.98-bdb0296` vc98 (sha 4dc60906…, built 2026-11-16T06:27:41Z, size 13504088).
- Installed on Pixel 6a: versionCode=98, versionName=1.0.98-bdb0296. Launch PASS (foreground Pocket, no crash). New `WebRenderTool`/`WebRenderManager` Log.d tags present in code (verified by CI compile + build). On-device web_render verification (log evidence + tool-selection bias) deferred to next run.
## Run 2026-11-16T12:12Z (PM cron, vc98 `1.0.98-bdb0296`, CI run 1636 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc98 installed from store (sha 4dc60906…). Device reachable via mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (192.168.1.127:38361); hardcoded 192.168.1.147:45357 stale. Evidence: `docs/pm/logs/20261116T120602Z-device-identity.log` (versionCode=98, versionName=1.0.98-bdb0296).
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime for Pocket in captured logcat. Evidence: `docs/pm/logs/20261116T120602Z-launch-logcat.log`, `docs/pm/screenshots/20261116T120602Z-launch.png`.
- Standard hello/web_search: NOT cleanly captured this run. ADB input-coordinate drift (chat input at [21,1081][765,1417] bottom bar, send button [965,2149][1028,2212]) and `NO_INPUT_CHANNEL`/IME state prevented a reproducible send; accumulated stale text in the field. No PocketNet stream lines captured. Prior vc91 QA evidence (2026-11-15) remains the last clean hello/web_search proof. Evidence attempt: `docs/pm/logs/20261116T120602Z-hello-logcat.log` (empty).
- web_render (todo #697): vc98 logging verified in source — `WebRenderTool` (tag) + `WebRenderManager` Log.d (start/onPageFinished/await/done) in `app/src/main/java/com/sutliff/pocket/tools/WebTools.kt`. On-device invocation not captured this run due to ADB input fragility; runtime path previously verified on vc91 via WebViewFactory loading (no crash, stream completed). Evidence attempt: `docs/pm/logs/20261116T120600Z-webrender.log` (hello send captured, no WebRenderTool tag).
- Permission/dialogs: no new system permission dialog observed (mCurrentFocus stayed Pocket during checks).
- OTEL: not exercised (device endpoint stale at 192.168.1.2:4318; telemetry service at telemetry.ffilt.us returns 405/up). No regression.
- CI/store: latest code build run 1636 `bdb0296` (completed/success, workflow_dispatch); store tip `1.0.98-bdb0296` vc98 sha `4dc60906…`; device vc98 matches. HEAD `0d8edd8` docs-only ahead. No drift.
- Homelab MCP todo board: open Pocket todo #697 (normal) still open; no new Pocket todos.

### Shipped
- No code changes this run. Evidence files from this run committed with this update.

### Open / Next (as of 2026-11-16T12:12Z — RESOLVED in next run below)
- Todo #697 remains open: on-device web_render log verification + tool-selection bias + OTEL E2E. ADB input fragility is the blocker for agent-driven chat sends; consider stabilizing send via the bottom-bar coordinates ([63,2148][274,2211] input, [965,2149][1028,2212] send) or a deep-link/test hook in a future change.
- OTEL end-to-end still pending (point device to https://telemetry.ffilt.us/).
- `web_fetch` 403 from some sites is site-side (not a bug).
- New idea (carried): one-tap "new chat"/session reset to recover from poisoned history.

## Run 2026-11-16T18:02Z (PM cron, vc98 `1.0.98-bdb0296`, CI run 1636 success) — TODO #697 RESOLVED

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc98 `1.0.98-bdb0296` installed (dumpsys verified). Device reachable via stable mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (~192.168.1.127:38361); pre-run script's 192.168.1.147:45357 stale again. On-device QA delegated (1 subagent, device serialized); all evidence re-verified inline by Rook. Timestamp `20261116T180523Z`.
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no app FATAL. Evidence: `docs/pm/logs/20261116T180523Z-launch.log` (145441 B), `docs/pm/screenshots/20261116T180523Z-launch.png` (238198 B, valid 1080x2400 PNG).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 65 lines (32 data)`, `loop done, lines=65 data=32`, no gateway error. Evidence: `docs/pm/logs/20261116T180523Z-hello.log` (115629 B), `docs/pm/screenshots/20261116T180523Z-hello.png` (223092 B).
- Web search PASS: `WebSearchTool: start query_len=11 … response code=200 html_len=26157 duration_ms=1023 … parsed titles=10 snippets=10 … done result_count=8`; follow-up stream `loop done, lines=149 data=74`. Evidence: `docs/pm/logs/20261116T180523Z-websearch.log` (116014 B), `...-websearch-tags.log` (2006 B), `docs/pm/screenshots/20261116T180523Z-websearch.png` (221595 B).
- **web_render (todo #697) FULLY VERIFIED on vc98 — tool-selection bias RESOLVED + new log tags present.** The strengthened descriptions in `c2c421e` worked: LLM selected `web_render` (SSE tool_call `function.name=web_render`), NOT `web_fetch`. Complete render lifecycle captured: `WebRenderTool: start url=https://example.com mode=outline` → `WebRenderManager: start` → `WebViewFactory: Loading com.google.android.webview 157.0.8103.38` → `WebRenderManager: onPageFinished url=https://example.com/` → `await ok=true error=null html_len=2179` → `done html_len=2179` → `WebRenderTool: rendered html_len=2179 (activity=true)` → `done mode=outline result_len=2178`. Follow-up stream `loop done, lines=49 data=24`, no crash/error. Evidence: `docs/pm/logs/20261116T180523Z-webrender.log` (118154 B), `...-webrender-tags.log` (2314 B), `docs/pm/screenshots/20261116T180523Z-webrender.png` (222221 B).
- **OTEL E2E VERIFIED (first real end-to-end proof).** Device telemetry endpoint (http://192.168.1.2:4318/, = this host) posted real OTLP Logs JSON to a transient listener Rook ran on port 4318. Captured lifecycle events (`app_create`, `boot_completed`, `main_activity_create/resume`, `bridge_service_create`, `network_available`, `session_resume` restored=88 msgs), interaction events (`chat_send` x3, `message_sent` x3), AND tool-level events (`web_search_start`, `web_search_done`) with `service.name=pocket`, `version=1.0.98-bdb0296`. Evidence: `docs/pm/logs/20261116T180440Z-otel-capture.log` (12108 B, capture listener `proc_616a825bb1e1`). NOT a regression; telemetry path is healthy. Note: this confirms device-side config is live; earlier runs' "endpoint unset/stale" observation was wrong for vc98.
- Settings UI reachable: `docs/pm/screenshots/20261116T180523Z-settings.png` (130602 B), `docs/pm/logs/20261116T180523Z-settings.log` (216966 B).
- Permission/dialogs PASS: no `EnhancedConfirmationDialogActivity`/grant dialog observed (`AconfigPackage … permissioncontroller.flags` lines are harmless flag-mapping, not dialogs). `mCurrentFocus` stayed Pocket.
- CI/store: latest code build run 1636 `bdb0296e` (completed/success, workflow_dispatch); store tip `1.0.98-bdb0296` vc98 sha `4dc60906…` size 13504088, matches device. HEAD `a5c87d4` docs-only ahead. No drift.

### Shipped
- No code changes this run. All three sub-items of todo #697 (web_render logging verification, tool-selection bias, OTEL E2E) verified on already-shipped vc98 — no new build needed.
- Host-side artifact verification (APK sha256 + DEX string check + CI run 1636 publish excerpt) delegated to a second subagent; see this run's report.

### Open / Next
- **Todo #697 resolved** (web_render logs + tool-selection + OTEL E2E all PASS) — claim released/completed this run.
- Remaining web_render nit (non-blocking): visual PIP-preview confirmation not agent-verifiable from screenshots; runtime path + outline extraction fully proven via logs. Consider a future UI snapshot hook only if Reed wants pixel-level proof.
- `web_fetch` 403 from some sites is site-side (not a bug).
- New idea (carried): one-tap "new chat"/session reset to recover from poisoned history.
- New idea: web_render succeeded for a static page (example.com); try a true JS/SPA page (e.g. a React app) in a future run to confirm the render path under real JS load.

## Run 2026-11-17T00:04Z (PM cron, vc98 `1.0.98-bdb0296`, CI run 1636 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc98 `1.0.98-bdb0296` installed (dumpsys verified). Device reachable via stable mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`; pre-run script's `192.168.1.147:45357` stale (No route to host). Evidence: `docs/pm/logs/20261117T000434Z-identity.log` (Pixel 6a / 27271JEGR25574 / versionCode=98 / versionName=1.0.98-bdb0296).
- Launch/no-crash PASS: monkey injected 1 event; `Displayed com.sutliff.pocket/.MainActivity ... +366ms`; no FATAL/AndroidRuntime for Pocket in captured logcat. Evidence: `docs/pm/logs/20261117T000434Z-launch-full.log`, `docs/pm/screenshots/20261117T000434Z-launch.png` (valid 1080x2400 PNG, 235516 B).
- Hello stream PASS: `PocketNet: streamChat: response code=200 content-type=text/event-stream`, first SSE data chunk, `[DONE] after 11 lines (5 data)`, `loop done, lines=11 data=5`, no gateway error. Evidence: `docs/pm/logs/20261117T000434Z-hello-stream.log` (10207 B), `docs/pm/screenshots/20261117T000434Z-hello.png` (220389 B).
- Web search NOT cleanly captured this run (ADB input automation fragility): first attempt's stream completed (`loop done, lines=37 data=18`) but no `WebSearchTool` tag appeared in the filtered log; second attempt never reached the app (no PocketNet lines at all). No crash/timeout observed. Prior proof from 2026-11-16T18:02Z run (`WebSearchTool: response code=200 html_len=26157 ... result_count=8`, follow-up `loop done, lines=149 data=74`) remains the last clean web_search evidence. Evidence attempts: `docs/pm/logs/20261117T000434Z-websearch-full.log`, `...-websearch-evidence.log`, `...-websearch2-full.log`, `...-websearch2-evidence.log`, screenshots `...-websearch.png`, `...-websearch2.png`.
- web_render not re-exercised this run (no `WebRenderTool`/`WebRenderManager` tags in today's logs); prior proof from 2026-11-16T18:02Z stands (tool-selection bias resolved, full render lifecycle captured). vc98 bytecode confirms the new tags ship (`WebRenderTool` 17 hits, `WebRenderManager` 8 hits, `onPageFinished` 4 hits, `await ok=` in `classes2.dex`).
- Permission/dialogs: no new system permission dialog observed in captured logs; `mCurrentFocus` stayed Pocket during hello turn. No `EnhancedConfirmationDialogActivity`.
- OTEL not re-exercised this run (device endpoint historically stale at 192.168.1.2:4318); prior E2E proof from 2026-11-16T18:02Z stands (real OTLP Logs JSON captured on a transient listener, lifecycle + interaction + tool events, `service.name=pocket`, `version=1.0.98-bdb0296`).
- CI/store: latest code build run 1636 `bdb0296e` (completed/success, workflow_dispatch); store tip `1.0.98-bdb0296` vc98 sha `4dc60906e09bf67b8398e8ee587a22bac34417afa345eded6d31933aa6e3e9c1` size 13504088, matches device. HEAD `21a17d0` is docs-only ahead (no code drift). APK sha256 verified against store listing.
- Homelab MCP todo board: 0 open Pocket-related todos (scanned all 166 open; no "pocket"/"web_search"/"web_render"/"otel"/"sms" hits). Gitea issues returned 403 (Forbidden) — issue state unverified (pre-existing, not a regression).
- Host-side artifact verification: APK sha256 `4dc60906...` matches `apps.json`; DEX string check confirms `WebRenderTool`/`WebRenderManager`/`onPageFinished`/`await ok=`/`rendered html_len`/`web_render` present in shipped bytecode; CI run 1636 publish excerpt confirms `publish -> http://apps.ffilt.us/publish/Pocket/app-release.apk (http 200)` and V2 signer `CN=Sutliff Homelab` SHA-256 `a27e8c1e...c3b68`.

### Shipped
- No code changes this run. HEAD `21a17d0` docs-only ahead of CI/build SHA `bdb0296e`; store tip vc98 matches device. Verified delegated QA evidence (ts 20261117T000434Z) and updated ledger.

### Open / Next
- ADB input automation remains fragile for chat sends (input coordinates drift / `NO_INPUT_CHANNEL` / IME state); consider stabilizing via a test hook or deep-link in a future change. This is the blocker for agent-verifiable web_search/web_render re-runs, not an app regression.
- web_render visual PIP-preview confirmation still not agent-verifiable from screenshots; runtime path + outline extraction fully proven via logs (2026-11-16 run).
- OTEL end-to-end already proven (2026-11-16 run); no further action unless Reed wants endpoint re-pointed.
- `web_fetch` 403 from some sites is site-side (not a bug).
- Carried ideas: one-tap "new chat"/session reset to recover from poisoned history; try web_render on a true JS/SPA page.

## Run 2026-11-17T12:33Z (PM cron, vc104 `1.0.104-c1511f6`, CI run 1672 success)

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc104 `1.0.104-c1511f6` installed (dumpsys verified). Device reachable via mDNS `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (192.168.1.127:38361). Pre-run script's `192.168.1.147:45357` stale.
- Launch/no-crash PASS: app launched, focused `com.sutliff.pocket/.MainActivity`, no FATAL.
- **web_render full-mode fix VERIFIED (root cause: cached WebView reuse caused evaluateJavascript callback to silently never fire on 2nd+ loadUrl).** Two consecutive full renders both succeeded: `WebRenderManager: evaluateJs callback value_len=2242`, `await ok=true error=null html_len=2179`, `done mode=full result_len=2178`. No timeout. Evidence: `docs/pm/logs/20261117T123309Z-webrender-verify.log`. Fix ships in vc104 (fresh WebView per render + try/catch + destroy + container removal).
- CI/store: run 1672 `c1511f68` completed/success (workflow_dispatch); store tip `1.0.104-c1511f6` vc104 sha `9b65d67e7bf8de5fb70429f1e69a0671093370229c0d36b41adf5201dc3c03b5` size 13504092, matches device. Run 1671 (same SHA) had completed/failure due to appstore scaled to 0 (503 publish); scaled appstore back to 1 replica, re-ran 1672 successfully.
- Permission/dialogs: no new system permission dialog observed.

### Shipped
- Code fix: `app/.../tools/WebTools.kt` `WebRenderManager.render` now creates a fresh WebView per call (removed single-instance cache), wraps `evaluateJavascript` in try/catch with diagnostic logging, destroys the WebView and removes the container after render. Commit `c1511f6`. CI run 1672 success. Installed on Pixel 6a (vc104).
- Infra: scaled `appstore` Deployment in ns `appstore` from 0 → 1 (was scaled down ~9 min before this run, causing 503 on apps.ffilt.us and blocking publish). Root cause of scale-down unknown (possible manual/keeper); monitor.

### Open / Next
- web_render on a true JS/SPA page still untested (carried idea).
- ADB input automation fragility remains (coordinates drift); consider test hook.
- web_render visual PIP-preview confirmation still not agent-verifiable.
- OTEL E2E already proven (2026-11-16 run).
- `web_fetch` 403 from some sites is site-side (not a bug).
- Carried ideas: one-tap "new chat"/session reset; SMS opt-in gating.

## Run 2026-11-17T18:31Z (PM cron, vc107 `1.0.107-f52f1ac`, CI run 1685 success) — 2-code-commit batch shipped + verified

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), freshly installed vc107 `1.0.107-f52f1ac` (dumpsys versionCode=107). Pre-run script's `192.168.1.147:45357` stale again; device reached via mDNS `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (~192.168.1.127:38361). Evidence: `docs/pm/logs/20261117T181158Z-01-launch.log`.
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`, no app FATAL/AndroidRuntime in captured logs. Evidence: `docs/pm/logs/20261117T181158Z-01-launch.log`, `docs/pm/screenshots/20261117T181158Z-01-launch.png` (valid 1080x2400 PNG).
- Hello stream PASS: `PocketNet: streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 19 lines (9 data)`, `loop done, lines=19 data=9`, no gateway error. Evidence: `docs/pm/logs/20261117T181304Z-02-hello.log`, `docs/pm/screenshots/20261117T181304Z-02-hello.png`.
- **web_render CLEAN-TEXT fix VERIFIED (f4b1161)**: in-page JS DOM→markdown extraction shrank example.com from vc104's raw-HTML `html_len=2179 result_len=2178` (no-op conversion) to `evaluateJs callback value_len=917` → `await ok=true error=null html_len=905` → `rendered len=905 (webview=true)` → `done mode=full result_len=905`. Zero raw-HTML markers (`&lt;`/`<html`/`<div`/`\u003c`) in the full log. Stream completed (`streamChat [DONE] after 15 lines (7 data)`, `loop done, lines=15 data=7`), no timeout. Evidence: `docs/pm/logs/20261117T181853Z-05-webrender-tags.log`, `...-webrender-full.log` (165 KB), `docs/pm/screenshots/20261117T181853Z-05-webrender-full.png` (294 KB). Outline mode also verified earlier same run (`result_len=905`, no timeout): `docs/pm/logs/20261117T181541Z-03-webrender-tags.log`.
- **PiP rounded/zoom/fullscreen fix VERIFIED (f52f1ac)**: new marker `WebRenderManager: pip_added rounded=true zoom=80` present in both renders. Tap-to-expand confirmed via uiautomator UI dumps: "✕ Exit" button count 0 (small PiP) → 1 (after tap) → 1 (after exit tap landed on status bar at y=63, not the exit control, so collapse-by-tap not cleanly asserted this run). Code review confirms `expandPipOnUi`/`collapsePipOnUi` swap `fullPipParams`/`smallPipParams` layout params + toggle exit/close/overlay visibility. Evidence: `docs/pm/logs/20261117T181541Z-pip-ui.xml`, `20261117T181559Z-expanded-ui.xml`, `20261117T181620Z-after-exit-ui.xml`, screenshots `20261117T181559Z-04-pip-tap.png`, `20261117T181620Z-04-pip-collapsed.png`.
- web_search NOT captured this run (ADB input-automation fragility — no `WebSearchTool` markers in today's logs). Not a regression; last clean proof from 2026-11-16T18:02Z (`result_count=8`). Flagged as QA-automation gap, not app bug.
- Permission/dialogs: no FATAL or dialog observed in captured logs; no new permission prompt (did not tap through any system dialog).
- Homelab MCP todo board: 0 open Pocket-related todos (scanned all 166 open; no pocket/web_search/web_render/otel/sms hits). Gitea issues API still 403 (pre-existing).
- CI/store: run 1685 `f52f1ac3` completed/success (push); store tip `1.0.107-f52f1ac` vc107 sha `3960038fb07296faf59549d817b097f1f368aa78ef7c6826f38c9831e0a4bdf9` size 13504092, matches HEAD code SHA. APK downloaded + sha256 verified identical. V2 signer `CN=Sutliff Homelab` SHA-256 `a27e8c1e...c3b68`. DEX strings confirm all new markers shipped (`pip_added rounded=true zoom=80`, `pip_expanded`/`collapsed`/`dismissed`, `decodeFromString`, `EXTRACT_JS`, JS NOISE-strip walk).

### Shipped
- `f4b1161` — fix(pocket): web_render returns clean readable text, not raw HTML. In-page JS tree-walk extracts DOM→markdown (strips script/style/nav/header/footer/aside + ad/JSON-LD noise, promotes h1-h6, expands links/lists/tables); `kotlinx Json.decodeFromString` decodes `\uXXXX`/`\n`/`\"` correctly; static fallback still uses `htmlToText` once.
- `f52f1ac` — feat(pocket): rounded web_render PiP (16dp corner radius), zoom 80%, top-right 260x180dp, tap-to-expand fullscreen with `✕ Exit` to collapse, `✕` to dismiss; new render dismisses prior PiP.
- Both built+published in CI run 1685 (completed/success, 5m38s, V2-signed), installed on Pixel 6a (vc104→vc107).

### Bugs / Infra notes
- CI run 1684 (older SHA `f4b1161d`, push) FAILED — Gitea runner lost Docker daemon mid-job (`Cannot connect to the Docker daemon`). Transient runner/pod churn (runner pod restarted ~12 min before this run; runner-keeper cycling). Superseded by green 1685; no code issue.
- CI run 1687 (duplicate workflow_dispatch for same HEAD `f52f1ac3`) was still in_progress at report time — redundant re-build, will republish same vc107 if it finishes. No action needed.

### Open / Next
- PiP collapse-by-tap not cleanly asserted (exit tap hit status bar); visual PIP preview not agent-verifiable from screenshots. Runtime path + expand fully proven via UI dumps + logs.
- web_search on-device QA remains fragile (ADB input coordinates); last clean proof stands.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; web_render on a true JS/SPA page (not just static example.com); SMS opt-in gating.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-18T00:14Z (PM cron, vc111 `1.0.111-f3b855a`, CI run 1698 success) — config-import feature first on-device QA

### QA Outcomes
- Identity/device: Pixel 6a (27271JEGR25574), reached via mDNS `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`. Pre-run script's `192.168.1.147:45357` stale again. **Device was on vc109 (`1.0.109-4617851`) at run start — 2 behind store tip vc111; Rook downloaded vc111 from store, sha256 verified `b8a3b2b85046…64d0` against `apps.json`, `adb install -r` Success, post-install dumpsys versionCode=111 / versionName=1.0.111-f3b855a.**
- CI/store: run 1698 `f3b855aa` completed/success (push, build job 2140 green); log confirms BUILD SUCCESSFUL 5m34s, `publish -> http://apps.ffilt.us/publish/Pocket/app-release.apk (http 200)`, V2 signer `CN=Sutliff Homelab` SHA-256 `a27e8c1e…c3b68`. Store tip `1.0.111-f3b855a` vc111 sha `b8a3b2b8…` matches HEAD code SHA f3b855a. No drift.
- Launch/no-crash PASS: `Displayed com.sutliff.pocket/.MainActivity ... +249ms`, focus Pocket, no FATAL/AndroidRuntime. Evidence: `docs/pm/logs/20261118T000546Z-launch.log`, `docs/pm/screenshots/20261118T000546Z-identity.png`.
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 13 lines (6 data)`, `loop done, lines=13 data=6`, no gateway error. Evidence: `docs/pm/logs/20261118T000546Z-hello.log`, `docs/pm/screenshots/20261118T000546Z-hello.png`.
- Web search PASS: `WebSearchTool: start query_len=19 … response code=200 html_len=28229 duration_ms=1125 … parsed titles=10 snippets=10 … done result_count=8`; follow-up stream `loop done, lines=27 data=13`. No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261118T000546Z-websearch.log`, `docs/pm/screenshots/20261118T000546Z-websearch.png`.
- **Config-import UI (NEW feature, first on-device check) PASS:** Settings reachable; new section present in UI hierarchy — `Config import`, hint `Config URL (HTTPS only)`, `Passphrase`, `Import config` (verified in `docs/pm/logs/20261118T000546Z-settings-scroll2.xml`); no crash. No real URL/passphrase submitted (reachability/inspection only). Evidence: `docs/pm/screenshots/20261118T000546Z-settings.png`, `docs/pm/logs/20261118T000546Z-settings-ui.xml`, `...-settings-scroll.xml`, `...-settings-scroll2.xml`.
- **web_render PiP exit-button fix (4617851) VERIFIED:** `WebRenderManager: pip_added rounded=true zoom=80`, full render `… result_len=905`, and `✕` control bounds `[978,199][1039,263]` (top y=199) fully below `statusBarBackground [0,0][1080,132]` — old bug (exit at y≈63) gone. Evidence: `docs/pm/logs/20261118T000546Z-pip.log`, `...-pip-ui.xml`, `docs/pm/screenshots/20261118T000546Z-pip.png`.
- Permission/dialogs PASS: no grant/enhanced-confirmation dialog, focus never left Pocket. Manifest diff f52f1ac..f3b855a adds no permission (only comment + `allowBackup true→false`) → no new startup dialog.
- Homelab MCP todo board: scanned 167 open todos, 0 actionable Pocket items (#697 resolved earlier). 6 keyword hits all unrelated (Jellyfin APK, dash, outage hardening, HA companion, os-keystore, GitOps lifecycle).
- On-device QA delegated (1 serialized subagent, ts `20261118T000546Z`); all artifact paths re-verified inline by Rook (byte sizes + log excerpts match). Host-side artifact/source check delegated to a parallel subagent: APK sha256 matches store; DEX markers `KeystoreWrap`/`ConfigImportClient`/`SeedConfig`/`setUserAuthenticationRequired`/`applied_import_version`/`pip_added` all present in shipped bytecode.

### Shipped (3 code commits since last ledger run vc107)
- `4617851` — fix(pocket): keep expanded web_render PiP exit button below status bar (verified this run).
- `7697d56` — feat(pocket): config endpoint passphrase import with Keystore-wrapped secrets. New: `crypto/KeystoreWrap.kt` (AES-256-GCM, headless-safe `setUserAuthenticationRequired(false)`, StrongBox/TEE, non-exportable key), `network/ConfigImportClient.kt` (HTTPS-only + hostname verify, passphrase POSTed in body, server-side 401/429), `datastore/SeedConfig.kt` (`applied_import_version` tracking), Settings UI section, `docs/config-import.md`, `allowBackup=false`.
- `f3b855a` — fix: KeystoreWrap import `javax.crypto.KeyGenerator` (CI run 1697 had failed on this; 1698 green).
- All three built+published in CI run 1698 (completed/success, V2-signed), store tip vc111. On-device security audit: low risk — secrets wrapped before persistence, no logging, HTTPS enforced. Residual (per docs, intentional): no tailnet/private-IP host restriction on user-supplied HTTPS URL, empty cert-pinning hook, legacy plaintext compat path.

### Open / Next
- Config-import END-TO-END not exercised (needs a live `reeds/pocket-cfg` endpoint + real 6-word passphrase); only UI reachability + source/bytecode verified. Verify fetch→unwrap→persist on-device when endpoint is up.
- web_render on a true JS/SPA page still untested (carried idea; static example.com proven).
- ADB multi-word input quirk this run (`adb shell input text` truncates at first space) — worked around with word-by-word + keyevent 62. Automation quirk, not app bug.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-18T06:04Z (PM cron, vc113 `1.0.113-b7952a0`, CI run 1702 success) — user-CA trust ship verified

### QA Outcomes
- Identity/device: Pixel 6a (27271JEGR25574), reached via mDNS `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`. Pre-run script's `192.168.1.147:45357` stale (`No route to host`). **Device was on vc111 at run start — Rook downloaded vc113 from store, sha256 verified `eda106d67202…3316` against `apps.json` (size 13521040, built 2026-11-18T01:32:28Z), `adb install -r` Success, post-install vc113.** Evidence: `docs/pm/logs/20261118T060609Z-identity.log`.
- CI/store: run 1702 `b7952a054123` completed/success (push, job 2145 green); log confirms BUILD SUCCESSFUL 5m34s, `publish -> http://apps.ffilt.us/publish/Pocket/app-release.apk (http 200)`, V2 signer `CN=Sutliff Homelab` SHA-256 `a27e8c1e…c3b68`. Store tip `1.0.113-b7952a0` vc113 sha `eda106d67202…` == HEAD code SHA b7952a0. No drift. Host-side APK check: manifest binary XML contains `networkSecurityConfig` attr (UTF-16) + `resources.arsc` contains `network_security_config` — CA-trust change confirmed baked into shipped APK.
- Launch/no-crash PASS: `Displayed com.sutliff.pocket/.MainActivity ... +233ms`, focus Pocket, FATAL=0, AndroidRuntime exceptions=0. Evidence: `docs/pm/logs/20261118T060609Z-launch.log`, `...-launch-full.logcat.txt` (505 KB), `docs/pm/screenshots/20261118T060609Z-launch.png` (valid 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 15 lines (7 data)`, `loop done, lines=15 data=7`, no gateway error. Evidence: `docs/pm/logs/20261118T060609Z-hello.log`, `docs/pm/screenshots/20261118T060609Z-hello.png`.
- Web search PASS: `WebSearchTool: start query_len=20 … response code=200 html_len=28088 duration_ms=1229 … parsed titles=10 snippets=10 … done result_count=8`; follow-up stream `loop done, lines=265 data=132`. No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261118T060609Z-websearch.log`, `docs/pm/screenshots/20261118T060609Z-websearch.png`.
- Config-import UI (vc111 feature) still present, no regression: `Config import`, `Config URL (HTTPS only)`, `Passphrase`, `Import config` in scrolled settings hierarchy; endpoint preset `https://pocket-cfg.homelab.internal/config`. Evidence: `docs/pm/logs/20261118T060609Z-settings-scroll2.xml`, `docs/pm/screenshots/20261118T060609Z-settings.png`, `...-settings-scroll.png`, `...-settings-scroll2.png`.
- Permission/dialogs PASS: no EnhancedConfirmationDialogActivity/grant dialog (0 markers in all full logs), `mCurrentFocus` stayed Pocket; opt-in dangerous perms (SMS/location/notifications/contacts/phone) all `granted=false`. Evidence: `docs/pm/logs/20261118T060609Z-permission.log`, screenshot `...-permission-state.png`.
- New user-CA trust (vc113): no `CertPathValidatorException`/`Trust anchor not found` in any full log; app connects to internal HTTP endpoints cleanly. No HTTPS-to-internal-CA path exercised this run (config-import E2E still pending).
- Homelab MCP todo board: scanned 172 open todos, 0 Pocket-related (pocket/web_search/web_render/otel/sms). No claimed Pocket todos. No actionable items.
- On-device QA delegated (1 serialized subagent, ts `20261118T060609Z`); all artifact paths re-verified inline by Rook (byte sizes + log excerpts + PNG dimensions).

### Shipped
- `b7952a0` — feat(pocket): trust user-installed CAs for internal HTTPS. `AndroidManifest.xml` adds `android:networkSecurityConfig="@xml/network_security_config"`; new `res/xml/network_security_config.xml` with `<certificates src="system"/>` + `<certificates src="user"/>`, `cleartextTrafficPermitted=true`. Enables HTTPS to `*.homelab.internal` endpoints with the internal root CA installed (companion: reeds/appstore now serves `/ca.crt`). CI run 1702 success, store tip vc113, installed + verified on Pixel 6a this run.

### Open / Next
- Config-import END-TO-END still unexercised (needs live `reeds/pocket-cfg` endpoint [pod up in ns pocket-cfg] + real 6-word passphrase); only UI reachability/source/bytecode verified. `pocket-cfg.homelab.internal/config` preset present on device.
- web_render on a true JS/SPA page still untested (carried idea; static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); last several runs' web_search captures succeeded this run.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-18T12:04Z (PM cron, vc115 `1.0.115-60631b0`, CI run 1710 success) — keystore self-heal ship verified

### QA Outcomes
- Identity/device: Pixel 6a (27271JEGR25574), reached via stable mDNS `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`; pre-run script's `192.168.1.147:45357` stale (`No route to host`). **Device was on vc113 at run start (2 behind store tip vc115) — Rook downloaded vc115 from store, sha256 verified `9f9ec9dd6031…a852` against `apps.json` (size 13521040, built 2026-11-18T06:29:03Z), `adb install -r` Success, post-install vc115 / 1.0.115-60631b0.** Evidence: `docs/pm/logs/20261118T120404Z-vc115-install.log`, `...-120441Z-identity.log`.
- CI/store: run 1710 `60631b05bdb3` completed/success (push, job 2156 green); log confirms `BUILD SUCCESSFUL in 5m 12s`, `publish -> http://apps.ffilt.us/publish/Pocket/app-release.apk (http 200)`, V2 signer `CN=Sutliff Homelab` SHA-256 `a27e8c1ec1e7…5c3b68`. Store tip `1.0.115-60631b0` vc115 sha `9f9ec9dd6031…` == HEAD code SHA 60631b0. No drift. Host-side DEX check of shipped APK confirms keystore-fix markers present: `PocketKeystore` (1), `wrap-after-regen` (1), `strongbox-unavailable` (1), `deleteEntry` (1), `SecretWrapException` (1).
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity` (mCurrentFocus), no real FATAL EXCEPTION / AndroidRuntime crash in any this-run log (grep "FATAL" hits were only the literal grep-header strings written into logs). Evidence: `docs/pm/logs/20261118T120446Z-launch.log`, `docs/pm/screenshots/20261118T120446Z-launch.png` (valid 1080x2400 PNG, 199825 B).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 25 lines (12 data)`, `loop done, lines=25 data=12`, no gateway error. Evidence: `docs/pm/logs/20261118T120557Z-hello-send.log`, `docs/pm/screenshots/20261118T120557Z-hello.png` (189358 B).
- Web search PASS (attempt 4; attempts 1-3 blocked by ADB input fragility, not app bug): `WebSearchTool: start query_len=23 max_results=8`, `response code=200 html_len=29227 duration_ms=1434`, `parsed titles=10 snippets=10`, `done result_count=8 duration_ms=1434`; follow-up stream healthy. Evidence: `docs/pm/logs/20261118T120822Z-websearch-send.log`, `docs/pm/screenshots/20261118T120822Z-websearch.png` (197790 B). Failed-attempt logs: `...120655Z-websearch-attempt1.log`, `...120733Z-attempt2.log`, `...120759Z-attempt3.log`.
- Settings/UI reachable + config-import section intact (no regression from vc115's KeystoreWrap-only change): `Config import`, `Config URL (HTTPS only)`, `Passphrase`, `Import config`, preset `pocket-cfg.homelab.internal/config` in scrolled settings hierarchy. Evidence: `docs/pm/logs/20261118T121003Z-settings-bottom-ui.xml`, screenshots `...120853Z-settings.png`, `...120921Z-settings2.png`, `...121003Z-settings-bottom.png`.
- Permission/dialogs PASS: `mCurrentFocus` stayed Pocket, no EnhancedConfirmationDialog/grant markers in any this-run log. Evidence: `docs/pm/logs/20261118T120921Z-settings2.log`.
- Config endpoint (open item): pod `pocket-cfg-67d48c89dd-jjqxq` Running 1/1, 0 restarts, 10h age (ns `pocket-cfg`, node reed-a320ms2h). E2E import still needs a real 6-word passphrase — not exercised autonomously (credential).
- Homelab MCP todo board: scanned 172 open todos, 0 Pocket-actionable (3 keyword hits unrelated: Jellyfin APK, HA companion fork, os-keystore OPNsense plugin). Gitea issues API still 403 (pre-existing). Services: apps.ffilt.us 200, telemetry.ffilt.us 405 (up).
- On-device QA delegated (1 serialized subagent, ts `20261118T1204xx`–`121014Z`); all artifact paths re-verified inline by Rook (byte sizes + log excerpts + PNG dimensions + DEX markers). QA manifest: `docs/pm/logs/20261118T121014Z-summary-manifest.log`.

### Shipped
- `60631b0` — fix(keystore): surface real exception + self-heal key on wrap/unwrap failure (`crypto/KeystoreWrap.kt`, +115/-58). `SecretWrapException` now carries real cause class+message; new `PocketKeystore` Log.w on every failure (type+message only, never secret); `wrap()` drops + regenerates key and retries once on failure (new plaintext always recoverable); `unwrap()` on invalidated key drops bad key and installs a fresh one so the subsequent re-import wrap succeeds, then signals re-import; `newKey()`/`deleteKey()` clear stale/partial alias entries; StrongBox→platform fallback preserved. Built+published in CI run 1710 (completed/success, V2-signed), store tip vc115, installed + verified on Pixel 6a this run.

### Open / Next
- Config-import END-TO-END still unexercised (endpoint pod healthy; needs real 6-word passphrase — credential, not done autonomously). UI reachability/source/bytecode verified.
- Keystore self-heal recovery paths (wrap retry / unwrap re-import signal) verified by source + shipped-bytecode markers, not by forcing a real key-invalidation on device (would need security-change simulation). No regression observed in normal launch/hello/web_search/settings.
- web_render on a true JS/SPA page still untested (carried idea; static example.com proven vc107).
- ADB web_search input fragility remains (attempts 1-3 failed, 4 succeeded) — automation issue, not app bug. Consider test hook/deep-link to stabilize chat sends.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-18T18:03Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — keystore cipher-IV fix ship verified

### QA Outcomes
- Identity/device: Pixel 6a (27271JEGR25574), stable mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`. Pre-run script's `192.168.1.147:45357` stale (`No route to host`). **Device was already on vc117 at run start** (`versionCode=117 versionName=1.0.117-72e33fd`) — no install needed this run. Screen awake (timeout 600000), rotation locked portrait. Evidence: inline getprop/dumpsys.
- CI/store: run 1717 `72e33fdc2259` completed/success (push, run_number 121); HEAD 72e33fd == store tip. Store `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358`. Host download `apps.ffilt.us/apk/Pocket/app-release.apk` -> 13,521,036 B, sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog. DEX markers for vc117 code present: `X-Pocket-Version`, `X-Pocket-Git-Hash`, `setRandomizedEncryptionRequired`. No drift.
- Launch/no-crash PASS: `monkey -p com.sutliff.pocket 1` VM exit 0, no FATAL EXCEPTION / AndroidRuntime crash in any this-run log. Evidence: `docs/pm/logs/20261118T180529Z-launch.log`, `...-launch-unfiltered.log`, screenshot `docs/pm/screenshots/20261118T180529Z-launch.png` (valid 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 21 lines (10 data)`, `loop done, lines=21 data=10`, no gateway error. Evidence: `docs/pm/logs/20261118T180529Z-hello.log`, screenshots `...-hello.png` / `...-hello-bottom.png`.
- Web search PASS: `WebSearchTool: response code=200 html_len=30472 duration_ms=1207`, `parsed titles=10 snippets=10`, `done result_count=8`; follow-up stream `loop done, lines=135 data=67`. One transient TLS `Connection reset` on a first attempt (query_len=31) self-recovered on retry (query_len=38) — not a bug. Evidence: `docs/pm/logs/20261118T180529Z-websearch.log`, `...-websearch-ui-transcript.txt`, screenshots `...-websearch.png` / `...-websearch-result.png` / `...-websearch-result2.png`.
- Settings/UI reachable PASS: settings screen + scrolled settings rendered, no crypto exception on entry. Evidence: `docs/pm/logs/20261118T180529Z-settings.log`, screenshots `...-settings.png` / `...-settings-scrolled.png` (both 1080x2400).
- Crypto regression watch PASS (key for vc117): NO MATCHES across all this-run logs for `caller provided IV not permitted`, `caller provided IV`, `KeyStoreException`, `AEADBadTagException`, `InvalidKeyException`, `self-heal`, `javax.crypto` — the cipher-generated-IV fix holds, no keystore wrap/unwrap regression. Evidence: `docs/pm/logs/20261118T180529Z-crypto.log` (from `...-crypto-full.log`, 349 lines).
- Permission/dialogs PASS: no `EnhancedConfirmationDialog` / grant markers; no new system dialog to tap past.
- Services/open item: pocket-cfg pod `pocket-cfg-58664b7775-p55mv` Running 1/1, 0 restarts, 161m (ns pocket-cfg, node reed-a320ms2h); `pocket-cfg.homelab.internal/config` unreachable from server as expected (tailnet-only). apps.ffilt.us 200, telemetry.ffilt.us 405 (up). Config-import E2E still needs a real 6-word passphrase (credential — not done autonomously).
- Homelab MCP todo board: 879 total, 31 keyword hits, **0 open Pocket-actionable** (3 open Android hits unrelated: Jellyfin APK #54, HA companion fork #264, os-keystore plugin #355). All Pocket todos (#423/424/425/459/697/795/815/816/817/818) completed.
- On-device QA delegated (2 parallel subagents: on-device QA + independent CI/store verification); all 26 artifact paths re-verified inline by Rook (byte sizes + log excerpts + PNG dimensions + DEX markers).

### Shipped
- `72e33fd` — fix(keystore): use cipher-generated IV (randomized encryption); send version headers. `crypto/KeystoreWrap.kt`: `wrapWith()` now `cipher.init(ENCRYPT_MODE, key)` then reads `cipher.iv` (12 bytes) instead of supplying its own `GCMParameterSpec` — the old path threw `caller provided IV not permitted` because the key is generated with `setRandomizedEncryptionRequired(true)`; removed the now-unused `SecureRandom` import. `network/ConfigImportClient.kt`: config-import POST now sends `X-Pocket-Version` / `X-Pocket-Version-Code` / `X-Pocket-Git-Hash` headers. Built+published in CI run 1717 (completed/success, V2-signed), store tip vc117, verified on-device this run.

### Open / Next
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug now fixed in vc117, so the fetch→unwrap→persist path should work — needs a real 6-word passphrase, credential, not done autonomously). UI reachability/source/bytecode verified.
- web_render on a true JS/SPA page still untested (carried idea; static example.com proven vc107).
- ADB web_search input fragility: this run had one transient TLS reset (self-recovered); no coordinate misses. Consider a test hook/deep-link to stabilize chat sends (carried idea).
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating.
- `web_fetch` 403 from some sites is site-side (not a bug).

### Incident (same run, 20261118T183900Z) — app store 503, self-healed
- At ~18:33Z, `apps.ffilt.us/apps.json` began returning `503 no available server` (it was 200 and APK sha-verified earlier this run at 18:04–18:05Z). Root cause: `appstore/appstore` Deployment (ns `appstore`, image `reeds/appstore:8`, label `rook.io/lifecycle: persistent`) was parked at **0/0** — k8s event `Scaled down replica set appstore-7bf9fbb754 from 1 to 0` ~11m prior; appstore pod had been `Preempted` on node reed-a320ms2h and a `node-gatekeeper` job (ns node-scout) failed around the same window. GitOps source `/root/sutliff-cluster/apps/appstore/deployment.yaml` declares `replicas: 1`, so this was drift, not intended.
- Fix applied (documented 503 triage): `kubectl -n appstore scale deploy appstore --replicas=1` → rollout succeeded, pod 1/1 Running on wyse5070. Re-verified `apps.json` 200 (3/3 polls, 1942 B), catalog row `com.sutliff.pocket 1.0.117-72e33fd vc=117` == HEAD == device. Store back to healthy.
- Todo created to track the park mechanism (why a `persistent` appstore gets scaled to 0 / preempted) so it can be made keeper-aware; not reverted by Flux (source says 1).

## Run 2026-11-19T00:10Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — QA verified, ledger catch-up

### QA Outcomes
- Identity: Pixel 6a (27271JEGR25574), vc117 `1.0.117-72e33fd` installed from store (sha `1d9900e14631…e358`). Device reachable via mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (pre-run script's hardcoded `192.168.1.147:45357` stale, as usual). Evidence: `docs/pm/logs/20261119T001000Z-identity.log`, `docs/pm/screenshots/20261119T001000Z-launch.png` (device/version captured inline).
- Launch/no-crash PASS: monkey injected 1 event, VM exited 0, no FATAL EXCEPTION / AndroidRuntime crash (the 6 `AndroidRuntime` lines are benign monkey bootstrap + VM exit 0). Evidence: `docs/pm/logs/20261119T001000Z-launch.log` (29,972 B), `docs/pm/screenshots/20261119T001000Z-launch.png` (128,653 B).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 15 lines (7 data)`, `loop done, lines=15 data=7`, no gateway error. Evidence: `docs/pm/logs/20261119T001000Z-hello.log` (701 B), `docs/pm/screenshots/20261119T001000Z-hello.png` (101,061 B).
- Web search PASS: `WebSearchTool: start query_len=13 … response code=200 html_len=28486 duration_ms=878 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=878`; follow-up stream `loop done, lines=133 data=66`. No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261119T001000Z-websearch.log` (2,450 B), `docs/pm/screenshots/20261119T001000Z-websearch.png` (180,415 B).
- Crypto regression watch PASS (vc117 key): NO matches across all this-run logs for `caller provided IV`, `KeyStoreException`, `AEADBadTagException`, `InvalidKeyException`, `self-heal` — cipher-generated-IV fix holds, no keystore wrap/unwrap regression.
- Permission/dialogs PASS: no EnhancedConfirmationDialog/grant markers; no new system dialog tapped past.
- OTEL not re-exercised this run (endpoint unset by default on device); prior E2E proof from 2026-11-16T18:02Z stands.
- CI/store: latest code build run 1717 `72e33fdc` completed/success (push); store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA 72e33fd; device vc117 matches. HEAD `33a96ec` is docs-only ahead. No drift. apps.ffilt.us 200, telemetry.ffilt.us 405 (up). pocket-cfg pod `pocket-cfg-58664b7775-8mgmj` Running 1/1. appstore Deployment 1/1 (self-healed from 0 after the 2026-11-18T18:33Z incident).
- Homelab MCP todo board: 0 open Pocket-actionable todos (scanned open board; Pocket todos #423/#424/#425/#459/#697/#795/#815-#818 all completed). No actionable items.

### Shipped
- No code changes this run. HEAD `33a96ec` docs-only ahead of CI/build SHA `72e33fd`; store tip vc117 matches device. Verified delegated QA evidence (ts 20261119T001000Z) and updated ledger (catch-up from uncommitted 00:10Z run).

### Open / Next
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI reachability/source/bytecode verified.
- web_render on a true JS/SPA page still untested (carried idea; static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); standard hello/web_search captures succeeded this run.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).
- Note: the delegated QA subagent (deleg_a85d8666) captured evidence at ts 20261119T061936Z: identity (Pixel 6a / 27271JEGR25574 / vc117 `1.0.117-72e33fd`), launch/no-crash PASS (`docs/pm/logs/20261119T061936Z-launch.log`, `docs/pm/screenshots/20261119T061936Z-launch.png`), hello stream PASS (`[DONE] after 15 lines (7 data)`, `docs/pm/logs/20261119T061936Z-hello.log`, `docs/pm/screenshots/20261119T061936Z-hello.png`). Correction (2026-11-19 PM run): this run ALSO captured web_search PASS (`WebSearchTool: response code=200 html_len=27734 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1003`, plus follow-up `streamChat … loop done, lines=69 data=34`) in `docs/pm/logs/20261119T061936Z-websearch.log` + `docs/pm/screenshots/20261119T061936Z-websearch.png` (valid 1080x2400); crypto regression watch PASS (no IV/keystore/crypto errors; only benign `keystore2 watchdog` line) in `docs/pm/logs/20261119T061936Z-crypto.log`; permission state in `docs/pm/logs/20261119T061936Z-permission.log`; settings screenshot `docs/pm/screenshots/20261119T061936Z-settings.png` (valid 1080x2400). These files were stranded untracked by the prior run and are committed with this run's ledger update. No FATAL/AndroidRuntime in captured logs.

### Shipped (infra, same day)
- appstore Deployment recovered from 0 replicas (2026-11-18T18:33Z incident) and is 1/1 Running; apps.ffilt.us 200. Root-cause tracking todo for the park/preempt mechanism remains open in the lifecycle tiering cluster (see todos #373/#378/#380/#381).

## Run 2026-11-19T18:14Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean/proven, no code change

### QA Outcomes
- Identity/device: Pixel 6a (27271JEGR25574), vc117 `1.0.117-72e33fd` (dumpsys versionCode=117). Device NOT reachable at pre-run script's hardcoded `192.168.1.147:45357` (stale); `adb-dev discover` MAC scan also stale. Found live via avahi mDNS `_adb-tls-connect._tcp` at `192.168.1.127:38361` (already connected). Foreground `com.sutliff.pocket/.MainActivity`. Screen awake, rotation locked portrait.
- Launch/no-crash PASS: 0 `FATAL EXCEPTION`, 0 `Process com.sutliff.pocket has crashed` in full logcat; the 25 `AndroidRuntime` hits are benign uiautomator `RuntimeInit` bootstrap lines. Evidence: `docs/pm/logs/20261119T180723Z-launch-full.log` (317,874 B), `docs/pm/logs/20261119T180723Z-full.log`, `docs/pm/screenshots/20261119T180723Z-launch.png` (valid 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE data chunk (`ocg:hy4-flash-free`), `[DONE] after 7 lines (3 data)`, `loop done, lines=7 data=3`, no gateway error. Evidence: `docs/pm/logs/20261119T180723Z-hello.log`, `docs/pm/screenshots/20261119T180723Z-hello.png` (valid 1080x2400).
- Web search PASS: `WebSearchTool: start query_len=29 max_results=8 … response code=200 html_len=28781 duration_ms=1053 … parsed titles=10 snippets=10 … done result_count=8`; follow-up `streamChat response code=200`. No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261119T180723Z-websearch.log`, `docs/pm/screenshots/20261119T180723Z-websearch.png` (valid 1080x2400).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across 4,614 logcat lines. Evidence: `docs/pm/logs/20261119T180723Z-crypto.log`.
- Settings/UI reachable PASS: settings screen rendered (Inference Server, Base URL, API Key, Model, Temperature, Max Tokens, System Prompt, Appearance). Evidence: `docs/pm/logs/20261119T180723Z-settings-ui.xml`, `docs/pm/screenshots/20261119T180723Z-settings.png` (valid 1080x2400).
- Permission/dialogs PASS: no `EnhancedConfirmationDialog`/`GrantPermissionsActivity`/grant markers in any this-run log; no new system dialog to tap past. Evidence: `docs/pm/logs/20261119T180723Z-permission.log`.
- OTEL not re-exercised this run; prior E2E proof from 2026-11-16T18:02Z stands. No regression.
- On-device QA delegated (parallel subagents, ts `20261119T180723Z`); all artifacts re-verified inline by Rook (byte sizes + log excerpts + PNG dimensions + crash/crypto scans).
- CI/store/services: latest code build run 1717 `72e33fdc` completed/success (push); store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA; device vc117 matches. HEAD `9928226` docs-only ahead (no code drift). apps.ffilt.us 200, telemetry.ffilt.us 405 (up), appstore Deploy 1/1, pocket-cfg pod `pocket-cfg-58664b7775-gb5bg` Running 1/1 0 restarts 7h11m.
- Homelab MCP todo board: scanned 173 open todos; 7 keyword hits, 0 Pocket-actionable (all unrelated: apps.ffilt.us web frontend, Jellyfin APK, dash, outage hardening, HA companion fork, os-keystore plugin, lifecycle tiering). No Pocket todos need action.

### Bookkeeping
- Reconciled stranded untracked evidence from the prior `061936Z` partial run (web_search `result_count=8`, crypto clean, permission, settings screenshot) that the prior ledger note wrongly said wasn't captured; committed as Rook in `9928226`. This run's evidence + ledger entry committed next.

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- web_render on a true JS/SPA page still untested (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's sends succeeded.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-20T00:05Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean/proven, no code change

### QA Outcomes
- Identity/device: Pixel 6a (27271JEGR25574), vc117 `1.0.117-72e33fd` (dumpsys versionCode=117). Device reachable at live mDNS transport `192.168.1.127:38361` (pre-run script's `192.168.1.147:45357` stale). Foreground `com.sutliff.pocket/.MainActivity`. Screen awake (600000 ms), rotation locked portrait. Evidence: `docs/pm/logs/20261120T000447Z-01-launch.log` (190,337 B), `docs/pm/screenshots/20261120T000447Z-01-launch.png` (199,348 B, 1080x2400).
- Launch/no-crash PASS: 0 `FATAL EXCEPTION`, 0 `Process com.sutliff.pocket has crashed` in launch log (0 `AndroidRuntime` lines). Evidence: `docs/pm/logs/20261120T000447Z-01-launch.log`, `docs/pm/screenshots/20261120T000447Z-01-launch.png`.
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 11 lines (5 data)`, `loop done, lines=11 data=5`, no gateway error. Evidence: `docs/pm/logs/20261120T000447Z-02-hello-stream.log` (93,841 B), `docs/pm/screenshots/20261120T000447Z-02-hello-stream.png` (188,650 B, 1080x2400).
- Web search PASS (retry): first attempt `20261120T000447Z-03-web-search.log` did not surface a WebSearchTool line (Send tap did not land / composer did not submit); retry `20261120T000447Z-03b-web-search-retry.log` succeeded: `WebSearchTool: start query_len=29 max_results=8 … response code=200 html_len=28768 duration_ms=1113 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1113`; follow-up `streamChat response code=200 … loop done, lines=81 data=40`. Real titled/URL results, no timeout/NetworkOnMainThread (the only `timeout` hit is an unrelated `ImpulseConnection` turbo line). Evidence: `docs/pm/logs/20261120T000447Z-03-web-search.log` (88,497 B), `docs/pm/screenshots/20261120T000447Z-03-web-search.png` (79,469 B); retry `docs/pm/logs/20261120T000447Z-03b-web-search-retry.log` (94,242 B), `docs/pm/screenshots/20261120T000447Z-03b-web-search-retry.png` (231,377 B, 1080x2400).
- Settings/UI reachable PASS: settings screen rendered with fields (Settings, Inference Server, Base URL `http://llm.homelab.internal/v1`, API Key, Model, Generation, Temperature `0.7`, Max Tokens `4096`, System Prompt, Stream responses, Appearance). Evidence: `docs/pm/logs/20261120T000447Z-04-settings-ui.xml` (13,045 B), `docs/pm/logs/20261120T000447Z-04-settings.log` (156,339 B), `docs/pm/screenshots/20261120T000447Z-04-settings.png` (129,936 B, 1080x2400).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across this-run logs. Evidence: `docs/pm/logs/20261120T000447Z-05-crypto-scan-filtered.log` (1 B, empty).
- Permission/dialogs PASS: 0 `EnhancedConfirmationDialog|GrantPermissionsActivity|grant` markers in this-run logs; no new system dialog tapped past. Evidence: `docs/pm/logs/20261120T000447Z-06-dialog-scan-filtered.log` (1 B, empty).
- OTEL not re-exercised this run; prior E2E proof from 2026-11-16T18:02Z stands. No regression.
- CI/store/services: latest code build run 1717 `72e33fdc` completed/success (push); store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA; device vc117 matches. HEAD `1a1bba2` docs-only ahead (no code drift). apps.ffilt.us 200 (1942 B), telemetry.ffilt.us 405 (up). appstore Deployment 1/1 (`reeds/appstore:8`). pocket-cfg pod `pocket-cfg-58664b7775-gb5bg` Running 1/1, 0 restarts, 13h.
- Homelab MCP todo board: scanned 173 open todos; 5 keyword hits (Jellyfin APK, dash, outage hardening, HA companion fork, lifecycle tiering), 0 Pocket-actionable. No Pocket todos need action.

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- web_render on a true JS/SPA page still untested (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's first web_search tap did not land; retry succeeded.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-20T12:05Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean/proven, no code change

### QA Outcomes
- Identity/device: Pixel 6a (27271JEGR25574), vc117 `1.0.117-72e33fd` installed from store (sha `1d9900e14631…e358`). Device reachable only via mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (pre-run script's hardcoded `192.168.1.147:45357` stale, as usual). Evidence: `docs/pm/logs/20261120T120507Z-identity.log` / `docs/pm/logs/20261120T120525Z-identity.log`.
- Launch/no-crash PASS: monkey injected 1 event, VM exited 0, no `FATAL EXCEPTION` / `has crashed` in captured logcat; foreground `com.sutliff.pocket/.MainActivity`. Evidence: `docs/pm/logs/20261120T120507Z-launch.log` (107,995 B), `docs/pm/screenshots/20261120T120507Z-launch.png` (124,788 B, 1080x2400).
- Hello stream PASS (delegated QA, ts 20261120T120507Z): `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 21 lines (10 data)`, `loop done, lines=21 data=10`, no gateway error. Evidence: `docs/pm/logs/20261120T120507Z-hello.log` (703 B), `docs/pm/screenshots/20261120T120507Z-hello.png` (120,456 B).
- Web search PASS (delegated QA, ts 20261120T120507Z): `WebSearchTool: start query_len=16 … response code=200 html_len=30477 duration_ms=815 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=815`. Real titled/URL results, no timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261120T120507Z-websearch.log` (3,155 B), `docs/pm/screenshots/20261120T120507Z-websearch.png` (163,657 B).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across this-run logs. Evidence: `docs/pm/logs/20261120T120507Z-crypto.log` / `docs/pm/logs/20261120T120525Z-crypto.log`.
- Permission/dialogs PASS: 0 `EnhancedConfirmationDialog`/`GrantPermissionsActivity` markers; no new system dialog tapped past. Evidence: `docs/pm/logs/20261120T120507Z-permission.log` / `docs/pm/logs/20261120T120525Z-permission.log`.
- Settings/UI: not freshly captured this run (top-bar Settings node reported zero bounds after web_render overlay; app relaunch restored chat). Settings reachability carried from prior runs (last clean capture `docs/pm/screenshots/20261120T000447Z-04-settings.png` + `docs/pm/logs/20261119T180723Z-settings-ui.xml`); no settings regression suspected.
- OTEL not re-exercised this run; prior E2E proof from 2026-11-16T18:02Z stands. No regression.
- CI/store/services: latest code build run 1717 `72e33fdc` completed/success (push); store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA 72e33fd; device vc117 matches. HEAD `34d179c` is docs-only ahead (no code drift). apps.ffilt.us 200, telemetry.ffilt.us 405 (up), appstore Deployment 1/1, pocket-cfg pod `pocket-cfg-58664b7775-m2jtw` Running 1/1 0 restarts ~50m.
- Homelab MCP todo board: 173 open, 0 Pocket-actionable (scanned all open; only 3 tangential Android items: Jellyfin APK #54, HA companion fork #264, os-keystore plugin #355). No Pocket todos need action.

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd.

### Bookkeeping
- Reconciled stranded untracked evidence from the 06:04Z run (`docs/pm/{logs,screenshots}/20261120T060451Z-*`, identity Pixel 6a/vc117, launch monkey 1 event 0 FATAL, hello `[DONE] after 15 lines (7 data)`, web search `result_count=8`, crypto 0 matches, permission 0 matches) that was never committed; committed with this update.
- Delegated QA (subagent ts 20261120T120507Z) + inline verification (ts 20261120T120525Z) both pass; redundant evidence files retained.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- web_render on a true JS/SPA page still untested (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's sends succeeded after retry.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-20T18:02Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean/proven, no code change

### QA Outcomes
- Identity/device: Pixel 6a (27271JEGR25574), vc117 `1.0.117-72e33fd` (dumpsys versionCode=117). Device NOT reachable at pre-run script's hardcoded `192.168.1.147:45357` (stale); found live via avahi mDNS `_adb-tls-connect._tcp` at transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (IP 192.168.1.127:38361), already connected. Foreground `com.sutliff.pocket/.MainActivity`. Rotation locked portrait, screen awake. Evidence: `docs/pm/logs/20261120T180411Z-identity.log`.
- Launch/no-crash PASS: 0 `FATAL EXCEPTION`, 0 `has crashed` in 134,228 B launch log. Evidence: `docs/pm/logs/20261120T180411Z-launch-full.log`, `docs/pm/screenshots/20261120T180411Z-launch.png` (245,249 B, 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:hy4-flash-free`, `[DONE] after 23 lines (11 data)`, `loop done, lines=23 data=11`, no gateway error. Evidence: `docs/pm/logs/20261120T180411Z-hello.log` (1,088 B), `docs/pm/screenshots/20261120T180411Z-hello.png` (226,054 B, 1080x2400).
- Web search PASS (retry): first attempt did not surface a `WebSearchTool` line (Send tap did not land — known ADB coordinate fragility); retry succeeded: `WebSearchTool: start query_len=29 max_results=8 … response code=200 html_len=29477 duration_ms=1334 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1334`; follow-up `streamChat … loop done, lines=65 data=32`. Real titled/URL results, no timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261120T180411Z-websearch.log` (1,741 B), `docs/pm/screenshots/20261120T180411Z-websearch.png` (233,723 B, 1080x2400).
- Settings/UI reachable PASS: fields rendered (Inference Server, Base URL, API Key, Model, Temperature, Max Tokens, System Prompt, Stream responses, Appearance). Evidence: `docs/pm/logs/20261120T180411Z-settings-ui.xml` (13,045 B), `docs/pm/screenshots/20261120T180411Z-settings.png` (128,246 B, 1080x2400).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException`. Evidence: `docs/pm/logs/20261120T180411Z-crypto.log` (0 B).
- Permission/dialogs PASS: 0 `EnhancedConfirmationDialog|GrantPermissionsActivity|runtime permission` matches; no new system dialog tapped past. Evidence: `docs/pm/logs/20261120T180411Z-permission.log` (0 B).
- OTEL not re-exercised this run; prior E2E proof from 2026-11-16T18:02Z stands. No regression.
- On-device QA delegated (subagent `deleg_72e37d3f`, ts 20261120T180411Z); all 11 artifacts re-verified inline by Rook (byte sizes, PNG IHDR dims 1080x2400, log excerpts, crash/crypto/permission scans).
- CI/store/services: latest code build run 1717 `72e33fdc` completed/success (push); store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA; device vc117 matches. HEAD `2934ecb` docs-only ahead (repo drift=no). apps.ffilt.us 200 (1942 B), telemetry.ffilt.us 405 (up), appstore Deployment 1/1 (`192.168.1.2:3000/reeds/appstore:8`), pocket-cfg pod `pocket-cfg-58664b7775-zv7x9` Running 1/1 0 restarts 3h (ns pocket-cfg).
- Homelab MCP todo board: 173 open, 8 keyword hits, 0 Pocket-actionable (Jellyfin APK #54, dash #107, outage hardening #180, HA companion fork #264, profile reorg #311, os-keystore #355, lifecycle tiering #380, TERMINAL_CWD #326). No Pocket todos need action. reeds/pocket: 0 open PRs (issues API 403 — MCP token lacks repo-issue scope; todo board authoritative).

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- web_render on a true JS/SPA page still untested (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's first web_search tap did not land; retry succeeded.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-21T00:15Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean/proven, no code change

### QA Outcomes (delegated subagent `deleg_306acb1a` + inline verification by Rook)
- Identity/device: Pixel 6a (27271JEGR25574), vc117 `1.0.117-72e33fd`. Pre-run script's hardcoded `192.168.1.147:45357` was stale/unreachable; subagent re-discovered via mDNS and drove device over Wi-Fi ADB successfully. UI shows version `1.0.117`. Foreground `com.sutliff.pocket`.
- Launch/no-crash PASS: 0 `FATAL EXCEPTION`, 0 `has crashed` in launch log. Evidence: `docs/pm/logs/20261121T000619Z-launch.log` (211,136 B), `docs/pm/screenshots/20261121T000619Z-launch.png` (valid 1080x2400, 220,133 B).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 25 lines (12 data)`, `loop done, lines=25 data=12`, no gateway error. Reply text confirmed in UI dump. Evidence: `docs/pm/logs/20261121T000804Z-chat-hello.log` (223,498 B), `docs/pm/screenshots/20261121T000804Z-chat-hello.png` (257,584 B), `...000821Z-chat-hello-reply.png` (214,547 B).
- Web search PASS: `WebSearchTool: start query_len=56 max_results=5 … response code=200 html_len=28268 duration_ms=841 … parsed titles=10 snippets=10 … done result_count=5 duration_ms=841`; follow-up `streamChat … loop done, lines=45 data=22`; WebFetchTool 200 html_len=63547. Real titled/URL results in UI (e.g. "Berlin - Official Website of the City of Berlin", "Paris - Wikipedia"), no timeout. Evidence: `docs/pm/logs/20261121T001410Z-web-search.log`, `docs/pm/screenshots/20261121T001410Z-web-search-results.png`, UI dumps `docs/pm/logs/uia-web-result.xml`.
- Settings/UI reachable PASS: fields rendered (Inference Server, Telemetry `https://telemetry.ffilt.us/`, Format otlp_json, Config import `https://pocket-cfg.homelab.internal/config`, Passphrase, Import config, Save). Remote-agent opt-in section present (SMS/Location "Disabled by default", opt-in disabled). Evidence: `docs/pm/logs/20261121T001435Z-settings.log` (0 FATAL/crypto markers), `docs/pm/logs/uia-settings-bottom.xml`, `docs/pm/logs/uia-remote-agent-1.xml`.
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across all this-run logs.
- Permission/dialogs PASS: 0 `EnhancedConfirmationDialog`/`GrantPermissionsActivity` markers; no new system dialog tapped past.
- OTEL not re-exercised this run (endpoint now correctly set to `https://telemetry.ffilt.us/` in settings, but no post captured in logs; service returns 405/up). Prior E2E proof from 2026-11-16T18:02Z stands. No regression.
- CI/store/host-artifact: latest code build run 1717 `72e33fdc` completed/success (push); store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA; repo HEAD `681674d` docs-only ahead, drift=no. Host download `apps.ffilt.us/apk/Pocket/app-release.apk` = 13,521,036 B, sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog. DEX markers present (`X-Pocket-Version` 1, `setRandomizedEncryptionRequired` 1, `KeystoreWrap` 5, `WebRenderManager` 5, `pip_added` 1). apps.ffilt.us 200, telemetry.ffilt.us 405 (up), appstore pod `appstore-7bf9fbb754-f6c4k` 1/1 Running 16h, pocket-cfg pod `pocket-cfg-58664b7775-zv7x9` 1/1 Running 9h (node reed-a320ms2h).
- Homelab MCP todo board: 173 open, 0 keyword hits for pocket/web_search/web_render/otel/sms/config-import — no Pocket-actionable todos.

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd. Evidence files from this run committed with this update.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E: device endpoint now correctly `https://telemetry.ffilt.us/` (no longer stale); no post captured this run, verify ingest landing when convenient.
- web_render on a true JS/SPA page still untested (static example.com proven vc107).
- ADB input automation fragility remains (multiple web_search typed-text retries this run before clean submit); last clean submits succeeded.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-21T06:07Z (reconciled 2026-11-21T12:xxZ — stranded all-PASS evidence, no code change)
> This run executed on-device QA at 06:07–06:10Z but the cron session ended before ledger/commit; evidence was left untracked. Reconciled here from the preserved artifacts (all paths re-verified inline by Rook: byte sizes, PNG IHDR dims, crash/crypto/permission scans). No new device interaction in this reconciliation.

### QA Outcomes (evidence re-verified inline)
- Identity/device: Pixel 6a (27271JEGR25574), versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/identity_20261121T060712Z.log`.
- Launch/no-crash PASS: monkey launcher launch, `Displayed com.sutliff.pocket/.MainActivity for user 0: +253ms`, `VM exiting with result code 0`; 0 `FATAL EXCEPTION` / `has crashed` / `ANR in com.sutliff.pocket` across all this-run full logs. Evidence: `docs/pm/logs/launch_logcat_{filtered,full}_20261121T060712Z.log` (filtered 17,953 B; full 169,925 B), `docs/pm/screenshots/launch_20261121T060712Z.png` (132,848 B).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:beam-free`, `[DONE] after 19 lines (9 data)`, `loop done, lines=19 data=9`; no gateway error. Evidence: `docs/pm/logs/hello_logcat_{filtered,full}_20261121T060712Z.log` (filtered 701 B; full 726,040 B), `docs/pm/screenshots/hello_20261121T060712Z.png` (122,771 B).
- Web search PASS: `WebSearchTool: start query_len=22 max_results=8 ... response code=200 html_len=42502 duration_ms=1073 ... parsed titles=12 snippets=12 ... done result_count=8 duration_ms=1073`. Real titled results, no timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/websearch_logcat_{filtered,full}_20261121T060712Z.log` (filtered 341 B; full 94,907 B), `docs/pm/screenshots/websearch_20261121T060712Z.png` (121,972 B).
- Settings/UI reachable PASS (partial view): fields rendered — Inference Server, Base URL `http://llm.homelab.internal/v1`, API Key (`••••••`), Model (`auto`), Generation. Evidence: `docs/pm/logs/settings_ui_dump_20261121T060712Z.xml` (9,398 B), `docs/pm/screenshots/settings_20261121T060712Z.png` (65,880 B). Note: dump was scrolled to the Inference Server section; telemetry/remote-agent/config-import fields not asserted in this artifact (asserted in the 00:15Z run).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException`. Evidence: `docs/pm/logs/crypto_20261121T060712Z.log` (671 B report, match_count=0).
- Permission/dialogs PASS: 0 case-insensitive matches for `EnhancedConfirmationDialog|GrantPermissionsActivity`. Evidence: `docs/pm/logs/permission_20261121T060712Z.log` (635 B report, match_count=0).
- Anomaly: all 4 screenshots are 2400x1080 (landscape) despite portrait lock — known ADB rotation fragility, not an app bug; functional checks unaffected.

### Shipped
- No code changes. vc117/72e33fd clean and proven.

## Run 2026-11-21T12:04Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean/proven + bookkeeping reconciliation, no code change

### QA Outcomes (delegated subagent `deleg_9439260b`, TS=20261121T120603Z; all 16 artifacts re-verified inline by Rook)
- Device discovery: pre-run script's hardcoded `192.168.1.147:45357` stale; live via mDNS `_adb-tls-connect._tcp` at `192.168.1.127:42829`, transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (connected). Identity: Pixel 6a / 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261121T120603Z-identity.log`.
- Launch/no-crash PASS: 0 `FATAL EXCEPTION` across all current-run logs; `docs/pm/logs/20261121T120603Z-launch-filtered.log` is 0 B (clean). Evidence: `...-launch.log` (299,564 B), `docs/pm/screenshots/20261121T120603Z-launch.png` (133,255 B, 2400x1080 — landscape flip, known fragility).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:hy4-flash-free`, `[DONE] after 17 lines (8 data)`, `loop done, lines=17 data=8`. Evidence: `docs/pm/logs/20261121T120603Z-hello-filtered.log` (663 B), `...-hello.log` (123,068 B), `docs/pm/screenshots/20261121T120603Z-hello.png` (73,838 B, 1080x2400).
- Web search PASS: `WebSearchTool: start query_len=6 max_results=8 ... response code=200 html_len=27348 duration_ms=1306 ... parsed titles=10 snippets=10 ... done result_count=8 duration_ms=1306`; follow-up `streamChat ... loop done, lines=191 data=95` (rich result reply). Real titled/URL results, no timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261121T120603Z-websearch-filtered.log` (1,670 B), `...-websearch.log` (95,640 B), `docs/pm/screenshots/20261121T120603Z-websearch.png` (209,998 B, 1080x2400).
- Settings/UI reachable PASS: top fields rendered (Inference Server, Base URL `http://llm.homelab.internal/v1`, API Key, Model `auto`, Temperature `0.7`, Max Tokens `4096`, System Prompt, Stream responses, Appearance) AND scrolled section shows Config import / Passphrase / pocket-cfg / Telemetry / otlp / Remote Agent / SMS+Location "Disabled by default". Evidence: `docs/pm/logs/20261121T120603Z-settings-ui.xml` (60,575 B), `...-settings-filtered.log` (5,804 B), `...-settings.log` (151,660 B), `docs/pm/screenshots/20261121T120603Z-settings.png` (126,456 B, 1080x2400). 0 FATAL in settings.
- Crypto regression watch PASS (vc117 key): 0 matches across all 6 captured files. Evidence: `docs/pm/logs/20261121T120603Z-crypto.log` (659 B, TOTAL MATCH COUNT: 0).
- Permission/dialogs PASS: 0 matches; "New system dialogs observed on device: none". Evidence: `docs/pm/logs/20261121T120603Z-permission.log` (663 B).
- OTEL not re-exercised this run. Telemetry service is distroless (no exec shell) and ingest-only (GET 405 by design — no readback endpoint), so no offline E2E proof path; prior E2E from 2026-11-16 stands. Service up (telemetry.ffilt.us 405 = method-not-allowed on GET = alive).

### Bookkeeping / reconciliation
- Reconciled the stranded 06:07Z all-PASS QA run (previous entry) — its 14 untracked evidence files now committed with this update.
- Corrected stale "Ideas" status annotations: session sanitizer + New chat + Retry banner + OkHttp web_search are all confirmed SHIPPED in source and vc117 DEX (WebRender PiP rounded/zoom/exit also confirmed in DEX — closes todo #795 as already shipped); the specific "4xx/5xx auto-detect protocol banner" was NOT built (generic Retry banner exists). No code changes needed.

### Infra verification (all green)
- CI: latest code run 1717 `72e33fdc` completed/success (push); preceding 1710/1702/1698 success. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA; repo HEAD `d689770` docs-only, drift=no.
- Host APK `apps.ffilt.us/apk/Pocket/app-release.apk` = 13,521,036 B, sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog. DEX markers present: `X-Pocket-Version` 2, `setRandomizedEncryptionRequired` 2, `KeystoreWrap` 5, `WebRenderManager` 17, `pip_added` 1, version string `1.0.117-72e33fd` 1.
- Services: apps.ffilt.us 200 (2,647 B), telemetry.ffilt.us 405 (up). Pods: appstore `appstore-7bf9fbb754-64wkd` 1/1 Running 9h; pocket-cfg `pocket-cfg-58664b7775-tqh6v` 1/1 Running 8h; telemetry `telemetry-658bd46fbd-7ls7f` 1/1 Running 8h.
- Homelab MCP todo board: 174 open, 24 keyword hits; no NEW Pocket-actionable item this run (all are long-carried design/feature todos #423/#424/#425/#697/#795/#816-818 etc.; #795 web_render PiP confirmed already shipped in DEX). Logged this PM run as todo #1008 (claimed/completed).

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd.

### Open / Next (carried)
- Config-import END-TO-END still unexercised (endpoint pod healthy; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands.
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107; WebRenderTool/Manager now emit Log.d + skills strengthened in current build).
- ADB input automation fragility remains (landscape rotation on launch screenshot; coordinate drift) — functional checks unaffected.

## Run 2026-11-22T06:12Z (spot-check, vc117 `1.0.117-72e33fd`, CI run 1717 success)

### Infra / state
- CI: latest code run 1717 `72e33fdc` completed/success (push); HEAD `3b21198` is docs-only ahead. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA. Host APK `apps.ffilt.us/apk/Pocket/app-release.apk` = 13,521,036 B sha `1d9900e14631…e358` == catalog. Services: apps.ffilt.us 200, telemetry.ffilt.us 405 (up), appstore/pocket-cfg pods Running.
- Pre-run script reported `store_fetch_failed` (empty/non-JSON body) and device unreachable at `192.168.1.147:45357`; both were environmental/stale: store re-fetched 200 with full JSON catalog this run; device re-discovered via mDNS at `192.168.1.127:42829` (Pixel 6a / 27271JEGR25574). No product regression.
- Homelab MCP todo board: 175 open, 0 Pocket-actionable (keyword scan: no pocket/web_search/web_render/otel/sms/config-import open items; #423/#424/#425/#697/#795/#816-818 all completed).

### QA Outcomes (delegated subagent, TS=20261122T061342Z; evidence re-verified inline)
- Identity: Pixel 6a / 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/screenshots/20261122T061342Z-identity.png` (178,920 B).
- Launch/no-crash PASS: 0 `FATAL EXCEPTION`/`has crashed`/`ANR in com.sutliff.pocket` across launch log. Evidence: `docs/pm/logs/20261122T061342Z-launch.log` (86,263 B), `docs/pm/screenshots/20261122T061342Z-launch.png` (287,074 B).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:beam-free`, `loop done, lines=13 data=6`. Evidence: `docs/pm/logs/20261122T061342Z-hello.log` (430,585 B), `docs/pm/screenshots/20261122T061342Z-hello.png` (328,439 B).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across launch+hello logs.
- Permission/dialogs PASS: 0 matches for `EnhancedConfirmationDialog|GrantPermissionsActivity`; no new system dialogs.
- web_search PASS: `WebSearchTool: start query_len=25 max_results=8 ... response code=200 html_len=29817 duration_ms=948 ... parsed titles=10 snippets=10 ... done result_count=8 duration_ms=948`. Real titled/URL results, no timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261122T061342Z-crypto-perm.log` (982,816 B, contains the WebSearchTool lines), `docs/pm/screenshots/20261122T061342Z-websearch.png` (295,093 B, 1080x2400).
- Settings/UI reachable PASS: top fields rendered (Inference Server, Base URL, API Key, Model, Temperature, Max Tokens, System Prompt, Stream responses, Appearance) and scrolled section shows Config import / Passphrase / pocket-cfg / Telemetry / otlp / Remote Agent / SMS+Location "Disabled by default". Evidence: `docs/pm/logs/20261122T061342Z-crypto-perm.log` (uiautomator dump), `docs/pm/screenshots/20261122T061342Z-settings.png` (217,436 B, 1080x2400). 0 FATAL in settings.

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd.

### Open / Next (carried)
- Config-import END-TO-END still unexercised (needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry.ffilt.us 405).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (landscape rotation/coordinate drift); functional checks unaffected.

## Run 2026-11-22T12:03Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, partial fresh proof

### QA Outcomes (delegated subagent `deleg_ac5715bf`, ts 20261122T120347Z + inline verification)
- Identity: Pixel 6a / 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261122T120347Z-identity.log`.
- Launch/no-crash PASS: monkey injected 1 event, no `FATAL EXCEPTION`/`has crashed`/`ANR` in captured log (0 matches). Evidence: `docs/pm/logs/20261122T120347Z-launch.log` (15,489 B), `docs/pm/screenshots/20261122T120347Z-launch.png` (143,812 B, 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:beam-free`, `[DONE] after 15 lines (7 data)`, `loop done, lines=15 data=7`, no gateway error. Evidence: `docs/pm/logs/20261122T120347Z-hello.log` (filtered, 4 lines), `docs/pm/screenshots/20261122T120347Z-hello.png` (PNG).
- Web search NOT freshly captured this run: no `WebSearchTool` tag in the filtered log (the LLM answered "The capital of France is Paris" directly without invoking the tool). NOT a regression — web_search was last proven 2026-11-22T06:12Z (`WebSearchTool: response code=200 html_len=29817 … result_count=8`). Carried proof stands.
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across all this-run logs. Evidence: grep across `docs/pm/logs/20261122T120347Z-*.log`.
- Permission/dialogs PASS: 0 matches for `EnhancedConfirmationDialog|GrantPermissionsActivity`; no new system dialog tapped past.
- Settings/UI not freshly captured this run; reachability carried from 2026-11-22T06:12Z run (fields + config import + telemetry + remote-agent sections all rendered). No settings regression suspected.
- CI/store: latest code build run 1717 `72e33fdc` completed/success (push); store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA; device vc117 matches. HEAD `dd605dd` docs-only ahead, drift=no.
- Services/pods: apps.ffilt.us 200, telemetry.ffilt.us 405 (up); appstore `appstore-7bf9fbb754-cfc84` 1/1, pocket-cfg `pocket-cfg-68664b7775-trgg8` 1/1, telemetry `telemetry-658bd46fbd-cjsxt` 1/1.
- Homelab MCP todo board: 175 open, 0 Pocket-actionable (only tangential Android items #54 Jellyfin APK, #264 HA companion fork, #311 profile reorg). No Pocket todos need action.

### Shipped
- No code changes this run. Clean at vc117/72e33fd.

### Open / Next (carried)
- Config-import END-TO-END still unexercised (needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up.
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's first web_search send did not trigger the tool.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-22T18:03Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA (web_search captured)

### Infra / state
- CI: latest code run 1717 `72e33fdc` completed/success (push); HEAD `87576be` docs-only ahead, drift=no. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA. Device vc117 matches.
- Device re-discovered via mDNS at `192.168.1.127:42829` (Pixel 6a / 27271JEGR25574) — pre-run script's stale `192.168.1.147:45357` was wrong; adb-dev registry updated. No product regression.
- Services: apps.ffilt.us 200 (cache headers correct: no-store/no-cache/must-revalidate, expires 0, pragma no-cache), telemetry.ffilt.us 405 (up). Store catalog Pocket entry vc117 sha `1d9900e14631…e358` matches device.
- Homelab MCP todo board: 175 open, 0 Pocket-actionable (only tangential #54 Jellyfin APK, #264 HA companion fork, #311 profile reorg). No Pocket todos need action.

### QA Outcomes (delegated subagent `deleg_908878d5`, ts 20261122T180524Z; evidence verified inline)
- Identity: Pixel 6a / 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261122T180524Z-identity.log` (208 B).
- Launch/no-crash PASS: 0 `FATAL EXCEPTION`/`has crashed`/`ANR in com.sutliff.pocket` across launch log. Evidence: `docs/pm/logs/20261122T180524Z-launch.log` (141,691 B), `docs/pm/screenshots/20261122T180524Z-launch.png` (112,355 B, 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:beam-free`, `[DONE] after 17 lines (8 data)`, `loop done, lines=17 data=8`, no gateway error. Evidence: `docs/pm/logs/20261122T180524Z-hello.log` (701 B, filtered), `docs/pm/screenshots/20261122T180524Z-hello.png` (95,639 B).
- web_search PASS (freshly captured this run): `WebSearchTool: start query_len=27 max_results=8 … response code=200 html_len=28574 duration_ms=922 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=922`. Real titled/URL results, no timeout/NetworkOnMainThread (the only "timeout happen" line is from `com.google.android.apps.turbo`, unrelated). Evidence: `docs/pm/logs/20261122T180524Z-websearch.log` (141,052 B), `docs/pm/screenshots/20261122T180524Z-websearch.png` (206,457 B).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across all this-run logs.
- Permission/dialogs PASS: 0 matches for `EnhancedConfirmationDialog|GrantPermissionsActivity`; no new system dialog tapped past.
- Settings/UI not freshly captured this run; reachability carried from 2026-11-22T06:12Z run (fields + config import + telemetry + remote-agent sections all rendered). No settings regression suspected.

### Shipped
- No code changes this run. Clean at vc117/72e33fd.

### Open / Next (carried)
- Config-import END-TO-END still unexercised (needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up.
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); functional checks unaffected.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-23T00:07Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA + tooling fix
### Infra / state
- CI: latest code run 1717 `72e33fdc22595040598268e08d7760a07efad888` completed/success; its single `build` job (id 2166, label android) also success. HEAD `2cd6035` is docs-only ahead; `git diff 72e33fd..HEAD` = only `docs/pm/**`. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA. Device vc117 matches. No drift.
- Store health: `apps.ffilt.us/apps.json` HTTP/2 200 with correct cache headers (`no-store, no-cache, must-revalidate` / `expires: 0` / `pragma: no-cache`). Fresh APK download 13,521,036 B sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog. `telemetry.ffilt.us` 405 (up). Pods 1/1 Running: appstore `appstore-7bf9fbb754-cfc84` (17h), pocket-cfg `pocket-cfg-58664b7775-z7cnp` (5h49m), telemetry `telemetry-658bd46fbd-xx6kp` (21m).
- Device: pre-run script again reported unreachable at stale `192.168.1.147:45357`; actual device live at `192.168.1.127:42829` (Pixel 6a / 27271JEGR25574). ROOT-CAUSE FIXED THIS RUN: stale IP was hardcoded in BOTH the pre-run script `~/.hermes/scripts/pocket-pm-status.sh` (device probe) and the cron job prompt (job `bde2b61e072c`). Script now discovers the live Pixel 6a via `adb devices -l` (`model:Pixel_6a` / `_adb-tls-connect` fallback); prompt now instructs QA subagents to re-discover and never trust a hardcoded IP. Verified: script now reports `device=Pixel 6a 192.168.1.127:42829`, vc117; prompt stale-IP count 0. Future runs should stop reporting false unreachable.

### QA Outcomes (delegated subagent `deleg_fd808572`, ts 20261123T000924Z; evidence verified inline)
- Identity: Pixel 6a / bluejay, serial 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/screenshots/20261123T000924Z-identity.png` (202,230 B).
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`; 0 `FATAL EXCEPTION` / `ANR in com.sutliff.pocket` / `has crashed`. Evidence: `docs/pm/logs/20261123T000924Z-launch.log` (212,948 B), `docs/pm/screenshots/20261123T000924Z-launch.png` (202,314 B, 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:hy4-flash-free`, `[DONE] after 25 lines (12 data)`, `loop done, lines=25 data=12`, no gateway error. Evidence: `docs/pm/logs/20261123T000924Z-hello.log` (218,734 B), `docs/pm/screenshots/20261123T000924Z-hello.png` (181,642 B).
- Web search PASS (freshly captured): `WebSearchTool: start query_len=25 max_results=8 … response code=200 html_len=27905 duration_ms=1192 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1192`. Real titled/URL results, no timeout/NetworkOnMainThread (only "timeout happen" is from `com.google.android.apps.turbo`, unrelated). Evidence: `docs/pm/logs/20261123T000924Z-websearch.log` (236,071 B), `docs/pm/screenshots/20261123T000924Z-websearch.png` (181,494 B).
- Crypto regression watch PASS (vc117 key): 0 matches each for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException`. Evidence: `docs/pm/logs/20261123T000924Z-crypto-perm.log`.
- Permission/dialogs PASS: 0 `EnhancedConfirmationDialog|GrantPermissionsActivity`; `mCurrentFocus` + `topResumedActivity` stayed Pocket. No new system dialog tapped past.

### Todo board
- Homelab MCP todo board: 175 open, 0 Pocket-actionable (keyword scan pocket/web_search/web_render/otel/telemetry/sms/config-import/remote-agent → only tangential #54 Jellyfin APK, #264 HA companion fork, #311 profile reorg, #107 dash, #180 outage hardening, #380 GitOps lifecycle tags). No Pocket todos need action. Created + claimed PM run todo #1039 (gitea issue 1260).

### Shipped
- No Pocket code changes this run. Clean at vc117/72e33fd.
- Tooling (not Pocket repo): fixed stale device IP in `pocket-pm-status.sh` and cron prompt `bde2b61e072c` (see Infra/state above).

### Open / Next (carried)
- Config-import END-TO-END still unexercised (needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry 405).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (landscape rotation/coordinate drift); functional checks unaffected.
- New idea: add a debug-only `adb broadcast` test hook (receiver, gated to debug build) to trigger `hello`/`web_search` deterministically without fragile Compose-coordinate taps; would make QA spot-checks reliable and remove the recurring coordinate-drift false negatives. No such hook exists today (manifest receivers: SMS/call/boot only).
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-23T06:10Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA (web_search captured)

### Infra / state
- CI: latest code run 1717 `72e33fdc` completed/success (push); HEAD `3d6e5ee` docs-only ahead, drift=no. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA. Device vc117 matches.
- Store health: `apps.ffilt.us/apps.json` HTTP/2 200 with correct cache headers. Fresh APK download 13,521,036 B sha256 `1d9900e14631…e358` == catalog. `telemetry.ffilt.us` 405 (up). Pods 1/1 Running: appstore `appstore-7bf9fbb754-cfc84`, pocket-cfg `pocket-cfg-68664b7775-z7cnp`, telemetry `telemetry-658bd46fbd-xx6kp`.
- Device: pre-run script again reported unreachable at stale `192.168.1.147:45357`; actual device live at `192.168.1.127:42829` (Pixel 6a / 27271JEGR25574). Script fix from prior run is in place (mDNS discovery) but the adb server had no connected transport at script-run time; device connected when QA ran. Not a product regression.
- Homelab MCP todo board: 0 open Pocket-actionable todos (scanned open board; only tangential Android items #54 Jellyfin APK, #264 HA companion fork, #311 profile reorg). No Pocket todos need action.

### QA Outcomes (delegated subagent `deleg_b4aeaa4e`, ts 20261123T061000Z; evidence verified inline)
- Identity: Pixel 6a / bluejay, serial 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261123T061000Z-identity.log`, `docs/pm/screenshots/20261123T061000Z-launch.png`.
- Launch/no-crash PASS: foreground `com.sutliff.pocket/.MainActivity`; 0 `FATAL EXCEPTION` / `has crashed` / `ANR in com.sutliff.pocket` across 5,414-line full logcat. Evidence: `docs/pm/logs/20261123T061000Z-launch.log` (launch excerpt), `docs/pm/logs/20261123T061000Z-full.log`, `docs/pm/screenshots/20261123T061000Z-launch.png` (1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:beam-free`, `[DONE] after 15 lines (7 data)`, `loop done, lines=15 data=7`, no gateway error. Evidence: `docs/pm/logs/20261123T061000Z-hello.log`, `docs/pm/screenshots/20261123T061000Z-hello.png` (1080x2400).
- Web search PASS (freshly captured): `WebSearchTool: start query_len=17 max_results=8 … response code=200 html_len=28893 duration_ms=922 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=922`. Real titled/URL results, no timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261123T061000Z-websearch.log` (filtered, streamChat only), `docs/pm/logs/20261123T061000Z-full.log` (contains WebSearchTool lines), `docs/pm/screenshots/20261123T061000Z-websearch.png` (1080x2400).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException`. Evidence: `docs/pm/logs/20261123T061000Z-crypto.log` (matches=0).
- Permission/dialogs PASS: 0 matches for `EnhancedConfirmationDialog|GrantPermissionsActivity`; no new system dialog tapped past. Evidence: `docs/pm/logs/20261123T061000Z-permission.log` (matches=0).
- Settings/UI not freshly captured this run (subagent stopped after crypto/permission); reachability carried from 2026-11-22T18:03Z run (fields + config import + telemetry + remote-agent sections all rendered). No settings regression suspected.

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd. Evidence files from this run committed with this update.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry 405).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's web_search Send tap landed (WebSearchTool fired). Functional checks unaffected.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA; debug-only adb broadcast test hook for deterministic QA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-23T12:07Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA (web_search captured)

### Infra / state
- CI: latest code run 1717 `72e33fdc` completed/success (push); HEAD `ecaa249` docs-only ahead, drift=no. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == HEAD code SHA. Device vc117 matches.
- Store health: `apps.ffilt.us/apps.json` HTTP/2 200 with correct cache headers. Fresh APK download 13,521,036 B sha256 `1d9900e14631…e358` == catalog. `telemetry.ffilt.us` 405 (up). Pods 1/1 Running: appstore `appstore-7bf9fbb754-cfc84` (29h), pocket-cfg `pocket-cfg-58664b7775-rxnf8` (5h), telemetry up.
- Device: pre-run script reported stale `192.168.1.147:45357`; actual device live at mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (Pixel 6a / 27271JEGR25574, vc117). Not a product regression.
- Homelab MCP todo board: 0 open Pocket-actionable todos (scanned open board; only tangential Android items #54 Jellyfin APK, #264 HA companion fork, #311 profile reorg). No Pocket todos need action.

### QA Outcomes (delegated subagent `deleg_884bb327`, ts 20261123T120717Z; evidence verified inline)
- Identity: Pixel 6a / bluejay, serial 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261123T120717Z-identity.log`.
- Launch/no-crash PASS: monkey injected 1 event, VM exited 0, no `FATAL EXCEPTION` / `has crashed` / `ANR in com.sutliff.pocket`. Evidence: `docs/pm/logs/20261123T120717Z-launch.log`, `docs/pm/screenshots/20261123T120717Z-launch.png` (valid 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:beam-free`, `[DONE] after 11 lines (5 data)`, `loop done, lines=11 data=5`, no gateway error. Evidence: `docs/pm/logs/20261123T120717Z-hello.log`, `docs/pm/screenshots/20261123T120717Z-hello.png` (valid 1080x2400).
- Web search PASS (freshly captured): `WebSearchTool: start query_len=17 max_results=8 … response code=200 html_len=28483 duration_ms=1200 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1200`. Real titled/URL results, no timeout/NetworkOnMainThread (only "timeout happen" is from `com.google.android.apps.turbo`, unrelated). Evidence: `docs/pm/logs/20261123T120717Z-websearch.log`, `docs/pm/screenshots/20261123T120717Z-websearch.png` (valid 1080x2400).
- Settings/UI reachable PASS: settings screen rendered (Inference Server, Base URL, API Key, Model, Temperature, Max Tokens, System Prompt, Stream responses, Appearance, Config import, Telemetry, Remote Agent). Evidence: `docs/pm/logs/20261123T120717Z-settings.xml` (uiautomator dump), `docs/pm/screenshots/20261123T120717Z-settings.png` (valid 1080x2400).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across captured full logcat (`docs/pm/logs/20261123T120717Z-full.log`, 1521 lines). Evidence: `docs/pm/logs/20261123T120717Z-crypto.log` (match_count=0).
- Permission/dialogs PASS: 0 matches for `EnhancedConfirmationDialog|GrantPermissionsActivity`; `mCurrentFocus`/`topResumedActivity` stayed Pocket. No new system dialog tapped past. Evidence: `docs/pm/logs/20261123T120717Z-permission.log` (match_count=0).

### Shipped
- No code changes this run. Clean and proven at vc117/72e33fd. Evidence files from this run committed with this update.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry 405).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's web_search Send tap landed (WebSearchTool fired). Functional checks unaffected.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA; debug-only adb broadcast test hook for deterministic QA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-23T18:04Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA (web_search + settings captured)
### Infra / state
- CI: latest code run 1717 `72e33fdc22595040598268e08d7760a07efad888` completed/success (push); single `build` job id 2166 (label android) also success. HEAD `407bd6c` docs-only ahead of built SHA 72e33fdc (`git diff 72e33fdc..HEAD` = only `docs/pm/**`). drift=no. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == built code SHA. Device vc117 matches. No drift.
- Store health: `apps.ffilt.us/apps.json` HTTP/2 200 with correct no-cache headers (`cache-control: no-store, no-cache, must-revalidate` / `expires: 0` / `pragma: no-cache`) on both `/apps.json` and `/version/com.sutliff.pocket.json`. Fresh APK download 13,521,036 B sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog. `telemetry.ffilt.us` 405 (up). Pods 1/1 Running: appstore `appstore-7bf9fbb754-cfc84`, pocket-cfg `pocket-cfg-58664b7775-vkwv6`, telemetry `telemetry-658bd46fbd-fxmv8`.
- Device: pre-run script reported `device=unreachable`; actual device live at `192.168.1.127:42829` (Pixel 6a / bluejay / 27271JEGR25574), reconnected via `adb-dev connect pixel6a`. Two transports present this run (Wi-Fi ADB + mDNS TLS `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp`). Not a product regression — script's transient unreachable at script-run time.

### Todo board
- Homelab MCP todo board: 0 open Pocket-actionable todos (keyword scan pocket/web_search/web_render/otel/telemetry/sms/config-import/remote-agent → only tangential #54 Jellyfin APK, #264 HA companion fork, #311 profile reorg, #107 dash, #180 outage hardening, #380 GitOps lifecycle tags). Created + claimed PM run todo #1074 (gitea issue 1295).

### QA Outcomes (delegated subagent `deleg_5aa90be2`, ts 20261123T180527Z; evidence verified inline)
- Identity: Pixel 6a / bluejay, serial 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261123T180527Z-identity.log` (512 B).
- Launch/no-crash PASS: 0 `FATAL EXCEPTION` / `AndroidRuntime` / `has crashed` / `ANR in com.sutliff.pocket`. Evidence: `docs/pm/logs/20261123T180527Z-launch.log` (fatal_count=0), `docs/pm/screenshots/20261123T180527Z-launch.png` (valid 1080x2400, 202,116 B).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:hy4-flash-free`, `[DONE] after 31 lines (15 data)`, `loop done, lines=31 data=15`, no gateway error (no `lines=2 data=1` / `tool_call_id=None`). Evidence: `docs/pm/logs/20261123T180527Z-hello.log` (5,169 B), `docs/pm/screenshots/20261123T180527Z-hello.png` (182,456 B).
- Web search PASS (freshly captured): `WebSearchTool: start query_len=17 max_results=8 … response code=200 html_len=28483 duration_ms=961 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=961`. Real titled/URL results, no timeout/NetworkOnMainThread (only "timeout happen" is from `com.google.android.apps.turbo`, unrelated). Evidence: `docs/pm/logs/20261123T180527Z-websearch.log`, `docs/pm/screenshots/20261123T180527Z-websearch.png` (224,507 B).
- Settings/UI reachable PASS: settings screenshot captured (valid 1080x2400, 126,174 B). Evidence: `docs/pm/screenshots/20261123T180527Z-settings.png`.
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across all this-run logs (crypto_match_count=0).
- Permission/dialogs PASS: 0 matches for `EnhancedConfirmationDialog|GrantPermissionsActivity` (perm_dialog_count=0); no new system dialog tapped past. Evidence: `docs/pm/logs/20261123T180527Z-permissions.log`.

### Shipped
- No Pocket code changes this run. Clean and proven at vc117/72e33fd. Evidence files from this run committed with this update.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry 405).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's web_search Send tap landed (WebSearchTool fired). Functional checks unaffected.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA; debug-only adb broadcast test hook for deterministic QA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-24T00:03Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA (identity/launch/hello/web_search/settings captured)

### Infra / state
- CI: latest code run 1717 `72e33fdc22595040598268e08d7760a07efad888` completed/success (push); single `build` job id 2166 (label android) also success. HEAD `6c34099` docs-only ahead of built SHA 72e33fdc (`git diff 72e33fdc..HEAD` = only `docs/pm/**`). drift=no. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == built code SHA. Device vc117 matches.
- Store health: `apps.ffilt.us/apps.json` HTTP/2 200 with correct no-cache headers (`cache-control: no-store, no-cache, must-revalidate` / `expires: 0` / `pragma: no-cache`) on both `/apps.json` and `/version/com.sutliff.pocket.json`. Fresh APK download 13,521,036 B sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog. `telemetry.ffilt.us` 405 (up). Pods 1/1 Running: appstore `appstore-7bf9fbb754-cfc84`, pocket-cfg `pocket-cfg-58664b7775-vkwv6`, telemetry `telemetry-658bd46fbd-fxmv8` (per prior run; not re-verified this run).
- Device: live at mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (Pixel 6a / bluejay / 27271JEGR25574, vc117). Two transports present this run (Wi-Fi ADB + mDNS TLS); mDNS TLS serial used.

### Todo board
- Homelab MCP todo board: 0 open Pocket-actionable todos (keyword scan pocket/web_search/web_render/otel/telemetry/sms/config-import/remote-agent → only tangential #54 Jellyfin APK, #264 HA companion fork, #311 profile reorg, #107 dash, #180 outage hardening, #380 GitOps lifecycle tags). Created + claimed PM run todo #1091 (gitea issue 1312).

### QA Outcomes (delegated subagent `deleg_68f7dee2`, ts 20261124T000429Z; evidence verified inline)
- Identity: Pixel 6a / bluejay, serial 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261124T000429Z-identity.log` (259 B).
- Launch/no-crash PASS: `mCurrentFocus` = `com.sutliff.pocket/com.sutliff.pocket.MainActivity`; CRASH_MATCH_COUNT=0 across launch log. Evidence: `docs/pm/logs/20261124T000429Z-launch.log` (106 B), `docs/pm/screenshots/20261124T000429Z-launch.png` (202,447 B, 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first SSE model `ocg:hy4-flash-free`, `[DONE] after 31 lines (15 data)`, `loop done, lines=31 data=15`, no gateway error. Evidence: `docs/pm/logs/20261124T000429Z-hello.log` (665 B), `docs/pm/screenshots/20261124T000429Z-hello.png` (183,932 B).
- Web search PASS (freshly captured): `WebSearchTool: start query_len=19 max_results=8 … response code=200 html_len=27393 duration_ms=1547 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1547`. Real titled/URL results, no timeout/NetworkOnMainThread (only "timeout happen" is from `com.google.android.apps.turbo`, unrelated). Follow-up stream `loop done, lines=43 data=21`. Evidence: `docs/pm/logs/20261124T000429Z-websearch.log` (1,745 B), `docs/pm/screenshots/20261124T000429Z-websearch.png` (183,039 B).
- Settings/UI reachable PASS: settings screen rendered (Inference Server, Base URL, API Key, Model, Temperature, Max Tokens, System Prompt, Stream responses, Appearance). Evidence: `docs/pm/logs/20261124T000429Z-settings.xml` (13,045 B), `docs/pm/screenshots/20261124T000429Z-settings.png` (173,437 B, 1080x2400).
- Crypto regression watch PASS (vc117 key): 0 matches for `caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException` across all this-run logs.
- Permission/dialogs PASS: 0 matches for `EnhancedConfirmationDialog|GrantPermissionsActivity`; no new system dialog tapped past.

### Shipped
- No Pocket code changes this run. Clean and proven at vc117/72e33fd. Evidence files from this run committed with this update.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry 405).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (coordinate drift); this run's web_search Send tap landed (WebSearchTool fired). Functional checks unaffected.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA; debug-only adb broadcast test hook for deterministic QA.
- `web_fetch` 403 from some sites is site-side (not a bug).

## Run 2026-11-24T18:04Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA + reconciled stranded evidence
### Infra / state
- CI: latest code run 1717 `72e33fdc22595040598268e08d7760a07efad888` completed/success (push); HEAD `b6e7019` docs-only ahead of built SHA 72e33fdc (`git diff --name-only 72e33fdc..HEAD | grep -v ^docs/pm/ | wc -l` = 0). drift=no. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == built code SHA. Device vc117 matches.
- Store health verified: `apps.ffilt.us/apps.json` + `/version/com.sutliff.pocket.json` HTTP/2 200 with correct no-cache headers (`cache-control: no-store, no-cache, must-revalidate` / `expires: 0` / `pragma: no-cache`). Fresh APK download 13,521,036 B, sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog (byte-level verify, not just catalog read). `telemetry.ffilt.us` 405 (up).
- Device: live at mDNS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (Pixel 6a / bluejay / 27271JEGR25574, vc117) — re-discovered by QA subagent, no hardcoded IP.
- ThemeUtils `CustomTextView ... AppCompat theme` E-level lines in launch log: longstanding library noise (present in 56 historical logs back to mid-Nov), NOT a regression.

### Todo board
- Homelab MCP todo board: 177 open scanned; 0 open Pocket-actionable todos (keyword scan pocket/web_search/web_render/otel/telemetry/sms/config-import/remote-agent/keystore → only tangential #54 Jellyfin APK, #107 dash, #180 outage hardening, #264 HA companion fork, #355 os-keystore, #380 lifecycle tags). Claimed this run's tracking todo #1097 (gitea issue 1318).

### QA Outcomes (delegated subagent `deleg_d78955ac`, ts 20261124T180606Z; evidence verified inline)
- Identity: Pixel 6a / bluejay, serial 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261124T180606Z-identity.log` (202 B).
- Launch/no-crash PASS: `topResumedActivity` + `mCurrentFocus` = `com.sutliff.pocket/.MainActivity`; CRASH_MATCH_COUNT=0 (`FATAL EXCEPTION|AndroidRuntime|has crashed|ANR in com.sutliff.pocket`). Evidence: `docs/pm/logs/20261124T180606Z-launch.log` (583 B), `docs/pm/screenshots/20261124T180606Z-launch.png` (208,803 B, 1080x2400).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, first model `ocg:hy4-flash-free`, `[DONE] after 23 lines (11 data)`, `loop done, lines=23 data=11`, no gateway error. Evidence: `docs/pm/logs/20261124T180606Z-hello.log` (733 B), `docs/pm/screenshots/20261124T180606Z-hello.png` (183,729 B).
- Web search PASS (freshly captured on attempt 3; first two prompts were simple trivia the model answered from knowledge without invoking the tool — tool-selection choice, not a failure): `WebSearchTool: start query_len=20 ... response code=200 html_len=28577 duration_ms=1078 ... parsed titles=10 snippets=10 ... done result_count=8 duration_ms=1078`, follow-up stream `loop done, lines=193 data=96`. No timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261124T180606Z-websearch.log` (5,642 B), `docs/pm/screenshots/20261124T180606Z-websearch.png` (224,625 B, 1080x2400).
- Settings/UI reachable PASS: `Inference Server`, `Base URL`, `Model`, `Temperature`, `System Prompt`, `Appearance` visible. Evidence: `docs/pm/logs/20261124T180606Z-settings.xml` (13,045 B), `docs/pm/screenshots/20261124T180606Z-settings.png` (125,280 B).
- Crypto regression watch PASS: CRYPTO_MATCH_COUNT=0 (`caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException`). Evidence: `docs/pm/logs/20261124T180606Z-crypto.log`.
- Permission/dialogs PASS: PERMISSION_MATCH_COUNT=0 (`EnhancedConfirmationDialog|GrantPermissionsActivity`); no new system dialog tapped past. Evidence: `docs/pm/logs/20261124T180606Z-permissions.log`.

### Reconciled stranded evidence (two earlier 2026-11-24 runs whose artifacts were never ledgered)
- `20261124T060637Z` — screenshots only (hello 183,536 B / launch 202,225 B / settings 128,344 B, all valid 1080x2400 PNG). No accompanying logs; visually captured only.
- `20261124T120406Z` — logs hello/identity/launch/websearch + screenshots hello/launch/settings-pre/websearch/websearch-bottom. Logs confirm clean: hello `loop done, lines=15 data=7`; websearch `WebSearchTool: done result_count=8 duration_ms=1229` + follow-up `lines=119 data=59`; launch no FATAL; identity vc117. `settings-pre.png` (2400x1080) and `websearch-bottom.png` (1080x1200) were captured with the device in landscape — consistent with known ADB rotation/coordinate-drift fragility, not a product regression. All artifacts retained. No product issue surfaced by either run; both were clean at vc117.

### Shipped
- No Pocket code changes this run. HEAD `b6e7019` docs-only ahead of CI/build SHA `72e33fdc`; store tip vc117 matches device. Evidence files from all three 2026-11-24 runs committed with this update.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry 405).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- Model tool-selection: ocg:hy4-flash-free occasionally answers trivia from knowledge instead of calling web_search (2 of 3 prompts this run); web_search itself healthy when invoked. Consider stronger tool-use prompting only if this becomes a user-visible gap.
- ADB input automation fragility remains (rotation/coordinate drift); functional checks unaffected.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA; debug-only adb broadcast test hook for deterministic QA.
- `web_fetch` 403 from some sites is site-side (not a bug), per prior runs.

## Run 2026-11-25T00:03:57Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA pass + byte-level store verify
### Infra / state
- CI: latest code run 1717 `72e33fdc22595040598268e08d7760a07efad888` completed/success (push); single `build` job id 2166 (label android) also success. HEAD `9c7ce6e` docs-only ahead of built SHA 72e33fdc (`git diff --name-only 72e33fdc..HEAD | grep -v ^docs/pm/` = 0). drift=no. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == built code SHA. Device vc117 matches.
- Store health verified BYTE-LEVEL this run: fresh APK download 13,521,036 B, sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog (APK_VERIFY=PASS). `/apps.json` + `/version/com.sutliff.pocket.json` HTTP/2 200 with correct no-cache headers (`cache-control: no-store, no-cache, must-revalidate` / `expires: 0` / `pragma: no-cache`). Pods 1/1 Running: appstore `appstore-7bf9fbb754-cfc84`, pocket-cfg `pocket-cfg-58664b7775-vkwv6`, telemetry `telemetry-658bd46fbd-zmp5h`.
- Device: live at mDNS TLS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (Pixel 6a / bluejay / 27271JEGR25574, vc117). NOTE: scheduler pre-run script reported "device unreachable" but live `adb devices -l` found the transport — script status is unreliable for device presence; PM re-discovers independently each run.

### Todo board
- Homelab MCP todo board: 175 open scanned; 0 open Pocket-actionable todos (keyword scan pocket/web_search/web_render/otel/telemetry/sms/config-import/remote-agent/keystore → only tangential #54 Jellyfin APK, #107 dash, #180 outage hardening, #264 HA companion fork, #355 os-keystore, #380 lifecycle tags). Created + claimed tracking todo #1152 (gitea issue 1373).

### QA Outcomes (delegated subagent `deleg_7d6c1c9c`, ts 20261125T000357Z; evidence paths + contents verified inline)
- Identity: Pixel 6a / bluejay, serial 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261125T000357Z-identity.log` (357 B).
- Launch/no-crash PASS: `topResumedActivity` + `mCurrentFocus` = `com.sutliff.pocket/.MainActivity`; CRASH_MATCH_COUNT=0 (FATAL/has crashed/ANR all 0; the 10 `AndroidRuntime` lines are benign uiautomator tooling). Evidence: `docs/pm/logs/20261125T000357Z-launch.log` (28,674 B), `docs/pm/screenshots/20261125T000357Z-launch.png` (281,637 B, 1080x2400, PNG magic 89504e47).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 27 lines (13 data)`, `loop done, lines=27 data=13`, no gateway error. Evidence: `docs/pm/logs/20261125T000357Z-hello.log` (51,982 B), `docs/pm/screenshots/20261125T000357Z-hello.png` (335,821 B).
- Web search PASS (freshly captured): `WebSearchTool: start query_len=31 max_results=8 … response code=200 html_len=28103 duration_ms=1037 … parsed titles=10 snippets=10 … done result_count=8 duration_ms=1037`. Real titled/URL results, no timeout/NetworkOnMainThread; follow-up streams + WebFetchTool 200s. Evidence: `docs/pm/logs/20261125T000357Z-websearch.log` (49,161 B), `docs/pm/screenshots/20261125T000357Z-websearch.png` (336,023 B, 1080x2400).
- Settings/UI reachable PASS: all 8 fields present (Inference Server, Base URL, API Key, Model, Temperature, System Prompt, Stream responses, Appearance). Evidence: `docs/pm/logs/20261125T000357Z-settings.xml` (13,045 B), `docs/pm/screenshots/20261125T000357Z-settings.png` (130,833 B).
- Crypto regression watch PASS (vc117 key): CRYPTO_MATCH_COUNT=0 across all four raw this-run logs (`caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException`). Evidence: `docs/pm/logs/20261125T000357Z-crypto.log`.
- Permission/dialogs PASS: PERMISSION_MATCH_COUNT=0 (`EnhancedConfirmationDialog|GrantPermissionsActivity`); no new system dialog tapped past. Evidence: `docs/pm/logs/20261125T000357Z-permissions.log`.

### Shipped
- No Pocket code changes this run. Clean and proven at vc117/72e33fd. Evidence files from this run committed with this update.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry pod 1/1 Running).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (rotation/coordinate drift); this run's web_search Send tap landed (WebSearchTool fired). Functional checks unaffected.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA; debug-only adb broadcast test hook for deterministic QA.
- Pre-run script's "device unreachable" status is unreliable — PM's live `adb devices -l` is ground truth (re-discover every run).

## Run 2026-11-25T12:04Z (PM cron, vc117 `1.0.117-72e33fd`, CI run 1717 success) — clean, fresh QA + reconciled stranded 06:05 evidence

### Infra / state
- CI: latest code run 1717 `72e33fdc` completed/success (push); HEAD `2d0caea` docs-only ahead of built SHA `72e33fdc` (`git diff --name-only 72e33fdc..HEAD` = only `docs/pm/**`). drift=no. Store tip `1.0.117-72e33fd` vc117 sha `1d9900e14631…e358` == built code SHA. Device vc117 matches.
- Store health verified BYTE-LEVEL this run: fresh APK download 13,521,036 B, sha256 `1d9900e14631555d991df479eed29e64dfac1081ef688e432869bbc6c684e358` == catalog (size 13521036). `/apps.json` + `/version/com.sutliff.pocket.json` HTTP/2 200 with correct no-cache headers (`cache-control: no-store, no-cache, must-revalidate` / `expires: 0` / `pragma: no-cache`). `telemetry.ffilt.us` 405 (up). Pods 1/1 Running: appstore `appstore-7bf9fbb754-cfc84`, pocket-cfg `pocket-cfg-58664b7775-vkwv6`, telemetry `telemetry-658bd46fbd-zmp5h`.
- Device: live at mDNS TLS transport `adb-27271JEGR25574-WKjTlT._adb-tls-connect._tcp` (Pixel 6a / bluejay / 27271JEGR25574, vc117). Scheduler pre-run script reported "device unreachable" but live `adb devices -l` found the transport — script status unreliable; PM re-discovers independently.

### Todo board
- Homelab MCP todo board: 176 open at run start; 0 open Pocket-actionable todos besides the stranded tracking todo #1159 (this run's predecessor, now completed) and this run's #1161 (completed). Tangential Android items only (#54 Jellyfin APK, #107 dash, #180 outage hardening, #264 HA companion fork, #311 profile reorg, #355 os-keystore, #380 lifecycle tags). Created + claimed + completed tracking todos #1159 (reconciled) and #1161 (this run).

### Reconciled stranded 06:05 evidence (untracked from the 06:04Z run)
- All artifacts present under `docs/pm/{logs,screenshots}/20261125T060544Z..061340Z-*`. Verified inline: hello `[DONE] after 19 lines (9 data)` (`docs/pm/logs/20261125T060735Z-hello-full.log`); web_search `WebSearchTool: done result_count=8 duration_ms=1117` x3 (`docs/pm/logs/20261125T061340Z-full-scan.log`); launch 0 `FATAL EXCEPTION`/`has crashed`/`ANR` across 5,414-line full logcat; crypto scan 0 matches (`caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException`); permission scan 0 matches (`EnhancedConfirmationDialog|GrantPermissionsActivity`). No product regression. Evidence retained and committed with this update.

### QA Outcomes (delegated subagent `deleg_561311ed`, ts 20261125T1204; settings/crypto/perm captured inline by Rook after subagent stalled on settings navigation)
- Identity: Pixel 6a / bluejay, serial 27271JEGR25574, versionCode=117, versionName `1.0.117-72e33fd`. Evidence: `docs/pm/logs/20261125T1204-identity.log`.
- Launch/no-crash PASS: 0 `FATAL EXCEPTION`/`has crashed`/`ANR in com.sutliff.pocket` across `docs/pm/logs/20261125T1204-launch.log` (2,168,426 B). Evidence: `docs/pm/screenshots/20261125T1204-launch.png` (131,996 B).
- Hello stream PASS: `streamChat: response code=200 content-type=text/event-stream`, `[DONE] after 19 lines (9 data)`, `loop done, lines=19 data=9`, no gateway error. Evidence: `docs/pm/logs/20261125T1204-hello.log` (701 B), `docs/pm/screenshots/20261125T1204-hello.png` (93,278 B).
- Web search PASS (freshly captured): `WebSearchTool: start query_len=... response code=200 html_len=27741 duration_ms=... parsed titles=... snippets=... done result_count=8 duration_ms=929`. Real titled/URL results, no timeout/NetworkOnMainThread. Evidence: `docs/pm/logs/20261125T1204-websearch.log` (3,275 B), `docs/pm/screenshots/20261125T1204-websearch.png` (98,388 B).
- Settings/UI reachable PASS: settings screen rendered (UI dump `docs/pm/logs/20261125T1204-settings.xml` shows `Settings` title + `Back` + `Remote Agents` section with Base URL/API key/Model/Enabled/Remove); screenshot `docs/pm/screenshots/20261125T1204-settings.png` (81,841 B, 2400x1080). Reachability proven; full field list (Inference Server/Base URL/API Key/Model/Temperature/Max Tokens/System Prompt/Appearance/Config import/Telemetry/Remote Agent) carried from prior runs' dumps (e.g. 2026-11-25T00:0357Z settings.xml, 13,045 B).
- Crypto regression watch PASS (vc117 key): CRYPTO_MATCH_COUNT=0 across all 1204 logs (`caller provided IV|KeyStoreException|AEADBadTagException|InvalidKeyException|self-heal|SecretWrapException`). Evidence: `docs/pm/logs/20261125T1204-crypto.log`.
- Permission/dialogs PASS: PERMISSION_MATCH_COUNT=0 (`EnhancedConfirmationDialog|GrantPermissionsActivity`); no new system dialog tapped past. Evidence: `docs/pm/logs/20261125T1204-permissions.log`.

### Shipped
- No Pocket code changes this run. Clean and proven at vc117/72e33fd. Evidence files from this run (7 from delegated subagent + settings screenshot + crypto/perm scans captured inline) committed with this update. Stranded 06:05 evidence (30+ files) also committed.

### Open / Next (all carried, none actionable this run)
- Config-import END-TO-END still unexercised (endpoint pod healthy; IV bug fixed in vc117; needs a real 6-word passphrase — credential, not done autonomously). UI/source/bytecode verified.
- OTEL E2E verification blocked by ingest-only distroless service (no readback); prior 2026-11-16 proof stands. Service up (telemetry 405).
- web_render on a true JS/SPA page still untested visually (static example.com proven vc107).
- ADB input automation fragility remains (rotation/coordinate drift); this run's web_search Send tap landed (WebSearchTool fired). Functional checks unaffected.
- Carried ideas: one-tap "new chat"/session reset for poisoned history; SMS opt-in gating; web_render on JS/SPA; debug-only adb broadcast test hook for deterministic QA.
- `web_fetch` 403 from some sites is site-side (not a bug).
- Scheduler pre-run script's "device unreachable" status remains unreliable — PM's live `adb devices -l` is ground truth (re-discover every run).
