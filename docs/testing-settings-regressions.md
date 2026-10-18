# Playback and cache settings regressions

The project uses a single app module, manual dependency assembly, JUnit 4 and
Robolectric. Keep this stack; no DI migration or emulator is needed for these
settings fixes. `work-testing` matches the production WorkManager version and
is a test-only dependency.

`PlaybackSettingsRobolectricTest` exercises the actual ViewModel with persisted
DataStore settings and no individual UI collectors. An in-memory synthetic
session and intercepted HTTP responses prevent contact with real accounts or
servers. It checks SQ playback requests, mobile caching, retry quality,
changes to waiting downloads, and startup repair of old Wi-Fi constraints.

`DownloadPolicyRobolectricTest` uses an in-memory Room database and WorkManager's
test scheduler on API 24 and 35. It checks task identity, network constraints,
partial-file preservation, idempotence and account/paused/completed isolation.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PlaybackSettingsRobolectricTest' --tests '*DownloadPolicyRobolectricTest'
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Real-device acceptance is separate: turn Wi-Fi off, verify a queued download
can proceed over mobile data when allowed, and verify the actual audio quality
against an account/song entitled to SQ. Upstream entitlement fallback and
existing offline audio quality must not be misreported as SQ.
