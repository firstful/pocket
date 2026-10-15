# Pocket — 4 feature goals implemented (2026-10-15)

**Status: PUSHED + CI GREEN + INSTALLED & VERIFIED ON DEVICE**

- Branch: `main`, commits `8d66f31` + `47690c9` (on top of `4cda667`), pushed to origin.
- CI: Gitea Actions run **995** (head `47690c9`) → **completed / success** (~4 min).
- APK: `1.0.29-47690c9` (versionCode 29), sha256 `ffda37b0…4f05f` verified against
  `apps.ffilt.us/apps.json`, installed via adb to `192.168.1.127:46875`.
- No local gradle/docker builds were run (host rule honored).

---

## Feature 1 — Status indicators (state machine + visible status line)

Replaced the single `isStreaming: Boolean` with an explicit 5-state machine:

| State | Meaning | Where surfaced |
|---|---|---|
| `IDLE` | nothing in flight | chip "✓ Ready" (muted) |
| `WAITING_FOR_NETWORK` | offline / no route | chip "⚠ Offline — will send when back online" (error colors); auto-derived whenever the device is offline while idle |
| `SENDING` | request written, awaiting response headers | chip + linear progress, emitted by `InferenceClient.streamChat` just before `execute()` |
| `WAITING` | connected, awaiting first token | chip + linear progress, emitted after response headers return |
| `STREAMING` | receiving deltas | set on first `ContentDelta`, chip "Receiving…" |

- UI: `StatusLine` composable directly above the input — status chip (icon or 12dp
  spinner + text) plus an indeterminate `LinearProgressIndicator` while active, plus a
  "N queued" chip when messages are pending. The in-flight assistant bubble also shows
  the phase as a small label ("Sending… / Waiting for response… / Receiving…").
- Phase plumbing: `InferenceClient.streamChat(..., onPhase: ((AgentStatus) -> Unit)? = null)`
  → `AgentSessionManager.run(..., onStatus: (AgentStatus) -> Unit = {})` (defaulted, so
  `AgentBridgeService`/`HeadlessAgentRuntime` callers are untouched) → ChatScreen.

**Files:** `data/Models.kt` (enum), `network/InferenceClient.kt`, `agent/AgentSessionManager.kt`,
`ui/screens/ChatScreen.kt` (`StatusLine`, `statusText`, streaming-bubble label).

## Feature 2 — Offline queueing + durability

- Sending while offline (or while a backlog exists) **queues instead of dropping**: new
  `QueuedMessage(sessionId, text, timestamp)` persisted to `filesDir/outbox.json` via new
  `data/OutboxStore.kt` — same JSON-file style as the session store, atomic tmp+rename
  writes, `@Synchronized` access. Survives app restart / process death.
- Queued messages render immediately as user bubbles with a **"🕐 Queued · 14:32" badge**
  (clock icon + timestamp); StatusLine shows the "N queued" chip.
- **Auto-flush on connectivity return**: new `network/NetworkMonitor.kt`
  (`isNetworkAvailable()` + `ConnectivityManager.registerDefaultNetworkCallback`) flips an
  `isOnline` state and flushes the outbox in FIFO order; flush also runs on session open
  and on app start (queue for the open session). Failed sends stay queued and re-mark the
  bubble as queued — input is never silently dropped (send button stays enabled offline).
- Session switching mid-flush stops the loop safely (per-session guard + seen-set progress
  guard); flushed messages are removed from the outbox only after `run()` persisted them.
- Telemetry: `message_queued` (reason=offline|backlog), `message_sent` (source=direct|queued),
  `network_available`.

**Files:** `data/OutboxStore.kt` (new), `network/NetworkMonitor.kt` (new), `data/Models.kt`,
`ui/screens/ChatScreen.kt`.

## Feature 3 — Session management

- **Resume across restarts**: `currentSessionId` persisted in DataStore
  (`datastore/DataStoreHelper.kt`: `getCurrentSessionId`/`setCurrentSessionId`).
  ChatScreen init moved from `remember { runBlocking { readConfig } + createSession() }`
  to an async `LaunchedEffect(Unit)` (config load → restore last session → fallback newest
  → else create; emergency-create + `isReady` flag even on error, input shows "Loading…"
  until ready). No `runBlocking` in composition anymore; `AppNavigation` startDestination
  unchanged (Chat resolves its own session).
- Storage: `AgentSessionManager` already persisted sessions as JSON files — kept, but fixed
  two durability bugs: `saveSession()` used to **clobber `title`/`createdAt`** on every save
  (now `saveCurrentSession()` preserves session metadata, only updating `messages`/`updatedAt`),
  and the title is **derived from the first user message** (≤40 chars) instead of always
  "New Session"/"Session".
- Drawer (`SessionDrawerContent`): rows are now **clickable** (original had no click handler —
  switching was impossible), show derived title + relative timestamp + message count, a check
  marker on the current session (bold headline), delete button; empty state text. New session
  via existing top-bar "+" ; delete switches to the next session or creates one (blocked while
  a live run writes to the current session). Switching is instant — transcript + tool states
  rebuilt from disk via `applySession()`, no restart needed; in-flight events from a
  pre-switch run are dropped (stale-`sid` guard) while that run still persists to its own file.
- Telemetry: `session_resume` (restored, message count), `session_new`, `session_switch`,
  `session_delete`.

**Files:** `datastore/DataStoreHelper.kt`, `agent/AgentSessionManager.kt`, `ui/screens/ChatScreen.kt`.

## Feature 4 — Tool call displays

- **Interleaving**: new `TimelineEntry` (Msg | Tool) ordered list — tool calls appear where
  they arrived (user → tools → assistant bubble) instead of being dumped after all messages;
  built live from `ToolCallStart` events, rebuilt from disk on session load (tool results
  folded into their tool bubble; interrupted calls shown Failed, never an eternal spinner).
- **`ToolCallBubble` upgrade**: pretty-printed multiline JSON args (monospace, truncated at
  320 chars with Show more/less), status = running spinner / ✓ "Done" / ⚠ "Failed" with
  matching colors (surfaceVariant/secondaryContainer/errorContainer), duration
  (`1.2s`/`350ms`) measured from `ToolCallStart`→`ToolCallResult`, result block with its own
  expand/collapse at 600 chars. Failures detected from `Error`-prefixed tool results.
- **Fragment accumulation fix (prerequisite)**: streamed tool-call deltas are now merged per
  call id in `AgentSessionManager.run()` (id-less argument tails appended, delta vs cumulative
  servers handled, synthesized `call_auto_N` ids as last resort) — previously each fragment
  overwrote the state with invalid partial JSON. `AgentEvent` wiring
  (`ToolCallStart`/`ToolCallResult`) unchanged.

**Files:** `agent/AgentSessionManager.kt`, `ui/screens/ChatScreen.kt`.

---

## Files touched (7 modified, 2 new)

| File | Change |
|---|---|
| `app/src/main/java/com/sutliff/pocket/data/Models.kt` | + `AgentStatus`, + `QueuedMessage` |
| `app/src/main/java/com/sutliff/pocket/data/OutboxStore.kt` | **new** — durable outbox |
| `app/src/main/java/com/sutliff/pocket/network/NetworkMonitor.kt` | **new** — connectivity |
| `app/src/main/java/com/sutliff/pocket/network/InferenceClient.kt` | `streamChat` + `onPhase` (SENDING/WAITING) |
| `app/src/main/java/com/sutliff/pocket/datastore/DataStoreHelper.kt` | + current-session-id get/set |
| `app/src/main/java/com/sutliff/pocket/agent/AgentSessionManager.kt` | + `onStatus`, tool-fragment merge, title derivation, metadata-preserving save |
| `app/src/main/java/com/sutliff/pocket/ui/screens/ChatScreen.kt` | rewritten: status line, timeline, queue, sessions, tool bubbles; async init |
| `telemetry/Telemetry.kt`, `ui/AppNavigation.kt`, `AndroidManifest.xml` | unchanged (hooks/perms already sufficient) |

## Commits

- `8d66f31` feat: agent status state machine, durable offline outbox, session persistence hooks
- `47690c9` feat(ui): status line, offline queueing, session resume/switch, tool call bubbles
- (this report) docs: STATUS.md — feature report

## Test results

Verified on device `192.168.1.127:46875` (WiFi adb) with the CI build:

1. `adb install -r` of `1.0.29-47690c9` → **Success** (sha256 matches manifest).
2. Cold start `am start -n com.sutliff.pocket/.MainActivity` → process alive
   (`ps`: com.sutliff.pocket), `logcat AndroidRuntime:E *:F` → **empty, crash-free**.
3. Screenshot: Pocket top bar, **status chip "✓ Ready"** above input, placeholder
   "Message…", session transcript (`ping`/`pong`) **reloaded after a force-stop → relaunch**
   (proves session persistence + resume), send button, no error banner, no layout glitches.
4. CI run 995 = success (Kotlin compile + unit tests + signed APK publish).

**Pending (device was actively in use by its owner during the session — foreground kept
switching, so no further UI interaction was forced):** manual walkthrough of the session
drawer, offline-queue badge/auto-flush (toggle WiFi), and a live tool-call round (spinner →
Done + duration). Code paths for all three are in the installed build; recommend a quick
hands-on pass.

## Notes / constraints honored

- No `./gradle`, no docker build — compile gate was CI only.
- No PII in telemetry; 2KB cap untouched; new events are counters/flags only.
- English throughout; state hoisting kept in `ChatScreen`, all init/network work in
  `LaunchedEffect`/`DisposableEffect` (no blocking in composition).
