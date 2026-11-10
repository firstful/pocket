# Pocket PM Ledger

Append-only log of ideas, bugs, QA runs, and shipped changes. Newest at top within each section is fine; prefer dated entries.

## Ideas / Fun Extras
- 2026-11-10: Session-history sanitizer + "start fresh chat" recovery when gateway rejects malformed persisted tool history (auto-detect 4xx/5xx protocol errors like missing tool_call_id/name, offer one-tap reset of current session history). Low-med effort, high resilience.
- 2026-11-10: Inline stream-error banner showing gateway error payload + "Retry" action (today a 200-with-error SSE becomes a silent 2-line response). Small UX win for debugging.

## Bugs / Regressions
- 2026-11-10 [fix pushed, CI pending]: Chat/streaming broken on vc 73 (`1.0.73-1cbd954`) for `hello` and web_search prompts — gateway returns SSE error `Cannot resolve tool name for tool_call_id=None. Either 'tool_calls[].function.name' in assistant message or 'name' in tool message must be provided.` (`streamChat: loop done, lines=2 data=1`). Evidence: `/root/pocket/docs/pm/logs/20261110T060753Z-hello.log`, `/root/pocket/docs/pm/logs/20261110T060753Z-websearch.log`, screenshots `/root/pocket/docs/pm/screenshots/20261110T060753Z-hello.png`, `/root/pocket/docs/pm/screenshots/20261110T060753Z-websearch.png`. Fix pushed `7211dd8` (wire mapper adds/resolves tool message `name`, drops malformed blank tool_call ids/names) — awaiting CI/build/store/device verify.

## QA Runs
- 2026-11-10T06:07Z (device `pixel6a 192.168.1.147:45357`, vc 73 `1.0.73-1cbd954`): Identity PASS (`docs/pm/logs/20261110T060753Z-identity.log`). Launch/no-crash PASS, foreground `com.sutliff.pocket/.MainActivity`, no FATAL/AndroidRuntime (`docs/pm/logs/20261110T060753Z-launch.log`, `docs/pm/screenshots/20261110T060753Z-launch.png`). Hello stream FAIL — gateway 500-in-SSE `tool_call_id=None` / missing tool name, `lines=2 data=1` (`docs/pm/logs/20261110T060753Z-hello.log`, `docs/pm/screenshots/20261110T060753Z-hello.png`). Web search FAIL/blocked by same root cause — no `WebSearchTool` result tags, same error (`docs/pm/logs/20261110T060753Z-websearch.log`, `docs/pm/screenshots/20261110T060753Z-websearch.png`). Settings screenshot captured (`docs/pm/screenshots/20261110T060753Z-settings.png`, reachability not fully asserted). No unexpected permission dialogs observed in captured focus/logs.

## Shipped
- 2026-11-10: `7211dd8` (CI pending) — fix(pocket): include/resolve tool message names in OpenAI wire mapper to prevent gateway 500 on history (`InferenceClient.kt`: add `name` to `WireMsg`, resolve tool result name from assistant tool_calls by id, drop malformed blank tool_call ids/names). Pushed to `main`; CI/store/device verification pending (next run).
