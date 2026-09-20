# fnOS Trim media API research

The Android adapter in [`FnApi.java`](../app/src/main/java/com/fnvideo/app/FnApi.java) is based on the downloaded production bundle in `research/assets/`. No NAS data, password, session token, or cookie value is stored in this repository.

## Authentication and request signing

The web bundle stores the login result token in the `Trim-MC-token` cookie and sends that value as the raw `Authorization` header. It does not prepend `Bearer`.

Evidence: `research/assets/14a77ac952fc83b785b65592c335e16d-DmSmAgAZ.js`, around offsets `191 835` and `197 763`.

The WebView login flow should therefore read the cookie with Android `CookieManager` after the official login completes, then pass the raw value to `new FnApi(serverBase, token)`. The native adapter does not accept or store a username or password.

The API client signs requests with an `authx` header (the value is a query-string-shaped nonce/timestamp/sign tuple):

- GET payload: sorted `URLSearchParams`, with null values omitted; the signature payload is `decodeURIComponent(query)`.
- POST payload: the exact UTF 8 `JSON.stringify(body)` sent over the wire.
- `nonce`: random six digit integer from `100 000` through `999 999`.
- `timestamp`: current Unix time in milliseconds.
- `sign`: lower case MD5 of `NDzZTVxnRKP8Z0jXg1VAMonaG8akvh_<pathname>_<nonce>_<timestamp>_<MD5(payload)>_16CCEB3D-AB42-077D-36A1-F355324E4237`.

The adapter uses `X-Trim-Client: android-media` and `X-Trim-Client-Version: 629`. The public API key above is embedded in the web bundle and is a client protocol value; it is not a user credential.

Evidence: the `gu` signing function and request builder in `14a77ac952fc83b785b65592c335e16d-DmSmAgAZ.js`, around offsets `177 977` and `168 318`.

## Library and feed contracts

All routes below are under `/v/api/v1`.

| Adapter operation | HTTP route | Request | Response used |
| --- | --- | --- | --- |
| `libraries()` | `GET /mdb/list` | no body | `data` array; each library uses `guid` and `title`/`name` |
| `page(query, cursor)` | `POST /item/list` | `ancestor_guid`, `tags.type = ["Movie", "Video", "Episode"]`, `sort_type`, `sort_column`, `exclude_grouped_video`, `page`, `page_size = 50` | `data.list`, `data.total` |
| search page | `GET /search/list?q=...` | signed `q` query | array or `data.list`; the observed route has no page cursor |

The web list screen sends `ancestor_guid` or `parent_guid`, `tags`, `sort_type`, `sort_column`, `exclude_grouped_video`, `page`, and `page_size`. Evidence: `research/assets/dba38fcc7e14373d7386c091144609ff-BS08UET6.js`, around offsets `4 026` and `17 421`.

FeedPolicy owns eligibility; the adapter maps Query.mediaTypes into NAS tags and FeedState filters containers. The v1 app feed deliberately asks for movies, ordinary videos, and directly playable episodes and drops `Directory`, `TV`, and `Season` containers before they reach the player. That prevents a TV/library container from being treated as a playable item; series navigation is outside this bounded adapter contract.

Poster paths follow the web helper: relative values are prefixed with `/v/api/v1/sys/img` and receive `?w=400`; absolute URLs are preserved. Evidence: `research/assets/74c95604043427f0bee1d0e16bfa53af-Cc0bUiN5.js`, around offset `352 438`.

## Playback contract

`resolve(video)` follows the observed player sequence:

1. `POST /play/info` with `{ "item_guid": "<guid>" }`. The adapter requires `data.media_guid`.
2. `POST /stream` with the exact observed shape:

   ```json
   {
     "media_guid": "<media guid>",
     "ip": "<opaque per-client visitor id>",
     "header": {"User-Agent": ["FnVideo/1.0 (Android)"]},
     "level": 1
   }
   ```

   `level: 1` is the bundle's `Optional` enum (`Required = 0`, `Optional = 1`, `Debug = -1`). The browser supplies a FingerprintJS `visitorId` as `ip`; the adapter uses an in-memory opaque value for this request field and never persists it.
3. If `data.direct_link_qualities` contains a URL, return the first server-provided URL directly. These links are treated as signed playback links and receive no session header.
4. Otherwise, build the web player's `POST /play/play` body from the returned `video_stream`, `audio_streams`, `subtitle_streams`, and first `qualities` entry, then require `data.play_link`. The Android adapter uses `/v/api/v1/media/range/<media_guid>?playlink=<play_link>` for a non-HLS fallback link, matching the web player's `j2` helper; HLS links remain direct.

The observed fallback body is:

```json
{
  "media_guid": "...",
  "video_guid": "...",
  "video_encoder": "...",
  "resolution": "...",
  "bitrate": 0,
  "startTimestamp": 0,
  "audio_encoder": "...",
  "audio_guid": "...",
  "subtitle_guid": "...",
  "channels": 0
}
```

Evidence for the stream request and `Optional` value: `research/assets/74c95604043427f0bee1d0e16bfa53af-Cc0bUiN5.js`, around offsets `871 506` and `871 721`; the enum is defined in `14a77ac952fc83b785b65592c335e16d-DmSmAgAZ.js`, around offset `161 698`. Evidence for the exact `play.play` fields and `data.play_link`: `research/assets/bf29a647181e25c03987ab8cc8f81a1a-BnFlkbky.js`, around offset `567 110`.

The returned `MediaRepository.Source` is atomic: URL, headers, and inferred MIME type travel together. Direct links carry only the User-Agent used when requesting the stream, with no NAS session headers. Non-direct NAS play links carry the web player's required `Play-Link` (the original play link, not the range wrapper URL) and raw `Authorization` values only when the resolved URL is the NAS origin. The Android media datasource must strip sensitive headers on any cross-origin redirect; they must never be sent to a cloud-storage host. The adapter sets `startTimestamp` to zero because the Android activity owns local resume positions. Evidence for the fallback URL and headers is `research/assets/74c95604043427f0bee1d0e16bfa53af-Cc0bUiN5.js`, around offsets `1 376 979` and `1 376 315` (`media/range`, `Play-Link`, and `Authorization`).

## Validation and limits

Public probes confirmed that unsigned or incorrectly signed API calls are rejected, while `/v/api/v1/sys/version` is public. A logged-in browser session successfully played a normal movie, but its cookie value was not extracted or retained. Subsequent NAS Android testing with the user-authenticated app verified live library requests, stream resolution, and rendered playback. The container required H.264/AAC fallback for HEVC Main10. HLS uses the original play_link and Media3 MIME application/x-mpegURL, without the media/range wrapper.

When a remote stream response omits the fields required to construct the observed `play.play` request, `FnApi` fails with a credential-free diagnostic instead of inventing a transcoding protocol. Search results are returned as one page because the observed `/search/list` route did not expose pagination. Direct-link quality selection uses the first server-provided quality; a future quality selector can make that choice explicit without changing the request contract.
