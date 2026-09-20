# Project guidance

Read `docs/ARCHITECTURE.md` before changing module boundaries or API contracts.

- This project is an Android client for an existing fnOS Video service. Do not modify NAS configuration, database, or media as part of ordinary app development.
- Keep NAS request/response fields, signing and playback URL resolution inside `FnApi`. UI depends on `MediaRepository` models and contracts.
- Keep feed ordering, pagination and stale-request handling independently testable. Network calls must not run on the Android main thread.
- Use one active player. Verify rapid swipes, user pause, backgrounding, and teardown when changing playback behavior.
- Use clean-architecture and a-philosophy-of-software-design for architectural decisions; clean-code and code-complete for implementation and review. Prefer focused boundaries over layers that merely forward calls.
- Never commit credentials, tokens, private media catalog exports, SDKs, build caches or machine-local paths. Session storage uses Android Keystore; no passwords in source or APK.
- Validate with `testDebugUnitTest`, `assembleDebug` and `lintDebug`. State separately whether device install, NAS login, and native playback were actually tested.
- Delegate bounded independent work to `luna_max_worker` with a complete brief and fresh context. Do not overwrite another worker's changes.
