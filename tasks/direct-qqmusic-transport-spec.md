# Spec: Direct QQ Music Transport

## Objective

Move every post-login QQ Music data request from the QMusic Watch gateway to QQ
Music's official HTTPS endpoints. The Ronan server remains responsible for QQ
and WeChat QR creation/polling, plus updates, announcements, diagnostics and
feature switches. Outside the explicitly retained QR flow, it must never
receive QQ Music cookies, search terms, song identifiers, lyrics, playlists or
stream URLs.

The existing signed-in session is reused locally. New QQ and WeChat QR sessions
continue using the server because the native/WebView flow was unreliable on
some watches. There is no silent gateway fallback for post-login music data.

## Tech Stack

- Kotlin, coroutines and OkHttp 4.12 in the existing single Android app module.
- kotlinx.serialization for bounded JSON parsing.
- Android Keystore-backed `SessionVault` for QQ Music credentials.
- Existing `ControlPlaneClient` for `https://heyboxlite.xyz/` control requests.

## Commands

- Unit tests: `./gradlew.bat --no-daemon --console=plain --max-workers=2 testDebugUnitTest`
- Release build: `./gradlew.bat --no-daemon --console=plain --max-workers=2 assembleRelease`
- Direct-route audit: `rg -n "api/qmusic-watch/(gateway|auth/.+/qr)|auth/refresh" app/src/main`
- Server-leak audit: `rg -n "qmusicServerEndpoint|QMUSIC_SERVER_BASE_URL" app/src/main`

## Project Structure

- `network/ApiClient.kt`: QQ Music API, search, profile, stream and write calls.
- `network/ControlPlaneClient.kt`: server QR creation/polling and other control calls.
- `network/QMusicGateway.kt`: trusted QQ/Tencent media URL policy and the control host constant.
- `network/ControlPlaneClient.kt`: Ronan server control-only calls.
- `QMusicApplication.kt`: dependency assembly and image/network policy.
- `AppViewModel.kt`: feature-switch enforcement and QR UI state.
- `app/src/test`: transport, QR parsing, media allowlist and favorite contracts.

## Code Style

Keep protocol construction explicit and bounded:

```kotlin
val request = Request.Builder()
    .url(QQ_MUSICU_URL)
    .post(payload.toString().toRequestBody(JSON_MEDIA))
    .header("Referer", "https://y.qq.com/")
    .build()
```

Use named helpers for host validation and response parsing. Never log cookies,
QR state, vkeys, stream query strings or full upstream response bodies.

## Testing Strategy

- Retain server QR contract tests and unit-test direct media host allowlists.
- Contract-test direct `musicu.fcg` request shape and favorite fallback order
  with `MockWebServer`-style injectable transports or pure request builders.
- Retain all existing parser, playback recovery, UI and signing tests.
- Build the signed `0.9.9 (39)` release APK after the explicit release request.
- Audit the release source for forbidden gateway routes and leaked secrets.

## Boundaries

- Always: use HTTPS official hosts, bound response sizes, redact secrets, keep
  server feature flags effective and preserve cached/offline playback.
- Ask first: change app version, publish a release, remove legacy server routes,
  or change the public control API.
- Never: proxy post-login QQ Music data through the Ronan server, bypass
  VIP/region/DRM rights, accept arbitrary redirect/media hosts, or commit real
  credentials.

## Success Criteria

- Only QR create/poll, control, diagnostics and update calls target `heyboxlite.xyz`.
- Control config can still disable QR login, profile, lyrics, streaming,
  playlist writes and diagnostic upload.
- QQ and WeChat QR generation/polling remains on the server; the returned
  credential is encrypted on the watch and used directly afterwards.
- Search, home, library, details, profile, lyrics and vkey use official QQ Music
  endpoints directly.
- Favorite add/remove first uses `SongFavWrite/AddSongFav|DeleteSongFav` with
  scalar MID/ID and falls back to Android directory `201` with `songType=0`.
- Official stream and Tencent image hosts pass the strict allowlist; unrelated
  hosts and server gateway media paths are rejected.
- All tests and the canonical signed release build pass with version `0.9.9 (39)`.

## Open Questions

None. The user explicitly retained server-generated QQ/WeChat QR login while
selecting direct transport for every post-login QQ Music operation.
