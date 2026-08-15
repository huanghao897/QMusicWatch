# QMusic Watch direct transport plan

1. Preserve `ControlPlaneClient` and its cached feature switches on `heyboxlite.xyz`.
2. Retain the existing server-generated QQ/WeChat QR flow as the sole QQ Music gateway exception.
3. Send post-login musicu, legacy search/profile, lyrics, library, playlist and stream requests directly to official QQ Music hosts.
4. Restrict media and image loading to explicit QQ/Tencent HTTPS host families.
5. Repair favorite writes with the current SongFav contract plus directory-201 fallback.
6. Remove gateway credential migration and the server auth-refresh route; retain only server QR create/poll.
7. Run unit tests, direct-route audits and the canonical release build for version `0.9.9 (39)`.
8. Publish `0.9.9 (39)` to the existing server update channel after Ronan's explicit release request.
