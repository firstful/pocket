# Config import (passphrase endpoint)

Pocket can fetch its configuration from an HTTPS endpoint using a one-time
passphrase, instead of relying on a build-time `assets/config.seed.json`.
This lets a provisioned device be configured post-install without baking
secrets into the APK.

## Flow

1. In **Settings → Config import**, enter the endpoint URL and passphrase.
2. The app POSTs `{"passphrase":"..."}` to the URL (passphrase is in the body
   only — never the URL/query, logs, crash reports, or telemetry).
3. On a `200`, the endpoint returns a `SeedConfig` JSON object (same shape as
   `config.seed.json`). The app parses it and applies each present block via
   the normal DataStore `save*` helpers, which Keystore-wrap any secrets.
4. The imported version is recorded separately (see below) so the same version
   is not re-applied on every open. The passphrase is cleared from UI state
   immediately after the attempt (success or failure).

The URL field defaults to `https://pocket-cfg.homelab.internal/config`.

## Endpoint contract

- **Method:** `POST`
- **Content-Type:** `application/json`
- **Request body:** `{"passphrase":"<secret>"}`
- **Transport:** HTTPS only. The client rejects `http://` (and non-HTTPS
  schemes) with `InsecureConfigUrlException` *before* sending, regardless of
  the app-wide `android:usesCleartextTraffic="true"` flag. TLS uses the
  platform trust store (system + user-installed CAs), so a Tailscale cert or
  internal CA installed on the device is trusted; hostname verification stays
  on. A certificate-pinning hook is marked in `ConfigImportClient`.
- **Responses:**
  - `200` + `SeedConfig` JSON object → applied.
  - `401` → wrong passphrase (friendly "Incorrect passphrase" message).
  - `429` → rate limited ("Too many attempts — please wait and try again").
  - `5xx` → network/server error.
  - other non-2xx, or a `200` body that is not a JSON object → invalid response.

A `SeedConfig` with no config blocks (`inference`, `remoteAgents`, `telemetry`,
`permissions` all absent) is rejected during validation and is never marked
as applied.

## Version key

Imported configs are tracked with the separate DataStore preference key
`applied_import_version` (`appliedImportVersion`). This is deliberately **not**
`applied_seed_version`, which the build-time seed path writes — keeping them
separate prevents either path from silencing the other. A config is re-applied
only when its `seedVersion` differs from the applied import version. The key is
written **last**, after all blocks succeed, so a partial failure does not mark
the import as applied. (An internal `force` option exists to re-apply the same
version — e.g. to re-wrap secrets after a device security change.)

## Secrets: Keystore-wrapped, with legacy-plaintext compatibility

API keys (`InferenceConfig.apiKey`, `RemoteAgent.apiKey`) are wrapped before
they are persisted to DataStore and unwrapped on every read via
`crypto/KeystoreWrap`. Wrapped values use the form:

```
enc:v1:<base64(iv || ciphertext)>
```

- AES-256-GCM, hardware-backed Android Keystore key, **non-exportable**, with
  a fresh 12-byte SecureRandom IV per wrap.
- `setUserAuthenticationRequired(false)`: Pocket is headless (SMS/boot/
  notification triggers read secrets with no Activity for BiometricPrompt),
  so a user-auth gate would break background sessions.
- Values **without** the `enc:v1:` prefix are returned unchanged. This keeps
  existing installs working: legacy plaintext from seeded builds or keys
  manually entered in Settings still reads fine, and is re-wrapped on the
  next save.
- If the hardware key is invalidated by a device security change, unwrap
  throws `SecretWrapException`; the user must **re-import** config from
  Settings so secrets are re-wrapped under the new key.

`android:allowBackup="false"` keeps DataStore out of cloud/ADB backups.
