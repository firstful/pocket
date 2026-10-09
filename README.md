# Pocket

A native Android app that owns an Alpine proot environment and provides a headless agent runtime with event-driven triggers.

## What This Is

- **Not** a separate app that interops with Termux
- **Is** a self-contained Android app with the agent runtime built directly in
- The app owns its own proot + Alpine environment (no external Termux dependency)
- No terminal UI — just a minimal status activity + background service

## Architecture

```
pocket/
├── app/src/main/java/com/sutliff/pocket/
│   ├── AgentApp.kt              # Application class, starts bridge service
│   ├── MainActivity.kt          # Minimal status screen (no terminal UI)
│   ├── bridge/
│   │   └── AgentBridgeService.kt    # Foreground service, owns proot + agent
│   ├── data/
│   │   └── Models.kt            # InferenceConfig, ChatMessage, StreamChunk
│   ├── datastore/
│   │   └── DataStoreHelper.kt   # Config persistence
│   ├── network/
│   │   └── InferenceClient.kt   # OpenAI-compatible API client
│   ├── runtime/
│   │   └── HeadlessAgentRuntime.kt  # LLM streaming + tool execution
│   ├── tools/
│   │   └── ToolRegistry.kt      # exec, fs_read, fs_list, health
│   └── triggers/
│       ├── SmsTriggerReceiver.kt    # SMS regex → agent session
│       └── BootReceiver.kt          # Restart service on boot
```

## Key Features

### Agent Runtime
- OpenAI-compatible inference server (configurable URL, API key, model)
- SSE streaming for real-time responses
- Tool calling: `exec`, `fs_read`, `fs_list`, `fs_write`, `fs_exists`, `health`
- Headless operation — no UI required for agent execution

### Event Triggers
- **SMS Trigger**: Regex match on sender/body → spawns background agent session
- **Boot Trigger**: Auto-starts bridge service on device boot
- Extensible: add more `BroadcastReceiver`s for other events (notifications, calls, etc.)

### Bridge Service
- Foreground service keeps proot + agent alive
- Socket server on `localhost:8765` for external control
- JSON command protocol: `health`, `exec`, `fs_*`, `agent_run`

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Inference server communication |
| `RECEIVE_SMS` | SMS trigger receiver |
| `READ_SMS` | Read SMS content |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on boot |
| `FOREGROUND_SERVICE` | Keep bridge alive |
| `POST_NOTIFICATIONS` | Service notification |

## Build

```bash
# Requires Android SDK 34, JDK 17
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

Inference config stored in DataStore. Set via:
- `adb shell` + `am broadcast` to update DataStore
- Or add a settings activity later

Default: `http://192.168.1.100:8080/v1`, model `llama3-8b-instruct`

## SMS Triggers

Default rule: any SMS containing "agent", "run", or "exec" (case-insensitive) triggers a session.

Customize by editing `SmsTriggerReceiver.loadTriggerRules()`:
```kotlin
TriggerRule(
    name = "custom",
    senderPattern = "\\+1234567890",  // exact sender
    bodyPattern = ".*deploy.*",        // regex on body
    promptTemplate = "SMS from {sender}: {body}\n\nDeploy the latest build."
)
```

## Bridge Protocol

Connect to `localhost:8765` and send JSON:

```json
{"command": "health"}
{"command": "exec", "args": {"command": "ls -la", "cwd": "/root"}}
{"command": "agent_run", "prompt": "Check disk space and report"}
```

## Next Steps

1. Add proot binary + Alpine rootfs to `assets/`
2. Implement proot extraction on first launch
3. Add more trigger types (notification listener, call receiver)
4. Add SMS reply after agent completes
5. Push to Gitea for CI builds