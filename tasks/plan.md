# Spec: Watch playback completion

## Objective

Complete every item in the approved playback/settings checklist for a 480x480 Android 7+ square watch while keeping the existing single-module Kotlin/Compose architecture.

## Tech Stack

Kotlin, Jetpack Compose, DataStore, Room, WorkManager and Media3 1.10.1. No new dependency is required.

## Commands

- Test: `.\gradlew.bat :app:testDebugUnitTest`
- Lint: `.\gradlew.bat :app:lintRelease`
- Build: `.\gradlew.bat :app:assembleRelease`

## Project Structure

- `app/src/main/java/com/ronan/qmusicwatch`: UI and state orchestration
- `data`: persistent settings, queue snapshots and download records
- `playback`: Media3 service/controller, volume and sleep timer
- `download`: resumable account-isolated offline cache
- `app/src/test`: parser, queue and snapshot unit tests

## Code Style

Use existing StateFlow/DataStore patterns and native Android/Compose APIs. Keep behavior in the shared controller/view-model rather than duplicating it in screens.

## Testing Strategy

Unit-test queue cleanup, snapshot compatibility and cache calculations. Run all JVM tests, release lint, R8 release build and APK signature/package verification.

## Boundaries

- Always: preserve API 24 support, account cache isolation, existing login/playback behavior and offline data.
- Ask first: new external dependencies, database-destructive migrations or server contract changes.
- Never: store credentials in logs, bypass membership/DRM restrictions or expose another account's cache.

## Success Criteria

- Playback mode, queue order, current track and position survive app restart.
- Sleep timer supports presets/custom time, fade-out and finish-current behavior.
- Lyrics support original/translation visibility, size, offset, animation strength, manual scrolling and tap-to-seek.
- Player supports cover double-tap, cover swipe track/volume, rotary volume/lyrics, touch lock and AMOLED black mode.
- Cache supports Wi-Fi-only mode, total usage, playlist grouping label, invalid-file cleanup and storage errors.
- Account page shows provider-appropriate account ID, avatar, nickname, membership type/expiry, created and collected playlist sections, login check and re-login.
- Queue supports reorder, reverse, save-as-playlist, batch import, duplicate removal, next-play and search.

## Open Questions

None. The user approved the complete checklist and requested all changes be pushed after verification.

