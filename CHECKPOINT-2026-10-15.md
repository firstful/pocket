# Pocket APK — Resumable Checkpoint (2026-10-15 ~03:30 UTC)

**Standing goal:** iterate on the Pocket Android chat APK using **CI builds only**
(no local Gradle — host freeze rule) + **WiFi adb debugging** for on-device testing.

---

## 1. Where the build stands

| Version | Commit | What | Verified? |
|---|---|---|---|
| 1.0.25 | `7201ace` | fix: AgentBridgeService `startForeground(null)` cold-start crash | ✅ verified live |
| 1.0.26 | `5095a7c` | defaults → `http://llm.homelab.internal/v1`, key `dummy`, model `auto` | ✅ endpoint E2E verified |
| 1.0.27 | `4cda667` | fix: `BackgroundServiceStartNotAllowedException` (background-restricted service start on install/restore) — catch in `Application.onCreate`, retry from `MainActivity.onResume`, telemetry `bridge_start_blocked` / `bridge_service_start_retried` | ✅ install-restore launch clean, 2× force-stop cycles clean, chat ping→pong |
| **1.0.29** | `47690c9` (+`8d66f31`) | **4 features**: ① status chip state machine (IDLE/WAITING_FOR_NETWORK/SENDING/WAITING/STREAMING) ② durable offline outbox (`filesDir/outbox.json`, NetworkMonitor auto-flush, 🕐 Queued badge) ③ session persistence/resume + drawer switch ④ upgraded tool-call bubbles (pretty args, status, interleaved) | ⏳ **installed on device, NOT yet tested** — was next step |

- **Currently installed on phone: `1.0.29-47690c9` (vc29)**, install Success, process alive, 0 FATAL in logcat.
- Feature subagent report: `/root/pocket/STATUS.md` (commit `f997324`); note it **rewrote STATUS.md** (telemetry report gone).
- Origin/main = `f997324`; all fixes are ancestors, no diverged history.

## 2. Build/test loop (the repeatable path)

```bash
# 1. push → CI (~4 min; webhook usually fires, else manual POST /api/v1/repos/reeds/pocket/actions/tasks)
cd /root/pocket && git push origin main

# 2. wait for CI
TOKEN=$(cat /root/.gitea-docs-token)
curl -s -H "Authorization: token $TOKEN" "http://localhost:3000/api/v1/repos/reeds/pocket/actions/runs" \
 | python3 -c "import sys,json;[print(r['head_sha'][:7],'run'+str(r['id']),r['status'],r.get('conclusion')) for r in json.load(sys.stdin)['workflow_runs'][:3]]"

# 3. download + verify
curl -s http://apps.ffilt.us/apps.json        # expect version, sha256, versionCode = commit count
curl -sL -o /tmp/P.apk http://apps.ffilt.us/apk/Pocket/app-release.apk && sha256sum /tmp/P.apk

# 4. install + test
export PATH=/opt/platform-tools:$PATH
D=192.168.1.127:46875                          # rediscover: adb mdns services | grep _adb-tls-connect
adb -s $D logcat -c
adb -s $D install -r /tmp/P.apk                # downgrade FAILS (needs -d); use always-newer CI builds
adb -s $D shell am start -n com.sutliff.pocket/.MainActivity
adb -s $D logcat -d AndroidRuntime:E *:F       # expect empty
```

## 3. WiFi adb (device: Reed's Pixel 6a)

- **adb:** `/opt/platform-tools/adb` (v1.0.41 — Ubuntu's 1.0.39 lacks `pair`/`mdns`); export PATH per call.
- **Serial:** `192.168.1.127:46875` (IP may change → rediscover via `adb mdns services | grep _adb-tls-connect`).
- **Pairing persists** (guid `adb-27271JEGR25574-WKjTlT`) — only re-pair if phone forgets; mDNS pairing service exists only while the pairing dialog is open; 6-digit code ~60 s. QR pairing possible via `npx adb-qr`.
- **UI automation:** `adb shell uiautomator dump /sdcard/ui.xml` → parse `text=`/`content-desc=`/`bounds=` for exact tap coords (do NOT guess). Settings gear = `content-desc="Settings"` (was at ~1007,217).
- **Screenshots:** `adb -s $D exec-out screencap -p > /tmp/x.png` → `vision_analyze`.

### Phone gotchas (cost time this session)
- **Screen sleeps → keyguard locks → fingerprint required; adb CANNOT unlock** (`wm dismiss-keyguard` failed to help while secured). Keep screen awake: `adb shell settings put system screen_off_timeout 600000` (already set, was 1800000→600000).
- **Black screencap = display dozing**, not an app bug (`dumpsys power | grep mWakefulness`).
- **Shared daily phone:** if top activity is not Pocket (search app, Bambu app…) a human is using it — **STOP injecting taps**; coordinate screen time with Reed instead.
- Wake: `input keyevent KEYCODE_WAKEUP` + `input swipe 540 1900 540 500 300`.
- Chat test recipe: tap input (~300,2180) → `input text "hello"` → tap send (~996,2180) → 12 s → dump/screenshot.

## 4. Endpoint / backend

- Pocket backend: `http://llm.homelab.internal/v1`, model `auto`, key `dummy` — resolves via OPNsense → **192.168.1.2**, `/v1/models` 200.
- Live gateway = **`llm-gateway.service` on :8088** (pid in system.slice); **:8089 = candidate/staging** (blue-green swap, skill `llm-gateway-swap`). socat :8080 → unrelated k3s pod (404 on /v1).
- Chat completion smoke: `POST /v1/chat/completions {"model":"auto",...}` → 200.

## 5. Telemetry verification (device-side truth)

```bash
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
kubectl run telemetry-peek -n telemetry --image=python:3.11-alpine --restart=Never \
 --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"reed-a320ms2h"},"containers":[{"name":"p","image":"python:3.11-alpine","command":["sh","-c","python3 - <<PY\nimport sqlite3\nc=sqlite3.connect(\"/data/telemetry.db\")\nfor r in c.execute(\"SELECT id,ts,body FROM logs WHERE body LIKE \x27%pocket%\x27 ORDER BY id DESC LIMIT 10\"): print(r)\nPY\n"],"volumeMounts":[{"name":"data","mountPath":"/data"}]}],"volumes":[{"name":"data","persistentVolumeClaim":{"claimName":"telemetry-data"}}]}}'
kubectl logs telemetry-peek -n telemetry; kubectl delete pod telemetry-peek -n telemetry --force
```
Known events: `app_create, main_activity_create/resume/pause, bridge_service_create, boot_completed, chat_send, sms_trigger_matched` + new feature events (`session_resume`, `message_queued`, …) + `bridge_start_blocked`/`bridge_service_start_retried`.
Healthy pattern (1.0.27+): `app_create → activity_create → resume → bridge_service_create`.

## 6. Next concrete step (was in flight when paused)

**Test the 4 features on 1.0.29 — blocked on phone screen availability (human was using it):**
1. **Session resume:** force-stop → relaunch → previous messages (hello, ping/pong) must reappear; drawer shows titles/timestamps; `+` new chat; switch sessions.
2. **Status indicators:** send msg → catch chip states (sending/waiting/streaming); idle shows nothing/"Offline…".
3. **Offline queue:** `adb shell cmd connectivity airplane-mode enable` → send → 🕐 Queued badge + "N queued" chip → airplane off → auto-flush FIFO. **Reset airplane off when done.**
4. **Tool call:** ask e.g. "what's my battery status" → tool bubble with args/pretty JSON/status/duration, interleaved in arrival order.
5. Screenshot each; check logcat FATAL empty; check telemetry events arrived.

## 7. Decisions / rules already locked

- **NO local Gradle/Android/docker builds on sutliffserver** — CI only (Reed 2026-10-15; SOUL.md `<BUILD_RULE>` + memory). Local+CI concurrent builds coincided with hard freezes (Oct 11, Oct 15) needing power-cycle.
- No `runs API`/direct curl for cross-profile work — A2A only (but Gitea Actions API for CI polling is fine).
- Ship-fix-first sequencing: crash fixes (`7201ace`, `4cda667`) precede features; never push without CI green before installing.
- RAM guardrails now live (hermes MemoryMax=1664M, OOMPolicy=continue, earlyoom swap tripwire) → a runaway build gets killed instead of freezing the box; reports at `/root/ram-limit-report-2026-10-15.md` + `/root/.hermes/cache/delegation/subagent-summary-*`.

## 8. Open threads (Pocket-related)

- [ ] **1.0.29 feature test round** (section 6) — needs ~3 min of screen control.
- [ ] Crash #2 fix path proven but only on observed restore path — keep watching `bridge_start_blocked` in telemetry.
- [ ] Phone screen timeout currently 10 min (was 30) — restore if Reed prefers.
- [ ] Feature STATUS.md overwrote the telemetry STATUS report (history in git `f997324`).
- [ ] Older held/other topics (NOT Pocket): earlyoom kill rehearsal awaiting OK (`/root/ram-guard-tests/earlyoom_test.sh`), llm-gateway fixes in `/tmp/lg-fix` (HOLD, no deploy), property docs, UniFi no-change.
