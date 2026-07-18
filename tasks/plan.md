# QMusic Watch client plan

1. Keep the watch client independent from optional update and diagnostics services.
2. Preserve direct QQ Music requests, encrypted local account storage and offline playback.
3. Maintain API adapters for search, library, playlists, lyrics, streams and account status.
4. Verify API 24 and API 36 behavior on a 480x480 square display before changing the app version.
5. Run JVM tests, Android source compilation, lint and the debug build for every functional change.
6. Publish a new Android version only when Ronan explicitly requests a release.

