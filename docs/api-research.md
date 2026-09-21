# fnOS Trim media API research

The Android adapter in [`FnApi.java`](../app/src/main/java/com/fnvideo/app/FnApi.java) is based on the downloaded production bundle in `research/assets/`. No NAS data, password, session token, or cookie value is stored in this repository.

## Authentication and request signing

The Android client now logs in through the native `LoginActivity` form and `FnApi.LoginCall`; it does not embed the web login in a WebView or read a `CookieManager` cookie. The form sends the entered username and a SHA-256 password digest to `/api/v2/user/loginByPassword` with `app_name=trimemedia-web`. The returned raw token is handed back to `MainActivity`, then encrypted by `SessionStore` and passed to `new FnApi(serverBase, token)` for API requests. The password and digest are transient; the adapter accepts them only for the login call and does not retain them.

The web bundle remains the evidence for the media API's raw `Authorization` convention: when a session token is available, `FnApi` sends it without a `Bearer` prefix.

Evidence for the web request convention: `research/assets/14a77ac952fc83b785b65592c335e16d-DmSmAgAZ.js`, around offsets `191 835` and `197 763`. The native login path is implemented in [`LoginActivity.java`](../app/src/main/java/com/fnvideo/app/LoginActivity.java) and [`FnApi.java`](../app/src/main/java/com/fnvideo/app/FnApi.java).

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
| `catalogPage(query, cursor)` | `POST /item/list` | `ancestor_guid`, `tags.type = ["Movie", "TV", "Video"]` for an all-kind catalog, or one observed work type for `Query.kind`; same sort and page fields | `data.list`, `data.total` when present; ordinary catalog rows exclude `Episode` |
| feed/search page | `GET /search/list?q=...` | signed `q` query | array or `data.list`; the observed route has no page cursor |

The web list screen sends `ancestor_guid` or `parent_guid`, `tags`, `sort_type`, `sort_column`, `exclude_grouped_video`, `page`, and `page_size`. Evidence: `research/assets/dba38fcc7e14373d7386c091144609ff-BS08UET6.js`, around offsets `4 026` and `17 421`.

`page(Query, cursor)` preserves the Feed contract: `Query.mediaTypes` maps to `Movie`, `Video`, and `Episode`, and FeedState keeps only playable rows. `catalogPage(Query, cursor)` is a separate work catalog contract: an empty `Query.kind` asks for the observed `Movie`, `TV`, and `Video` work types; `movie`, `tv`/`series`, and `video` narrow that set. Ordinary catalog pages exclude `Episode` so a series is represented by its TV work row rather than one poster per episode. When the catalog list omits `total`, a full 50-row response yields another cursor and only a short or empty page establishes the boundary. A search with an all-kind query is the documented exception: because the observed search route does not provide a reliable parent mapping, an Episode-only hit remains in the one-page result as a possible episode entry. A kind-specific search still filters to that kind.

`seriesEpisodes(Video)` reuses `POST /item/list` with `parent_guid`, ascending `episode` order, and `page/page_size=50`. It accepts either direct Episode children or Season containers; Season containers are queried again with the same observed route. Pages continue until a trustworthy `total`, `has_more`, or short/empty page boundary is reached, and rows are deduplicated by stable item id. A repeated page, contradictory pagination metadata, missing stable id, or reported total that cannot be reached produces an explicit adapter failure. The traversal has a 512-page-per-container, 16-level, and 2,048-container safety budget. TV and Season containers are never passed to `resolve()`.

`Video` also carries the observed list metadata needed by the catalog (`overview`, string `year`, `seriesId`, and `seasonId`). There is no separately verified item-details route in this adapter; `MediaRepository.details(Video)` therefore defaults to returning the already-read model rather than inventing `/item/info` or another endpoint.

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

Earlier baseline probes confirmed that unsigned or incorrectly signed API calls are rejected, while `/v/api/v1/sys/version` is public. A logged-in browser session successfully played a normal movie, but its cookie value was not extracted or retained. Earlier NAS Android testing with the user-authenticated app verified live library requests, stream resolution, and rendered playback. The container required H.264/AAC fallback for HEVC Main10. HLS uses the original play_link and Media3 MIME application/x-mpegURL, without the media/range wrapper.

When a remote stream response omits the fields required to construct the observed `play.play` request, `FnApi` fails with a credential-free diagnostic instead of inventing a transcoding protocol. Search results are returned as one page because the observed `/search/list` route does not expose pagination; all-kind search retains an unmerged Episode hit as described above. Direct-link quality selection uses the first server-provided quality; a future quality selector can make that choice explicit without changing the request contract.

The catalog and Season traversal behavior in this revision is covered by synthetic JVM contract tests. This revision has not been verified against a real multi-season NAS library, so the exact server hierarchy and long-list behavior remain an integration acceptance item.
