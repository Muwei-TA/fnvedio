# Session, playback and pagination hardening

Base: `main@06f22b2c5db8ad081fae845c0d9579fff5bbdc5b`.
Branch: `fix/playback-session-hardening-20260921`.

## Scope and invariants

This change applies the five-axis review findings without changing NAS configuration,
API payloads, signing constants, decoder preferences, or the existing audio-track
compatibility fallback. It does not merge or force-push the main branch.
The latest main-branch documentation deletions are retained.

- `ServerAddress` validates settings before persistence and canonicalizes HTTP(S)
  origins, including default ports and IPv6. Credentials, query strings, fragments,
  invalid ports and unsupported entry paths are rejected. Supported entry paths are
  the root, `/v`, `/v/login`, `/v/api/v1` and `/v/api/v2`.
- `SessionStore.changeServer` retains a token only for the same origin. Changing
  scheme, host or effective port clears the old token and requires a new login.
  Invalid legacy configuration is cleared at startup rather than crashing in a loop.
- `PlaybackSession` separates selected media from media actually loaded into ExoPlayer.
  Each selection/retry has a distinct ticket, even for A -> B -> A. Resume writes use
  the captured loaded server/media identity, never the currently visible card.
- Stopping first removes playback ownership, then pauses, stops and clears the old
  media item. A resolved source is marked loaded only after its media ID and initial
  seek position have been installed. Delayed view-binding callbacks validate their
  ticket, feed generation and current page without reactivating an old selection.
- `RepositoryFailure` is the app-owned error classification. `FnApi` translates HTTP
  401 and API code -2 into an authentication failure; HTTP 403 is a permission error.
  The UI clears an expired session and offers login instead of reusing a rejected
  token. No English substring matching is used for authentication decisions.
- `FeedState` bounds consecutive empty/duplicate/filtered pages to five requests per
  scan and rejects repeated/cyclic cursors. Later empty pages continue automatically
  while near the end. A visible explicit retry action resumes a bounded scan.
- Search and library changes use one reset path. The old player/list is detached
  before accepting results for the new query. Existing audio focus, headphone
  disconnection handling, H.264/AAC fallback and source-header scoping are retained.

The Activity still owns Android views and lifecycle integration. This is a focused
state-boundary repair, not a claim that all UI orchestration has been extracted.
`PlaybackDataSource` and the verified stream request contracts remain unchanged.

## Automated checks

`CoreRegressionTest` runs dependency-free checks for address normalization, token
retention, playback ownership, stale tickets, pagination bounds and typed errors.
`RepositoryFailureTest` adds MockWebServer coverage for localized API authentication
errors, HTTP 401/403 and misleading message text. Existing API, feed, transport and
audio compatibility tests are retained.

Run the existing project gates:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The repository workflow runs these gates for a pull request to main. CI results and
the exact checked commit must be recorded in the PR; writing this document does not
assert that an Android build or a device test has passed.

## Device acceptance still required

1. Save malformed settings while signed in; the current working session must remain
   intact, and restarting must not crash. Test legacy malformed saved settings too.
2. Switch to another test-server origin and confirm no old Authorization header is
   sent. Equivalent root and `/v` addresses should retain the same session.
3. Start A, delay B resolution, swipe to C or background the app. B must never receive
   A's progress; an A -> B -> A delayed callback must not steal the player.
4. Test explicit pause, backwards seek, playback completion, foreground/background,
   headset removal, and the existing one-attempt audio/video compatibility fallback.
5. Return a duplicate-only later page with another cursor, then valid media. Verify
   continuation, five-empty-page suspension, explicit retry and cursor-cycle errors.
6. Expire the NAS session during page loading, library selection and source resolution;
   verify that login is offered, while a storage-link HTTP 403 remains a playback
   retry rather than incorrectly clearing the NAS session.

No credentials, real media snapshots or NAS modifications are part of these checks.
