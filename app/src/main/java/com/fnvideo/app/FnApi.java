package com.fnvideo.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Media and explicit password-login client for the Trim/FnOS API.
 *
 * <p>The web client signs every API request with the authx header.
 * This class keeps that protocol inside the adapter. The token is only sent
 * to the NAS API. Playback headers are returned atomically with the source;
 * PlaybackDataSource scopes them to the NAS origin on every request.</p>
 */
public final class FnApi implements MediaRepository {
    private static final String API_V1 = "/api/v1";
    private static final String AUTH_SALT = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String API_KEY = "16CCEB3D-AB42-077D-36A1-F355324E4237";
    private static final String CLIENT = "android-media";
    private static final String CLIENT_VERSION = "629";
    private static final String DEFAULT_USER_AGENT = "FnVideo/1.0 (Android)";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_EPISODE_PAGES = 512;
    private static final int MAX_EPISODE_CONTAINER_DEPTH = 16;
    private static final int MAX_EPISODE_CONTAINERS = 2_048;
    private static final int MAX_COMPATIBLE_AUDIO_CHANNELS = 2;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final String baseUrl;
    private final String token;
    private final String visitorId;

    /**
     * @param baseUrl server URL, normally {@code http://host:5666/v}
     * @param token raw value of the {@code Trim-MC-token} cookie
     */
    public FnApi(String baseUrl, String token) {
        this.baseUrl = ServerAddress.normalize(baseUrl) + "/v";
        this.token = token == null ? "" : token.trim();
        // The web client sends a FingerprintJS visitorId as the stream "ip".
        // Keep a per-client opaque value without persisting or exposing it.
        this.visitorId = UUID.randomUUID().toString().replace("-", "");
    }

    /** Native password login, matching the NAS v2 web contract. */
    public static String login(String origin, String username, char[] password) throws IOException {
        return new LoginCall(origin, username, password).execute();
    }

    /** Owns and clears the supplied password even if cancelled before execute(). */
    public static final class LoginCall {
        private final String origin;
        private final String username;
        private final char[] password;
        private volatile boolean cancelled;
        private volatile okhttp3.Call call;

        public LoginCall(String origin, String username, char[] password) {
            this.origin = origin;
            this.username = username;
            this.password = password;
        }

        public void cancel() {
            cancelled = true;
            okhttp3.Call running = call;
            if (running != null) running.cancel();
            clearPassword();
        }

        private void clearPassword() {
            if (password != null) java.util.Arrays.fill(password, '\0');
        }

        public String execute() throws IOException {
            try {
                if (cancelled) throw new LoginFailure("登录已取消");
                if (username == null || username.trim().isEmpty() || password == null || password.length == 0) {
                    throw new LoginFailure("请输入用户名和密码");
                }
                java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                byte[] digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                } finally {
                    java.util.Arrays.fill(bytes, (byte) 0);
                    if (encoded.hasArray()) java.util.Arrays.fill(encoded.array(), (byte) 0);
                    clearPassword();
                }
                StringBuilder hash = new StringBuilder(64);
                for (byte value : digest) hash.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
                JSONObject body = new JSONObject();
                body.put("username", username.trim());
                body.put("password", hash.toString());
                body.put("app_name", "trimemedia-web");
                FnApi api = new FnApi(origin, "");
                URL url = new URL(api.apiUrl("/api/v2/user/loginByPassword"));
                String json = body.toString();
                okhttp3.Request request = new okhttp3.Request.Builder().url(url)
                        .header("Accept", "application/json")
                        .header("X-Trim-Client", CLIENT).header("X-Trim-Client-Version", CLIENT_VERSION)
                        .header("authx", api.authx("POST", url, "", json))
                        .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.get("application/json; charset=UTF-8")))
                        .build();
                call = LOGIN_CLIENT.newCall(request);
                if (cancelled) { call.cancel(); throw new LoginFailure("登录已取消"); }
                try (okhttp3.Response response = call.execute()) {
                    int status = response.code();
                    if (status >= 300 && status < 400) throw new LoginFailure("服务器要求跳转，请直接填写最终影视服务地址");
                    if (status == 404) throw new LoginFailure("此服务不支持密码登录，请检查地址或升级飞牛影视");
                    if (status == 429) throw new LoginFailure("登录尝试过于频繁，请稍后重试");
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new LoginFailure("登录失败，请检查用户名、密码及影视访问权限");
                    }
                    // Bound response size and never surface server text or credential echoes.
                    JSONObject envelope = new JSONObject(response.peekBody(64 * 1024).string());
                    int code = envelope.optInt("code", 0);
                    if (code == 0) code = envelope.optInt("errno", 0);
                    if (code != 0) throw new LoginFailure("登录失败，请检查用户名、密码及影视访问权限");
                    JSONObject data = envelope.has("data") ? envelope.optJSONObject("data") : envelope;
                    if (data == null) throw new LoginFailure("登录响应无效，请检查影视服务版本");
                    String token = firstString(data, "token", "access_token");
                    if (token.isEmpty()) throw new LoginFailure("登录未返回会话，请检查账号的影视访问权限");
                    if (cancelled) throw new LoginFailure("登录已取消");
                    return token;
                }
            } catch (LoginFailure error) {
                throw error;
            } catch (JSONException error) {
                throw new LoginFailure("登录响应无效，请检查影视服务版本");
            } catch (javax.net.ssl.SSLException error) {
                throw new LoginFailure("无法验证服务器证书，请检查 HTTPS 配置");
            } catch (Exception error) {
                throw new LoginFailure(cancelled ? "登录已取消" : "无法完成登录，请检查地址、网络和影视服务状态");
            } finally {
                clearPassword();
            }
        }
    }

    private static final okhttp3.OkHttpClient LOGIN_CLIENT = new okhttp3.OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build();

    private static final class LoginFailure extends IOException {
        LoginFailure(String message) { super(message); }
    }

    @Override
    public List<Library> libraries() throws Exception {
        Object data = requestData("GET", API_V1 + "/mdb/list", null, null);
        JSONArray values = asArray(data, "library list");
        List<Library> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) {
                continue;
            }
            String id = firstString(value, "guid", "id");
            if (id.isEmpty()) {
                continue;
            }
            String title = firstString(value, "title", "name", "label");
            result.add(new Library(id, title.isEmpty() ? id : title));
        }
        return result;
    }

    @Override
    public Page page(Query query, String cursor) throws Exception {
        Query effective = query == null ? new Query("", "") : query;
        if (!effective.query.isEmpty()) {
            // The observed search route is GET /search/list?q=... and does
            // not return a server page cursor. Keep the response as one page.
            Map<String, String> params = new LinkedHashMap<>();
            params.put("q", effective.query);
            Object data = requestData("GET", API_V1 + "/search/list", params, null);
            JSONArray values = listArray(data);
            return new Page(parseVideos(values), "");
        }

        int pageNumber = parsePage(cursor);
        JSONObject body = new JSONObject();
        if (!effective.libraryId.isEmpty()) {
            body.put("ancestor_guid", effective.libraryId);
        }
        JSONObject tags = new JSONObject();
        // V1 feed scope intentionally contains playable movie/video items.
        // TV and directory containers are not playable media entries.
        tags.put("type", new JSONArray(effective.mediaTypes));
        body.put("tags", tags);
        body.put("sort_type", "DESC");
        body.put("sort_column", "create_time");
        body.put("exclude_grouped_video", 1);
        body.put("page", pageNumber);
        body.put("page_size", PAGE_SIZE);

        Object data = requestData("POST", API_V1 + "/item/list", null, body);
        JSONObject listing = asObject(data, "item list");
        JSONArray values = listing.optJSONArray("list");
        if (values == null) {
            values = new JSONArray();
        }
        List<Video> result = parseVideos(values);
        int total = listing.optInt("total", values.length());
        String next = total > pageNumber * PAGE_SIZE ? String.valueOf(pageNumber + 1) : "";
        return new Page(result, next);
    }

    @Override
    public Page catalogPage(Query query, String cursor) throws Exception {
        Query effective = query == null ? new Query("", "") : query;
        if (!effective.query.isEmpty()) {
            // Search is the only observed global search route. It has no
            // verified cursor, so keep it as one page. A global search may
            // return only an episode when no reliable parent mapping exists;
            // retain that entry so the caller can expose it as a play target.
            Map<String, String> params = new LinkedHashMap<>();
            params.put("q", effective.query);
            Object data = requestData("GET", API_V1 + "/search/list", params, null);
            List<Video> results = catalogSearchResults(parseVideos(listArray(data)), effective.kind);
            return new Page(results, "");
        }

        int pageNumber = parsePage(cursor);
        JSONObject body = new JSONObject();
        if (!effective.libraryId.isEmpty()) {
            body.put("ancestor_guid", effective.libraryId);
        }
        JSONObject tags = new JSONObject();
        tags.put("type", new JSONArray(catalogTypes(effective.kind)));
        body.put("tags", tags);
        body.put("sort_type", "DESC");
        body.put("sort_column", "create_time");
        body.put("exclude_grouped_video", 1);
        body.put("page", pageNumber);
        body.put("page_size", PAGE_SIZE);

        Object data = requestData("POST", API_V1 + "/item/list", null, body);
        JSONObject listing = asObject(data, "catalog item list");
        JSONArray values = listing.optJSONArray("list");
        if (values == null) {
            values = new JSONArray();
        }
        List<Video> works = catalogWorks(parseVideos(values), effective.kind);
        String next = catalogNextCursor(listing, values.length(), pageNumber);
        return new Page(works, next);
    }

    @Override
    public Source resolve(Video video) throws Exception {
        return resolve(video, false);
    }

    @Override
    public Source resolveCompatible(Video video) throws Exception {
        return resolve(video, true);
    }

    @Override
    public List<Video> seriesEpisodes(Video series) throws Exception {
        if (series == null || safe(series.id).isEmpty()) {
            throw new FnApiException("Cannot list episodes: series id is empty");
        }

        // A series may expose seasons as children while another server
        // revision exposes episodes directly. Walk both shapes through the
        // observed /item/list route, keeping the stable item id as identity.
        LinkedHashMap<String, Video> episodes = new LinkedHashMap<>();
        java.util.HashSet<String> visitedContainers = new java.util.HashSet<>();
        String seriesId = safe(series.seriesId);
        if (seriesId.isEmpty() && isSeasonContainer(series)) {
            seriesId = safe(series.parentId);
        }
        if (seriesId.isEmpty() && !isSeasonContainer(series)) {
            seriesId = series.id;
        }
        TraversalBudget budget = new TraversalBudget();
        collectEpisodeChildren(series.id, seriesId,
                isSeasonContainer(series) ? series : null,
                episodes, visitedContainers, 0, budget);

        List<Video> result = new ArrayList<>(episodes.values());
        result.sort(SERIES_ORDER);
        return result;
    }

    private static final java.util.Comparator<MediaRepository.Video> SERIES_ORDER =
            java.util.Comparator.comparingInt((MediaRepository.Video video) -> video.season)
                    .thenComparingInt(video -> video.episode);

    private void collectEpisodeChildren(String parentId, String seriesId,
                                        Video seasonContainer,
                                        LinkedHashMap<String, Video> episodes,
                                        java.util.Set<String> visitedContainers,
                                        int depth, TraversalBudget budget) throws Exception {
        if (depth > MAX_EPISODE_CONTAINER_DEPTH) {
            throw new FnApiException("NAS episode container nesting exceeds the safety limit");
        }
        if (!visitedContainers.add(parentId)) {
            return;
        }
        budget.containers++;
        if (budget.containers > MAX_EPISODE_CONTAINERS) {
            throw new FnApiException("NAS episode catalog contains too many containers");
        }
        List<Video> children = readAllChildren(parentId);
        for (Video child : children) {
            if (isSeasonContainer(child)) {
                String childSeriesId = safe(child.seriesId);
                if (childSeriesId.isEmpty()) {
                    childSeriesId = seriesId;
                }
                collectEpisodeChildren(child.id, childSeriesId, child,
                        episodes, visitedContainers, depth + 1, budget);
                continue;
            }
            if (isContainer(child) || !isEpisodeLike(child, seasonContainer)) {
                continue;
            }
            if (safe(child.seriesId).isEmpty()) {
                child.seriesId = safe(seriesId);
            }
            if (seasonContainer != null) {
                if (safe(child.seasonId).isEmpty()) {
                    child.seasonId = seasonContainer.id;
                }
                if (child.season == 0 && seasonContainer.season > 0) {
                    child.season = seasonContainer.season;
                }
                if (safe(child.parentId).isEmpty()) {
                    child.parentId = seasonContainer.id;
                }
            } else if (safe(child.parentId).isEmpty()) {
                child.parentId = parentId;
            }
            // A response without a stable id is rejected by readAllChildren;
            // this guard keeps the map contract explicit if that code changes.
            if (safe(child.id).isEmpty()) {
                throw new FnApiException("Episode list contains an item without a stable id");
            }
            episodes.putIfAbsent(child.id, child);
        }
    }

    private List<Video> readAllChildren(String parentId) throws Exception {
        LinkedHashMap<String, Video> items = new LinkedHashMap<>();
        long expectedTotal = -1L;
        for (int pageNumber = 1; pageNumber <= MAX_EPISODE_PAGES; pageNumber++) {
            ItemPage page = fetchChildrenPage(parentId, pageNumber);
            if (page.totalPresent) {
                if (page.total < 0L || (page.total == 0L && !page.items.isEmpty())) {
                    throw new FnApiException("NAS returned an invalid episode total");
                }
                expectedTotal = page.total;
            }

            int rows = page.items.size();
            int newItems = 0;
            for (Video item : page.items) {
                if (items.putIfAbsent(item.id, item) == null) {
                    newItems++;
                }
            }
            // A repeated full page usually means the server ignored `page`.
            // Returning it would make the catalog look complete while silently
            // dropping the remaining episodes.
            if (pageNumber > 1 && rows > 0 && newItems == 0) {
                throw new FnApiException("NAS repeated an episode page; pagination is incomplete");
            }

            if (rows == 0) {
                if (page.hasMorePresent && page.hasMore) {
                    throw new FnApiException("NAS reported more episodes after an empty page");
                }
                if (expectedTotal >= 0L && items.size() < expectedTotal) {
                    throw new FnApiException("NAS episode pagination ended before the reported total");
                }
                return new ArrayList<>(items.values());
            }

            if (page.hasMorePresent && !page.hasMore) {
                if (expectedTotal >= 0L && items.size() < expectedTotal) {
                    throw new FnApiException("NAS ended episode pagination before the reported total");
                }
                return new ArrayList<>(items.values());
            }
            // A duplicate row must not satisfy `total`: the service can count
            // it again while the final stable ID is still on a later page.
            // Also keep walking when has_more=true contradicts the total.
            if (expectedTotal >= 0L && items.size() >= expectedTotal
                    && (!page.hasMorePresent || !page.hasMore)) {
                return new ArrayList<>(items.values());
            }
            if (!page.hasMorePresent && expectedTotal < 0L && rows < PAGE_SIZE) {
                // A short page is the only observed end marker when the
                // service omits total/has_more.
                return new ArrayList<>(items.values());
            }
        }
        throw new FnApiException("NAS episode pagination exceeded the safety limit");
    }

    private ItemPage fetchChildrenPage(String parentId, int pageNumber) throws Exception {
        // The web season screen uses parent_guid on POST /item/list. Keep the
        // request shape evidence-backed and only add the page fields required
        // to walk long directories.
        JSONObject body = new JSONObject();
        body.put("parent_guid", parentId);
        body.put("sort_type", "ASC");
        body.put("sort_column", "episode");
        body.put("exclude_grouped_video", 1);
        body.put("page", pageNumber);
        body.put("page_size", PAGE_SIZE);

        Object data = requestData("POST", API_V1 + "/item/list", null, body);
        JSONObject listing = asObject(data, "episode list");
        JSONArray values = listing.optJSONArray("list");
        if (values == null) {
            values = new JSONArray();
        }
        List<Video> items = parseVideos(values);
        if (items.size() != values.length()) {
            throw new FnApiException("NAS episode list contains an item without a stable id");
        }

        long total = -1L;
        boolean totalPresent = listing.has("total") && !listing.isNull("total");
        if (totalPresent) {
            Object rawTotal = listing.opt("total");
            try {
                total = rawTotal instanceof Number
                        ? ((Number) rawTotal).longValue()
                        : Long.parseLong(String.valueOf(rawTotal));
            } catch (NumberFormatException error) {
                throw new FnApiException("NAS returned an invalid episode total", 0, 0, error);
            }
        }

        Boolean hasMore = optionalBoolean(listing, "has_more", "hasMore");
        return new ItemPage(items, totalPresent, total,
                hasMore != null, hasMore != null && hasMore);
    }

    private static final class ItemPage {
        final List<Video> items;
        final boolean totalPresent;
        final long total;
        final boolean hasMorePresent;
        final boolean hasMore;

        ItemPage(List<Video> items, boolean totalPresent, long total,
                 boolean hasMorePresent, boolean hasMore) {
            this.items = items;
            this.totalPresent = totalPresent;
            this.total = total;
            this.hasMorePresent = hasMorePresent;
            this.hasMore = hasMore;
        }
    }

    private static final class TraversalBudget {
        int containers;
    }

    private static Boolean optionalBoolean(JSONObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key) || object.isNull(key)) {
                continue;
            }
            Object raw = object.opt(key);
            if (raw instanceof Boolean) {
                return (Boolean) raw;
            }
            if (raw != null) {
                String value = String.valueOf(raw).trim();
                if ("true".equalsIgnoreCase(value)) return true;
                if ("false".equalsIgnoreCase(value)) return false;
            }
        }
        return null;
    }

    private static List<String> catalogTypes(String kind) throws FnApiException {
        String normalized = safe(kind).toLowerCase(java.util.Locale.US);
        List<String> types = new ArrayList<>();
        if (normalized.isEmpty() || "all".equals(normalized) || "*".equals(normalized)) {
            types.add("Movie");
            types.add("TV");
            types.add("Video");
            return types;
        }
        if ("movie".equals(normalized)) {
            types.add("Movie");
        } else if ("tv".equals(normalized) || "series".equals(normalized)
                || "show".equals(normalized)) {
            // TV is the observed server type for a series container.
            types.add("TV");
        } else if ("video".equals(normalized)) {
            types.add("Video");
        } else {
            throw new FnApiException("Unsupported catalog kind: " + kind);
        }
        return types;
    }

    private static List<Video> catalogWorks(List<Video> values, String kind) throws Exception {
        List<String> allowedTypes = catalogTypes(kind);
        List<Video> result = new ArrayList<>();
        for (Video value : values) {
            String type = safe(value.type);
            boolean matches = false;
            for (String allowed : allowedTypes) {
                if (allowed.equalsIgnoreCase(type)) {
                    matches = true;
                    break;
                }
            }
            if (matches) {
                result.add(value);
            }
        }
        return result;
    }

    private static List<Video> catalogSearchResults(List<Video> values, String kind) throws Exception {
        String normalized = safe(kind).toLowerCase(java.util.Locale.US);
        if (normalized.isEmpty() || "all".equals(normalized) || "*".equals(normalized)) {
            return values;
        }
        return catalogWorks(values, kind);
    }

    private static String catalogNextCursor(JSONObject listing, int rowCount, int pageNumber)
            throws FnApiException {
        Object rawTotal = listing.opt("total");
        if (rawTotal != null && rawTotal != JSONObject.NULL) {
            long total;
            try {
                total = rawTotal instanceof Number
                        ? ((Number) rawTotal).longValue()
                        : Long.parseLong(String.valueOf(rawTotal));
            } catch (NumberFormatException error) {
                throw new FnApiException("NAS returned an invalid catalog total", 0, 0, error);
            }
            if (total < 0L) {
                throw new FnApiException("NAS returned an invalid catalog total");
            }
            return total > pageNumber * PAGE_SIZE ? String.valueOf(pageNumber + 1) : "";
        }
        // Without a total, a full page is not evidence of the end. Expose the
        // next page cursor and let the following short/empty page establish a
        // clear boundary.
        return rowCount >= PAGE_SIZE ? String.valueOf(pageNumber + 1) : "";
    }

    private static boolean isSeasonContainer(Video video) {
        return video != null && "season".equalsIgnoreCase(safe(video.type));
    }

    private static boolean isContainer(Video video) {
        if (video == null) return false;
        String type = safe(video.type).toLowerCase(java.util.Locale.US);
        return "tv".equals(type) || "series".equals(type) || "season".equals(type)
                || "directory".equals(type) || "folder".equals(type)
                || "collection".equals(type) || "library".equals(type)
                || "show".equals(type);
    }

    private static boolean isEpisodeLike(Video video, Video seasonContainer) {
        if (video == null || isContainer(video)) return false;
        String type = safe(video.type);
        if ("episode".equalsIgnoreCase(type)) return true;
        if (seasonContainer != null && "video".equalsIgnoreCase(type)) return true;
        return video.episode > 0 || !safe(video.seriesId).isEmpty()
                || !safe(video.seasonId).isEmpty();
    }

    private Source resolve(Video video, boolean forceCompatible) throws Exception {
        if (video == null || safe(video.id).isEmpty()) {
            throw new FnApiException("Cannot resolve playback: item id is empty");
        }
        if (isContainer(video)) {
            throw new FnApiException("Cannot resolve playback: item " + video.id
                    + " is a media container");
        }

        JSONObject infoBody = new JSONObject().put("item_guid", video.id);
        JSONObject info = asObject(
                requestData("POST", API_V1 + "/play/info", null, infoBody),
                "play info");
        String mediaGuid = firstString(info, "media_guid");
        if (mediaGuid.isEmpty()) {
            throw new FnApiException("Playback info for item " + video.id + " has no media_guid");
        }

        JSONObject streamBody = new JSONObject();
        streamBody.put("media_guid", mediaGuid);
        streamBody.put("ip", visitorId);
        streamBody.put("header", new JSONObject()
                .put("User-Agent", new JSONArray().put(DEFAULT_USER_AGENT)));
        // The source enum is Required=0, Optional=1, Debug=-1.
        streamBody.put("level", 1);
        JSONObject stream = asObject(
                requestData("POST", API_V1 + "/stream", null, streamBody),
                "stream playback");

        String directLink = firstDirectLink(stream);
        if (!forceCompatible && !directLink.isEmpty()) {
            return source(directLink);
        }

        // Direct links are unavailable for local media. Reproduce the web
        // client's play.play request from the observed stream descriptors.
        JSONObject videoStream = firstObject(stream, "video_stream");
        if (videoStream == null) {
            videoStream = firstObjectInArray(stream, "video_streams");
        }
        JSONArray audioStreams = firstArray(stream, "audio_streams");
        JSONObject audioStream = chooseDefaultStream(audioStreams,
                firstString(info, "audio_guid"));
        JSONArray subtitleStreams = firstArray(stream, "subtitle_streams");
        JSONObject subtitleStream = chooseDefaultStream(subtitleStreams,
                firstString(info, "subtitle_guid"));

        JSONObject quality = firstQuality(stream);
        String videoGuid = firstString(info, "video_guid");
        if (videoGuid.isEmpty() && videoStream != null) {
            videoGuid = firstString(videoStream, "guid");
        }
        if (videoGuid.isEmpty()) {
            throw new FnApiException("Stream response has no video_guid; transcoding contract is unavailable");
        }

        String resolution = quality == null ? "" : firstString(quality, "resolution");
        if (resolution.isEmpty() && videoStream != null) {
            resolution = firstString(videoStream, "resolution");
        }
        long bitrate = quality == null ? 0L : firstLong(quality, "bitrate", "bps");
        if (bitrate == 0L && videoStream != null) {
            bitrate = firstLong(videoStream, "bitrate", "bps");
        }
        String videoEncoder = forceCompatible
                ? "h264"
                : videoStream == null ? "" : encoder(firstString(videoStream, "codec_name", "codec"));
        String audioEncoder = audioStream == null
                ? ""
                : forceCompatible ? "aac" : encoder(firstString(audioStream, "codec_name", "codec"));
        int channels = audioStream == null ? 0 : firstInt(audioStream, "channels");
        if (forceCompatible) {
            // AAC compatibility output is limited to stereo. Preserve mono and
            // keep an absent audio stream represented as zero channels.
            channels = Math.max(0, Math.min(channels, MAX_COMPATIBLE_AUDIO_CHANNELS));
        }
        String audioGuid = audioStream == null ? "" : firstString(audioStream, "guid");
        String subtitleGuid = subtitleStream == null ? "" : firstString(subtitleStream, "guid");

        JSONObject playBody = new JSONObject();
        playBody.put("media_guid", mediaGuid);
        playBody.put("video_guid", videoGuid);
        playBody.put("video_encoder", videoEncoder);
        playBody.put("resolution", resolution);
        playBody.put("bitrate", bitrate);
        // The Android activity owns local resume positions. Sending the web
        // player's recorded ts here would seek twice on a resumed card.
        playBody.put("startTimestamp", 0L);
        playBody.put("audio_encoder", audioEncoder);
        playBody.put("audio_guid", audioGuid);
        playBody.put("subtitle_guid", subtitleGuid);
        playBody.put("channels", channels);

        JSONObject played = asObject(
                requestData("POST", API_V1 + "/play/play", null, playBody),
                "play link");
        String playLink = firstString(played, "play_link");
        if (playLink.isEmpty()) {
            throw new FnApiException("NAS returned no play_link for item " + video.id);
        }
        String mediaUrl = !forceCompatible && needsRangeEndpoint(stream, playLink)
                ? rangeUrl(mediaGuid, playLink) : playLink;
        Source resolved = source(mediaUrl, true, playLink);
        return forceCompatible
                ? new Source(resolved.url, resolved.headers, "application/x-mpegURL")
                : resolved;
    }

    private Source source(String link) throws Exception {
        return source(link, false, "");
    }

    private Source source(String link, boolean nasPlayLink) throws Exception {
        return source(link, nasPlayLink, link);
    }

    private Source source(String link, boolean nasPlayLink, String playLinkHeader) throws Exception {
        String resolved = resolveMediaUrl(link);
        Map<String, String> headers = new HashMap<>();
        // Cloud links may be bound to the User-Agent supplied to /stream.
        headers.put("User-Agent", DEFAULT_USER_AGENT);
        if (nasPlayLink && sameOrigin(resolved) && !token.isEmpty()) {
            // This is the exact fallback contract used by the web player. The
            // app's media datasource must strip these headers on cross-origin
            // redirects; direct cloud links deliberately receive no headers.
            headers.put("Play-Link", safe(playLinkHeader));
            headers.put("Authorization", token);
        }
        return new Source(resolved, headers, mimeType(resolved));
    }

    private boolean sameOrigin(String value) {
        return ServerAddress.sameOrigin(baseUrl, value);
    }

    private String rangeUrl(String mediaGuid, String playLink) throws Exception {
        return apiUrl("/media/range/" + encodePath(mediaGuid))
                + "?playlink=" + encode(playLink);
    }

    private static String encodePath(String value) throws Exception {
        return URLEncoder.encode(safe(value), StandardCharsets.UTF_8.name())
                .replace("+", "%20").replace("%2F", "/");
    }

    private static boolean needsRangeEndpoint(JSONObject stream, String playLink) {
        String lower = safe(playLink).toLowerCase(java.util.Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        if (lower.endsWith(".m3u8")) {
            return false;
        }
        JSONArray qualities = stream.optJSONArray("qualities");
        if (qualities != null && qualities.length() > 0) {
            JSONObject selected = qualities.optJSONObject(0);
            if (selected != null && selected.optBoolean("is_m3u8", false)) {
                return false;
            }
        }
        // This is the browser's raw/range fallback for a non-HLS play link.
        return true;
    }

    private Object requestData(String method, String path, Map<String, String> query,
                               JSONObject body) throws Exception {
        String bodyText = body == null ? "" : body.toString();
        String queryText = canonicalQuery(query);
        URL url = new URL(apiUrl(path) + (queryText.isEmpty() ? "" : "?" + queryText));
        String authx = authx(method, url, queryText, bodyText);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        int status;
        String response;
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Trim-Client", CLIENT);
            connection.setRequestProperty("X-Trim-Client-Version", CLIENT_VERSION);
            // The production bundle sends authx as a header, not as URL query
            // parameters. Keeping it in the header also preserves GET q exactly.
            connection.setRequestProperty("authx", authx);
            if (!token.isEmpty()) {
                // This is deliberately the raw Trim-MC-token value, without Bearer.
                connection.setRequestProperty("Authorization", token);
            }
            if (!bodyText.isEmpty()) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = bodyText.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            status = connection.getResponseCode();
            response = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        } finally {
            connection.disconnect();
        }
        if (status < 200 || status >= 300) {
            throw new FnApiException("NAS API " + path + " failed with HTTP " + status,
                    status, 0);
        }
        if (response.trim().isEmpty()) {
            throw new FnApiException("NAS API " + path + " returned an empty response", status, 0);
        }

        JSONObject envelope;
        try {
            envelope = new JSONObject(response);
        } catch (JSONException error) {
            throw new FnApiException("NAS API " + path + " returned invalid JSON", status, 0, error);
        }
        int code = envelope.optInt("code", 0);
        if (code == 0 && envelope.has("errno")) {
            code = envelope.optInt("errno", 0);
        }
        if (code != 0) {
            String message = firstString(envelope, "msg", "message", "error");
            if (message.isEmpty()) {
                message = code == -2 ? "authentication failed" : "request rejected";
            }
            throw new FnApiException("NAS API " + path + " rejected request: " + message,
                    status, code);
        }
        return envelope.has("data") ? envelope.opt("data") : envelope;
    }

    private String authx(String method, URL url, String query, String body) throws Exception {
        String upper = method.toUpperCase(java.util.Locale.US);
        String payload;
        if ("GET".equals(upper)) {
            payload = URLDecoder.decode(query, StandardCharsets.UTF_8.name());
        } else {
            payload = body;
        }
        String payloadMd5 = md5(payload);
        int nonce = ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
        long timestamp = System.currentTimeMillis();
        String path = new URI(url.toString()).getRawPath();
        String material = AUTH_SALT + "_" + path + "_" + nonce + "_"
                + timestamp + "_" + payloadMd5 + "_" + API_KEY;
        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + md5(material);
    }

    private String apiUrl(String path) {
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private String resolveMediaUrl(String link) throws Exception {
        String value = safe(link);
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        URL origin = new URL(baseUrl);
        if (value.startsWith("/")) {
            return new URL(origin.getProtocol(), origin.getHost(), origin.getPort(), value).toString();
        }
        return new URL(baseUrl + "/" + value).toString();
    }

    private String posterUrl(String value) {
        String poster = safe(value);
        if (poster.isEmpty() || poster.startsWith("http://") || poster.startsWith("https://")) {
            return poster;
        }
        String imageBase = apiUrl("/sys/img");
        if (poster.startsWith(imageBase + "/")) {
            return poster;
        }
        if (poster.startsWith("/v/api/v1/sys/img/")) {
            try {
                URL origin = new URL(baseUrl);
                return origin.getProtocol() + "://" + origin.getAuthority() + poster;
            } catch (Exception ignored) {
                return poster;
            }
        }
        String separator = poster.contains("?") ? "&" : "?";
        return imageBase + (poster.startsWith("/") ? "" : "/") + poster
                + separator + "w=400";
    }

    private List<Video> parseVideos(JSONArray values) {
        List<Video> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) {
                continue;
            }
            String type = firstString(value, "type", "category");
            String id = firstString(value, "guid", "id");
            if (id.isEmpty()) {
                continue;
            }
            Video video = new Video();
            video.id = id;
            video.type = type;
            video.title = firstString(value, "title", "name", "sort_title");
            video.overview = firstString(value, "overview", "description", "summary");
            video.subtitle = firstString(value, "subtitle", "sub_title");
            if (video.subtitle.isEmpty()) {
                video.subtitle = video.overview;
            }
            video.poster = posterUrl(firstPoster(value));
            video.year = firstString(value, "year", "release_year", "publish_year");
            video.season = firstInt(value, "season", "season_number");
            video.episode = firstInt(value, "episode", "episode_number");
            video.parentId = firstString(value, "parent_guid", "ancestor_guid");
            video.seriesId = firstString(value, "series_guid", "series_id", "tv_guid");
            video.seasonId = firstString(value, "season_guid", "season_id");
            result.add(video);
        }
        return result;
    }

    private static JSONArray listArray(Object data) throws FnApiException {
        if (data instanceof JSONArray) {
            return (JSONArray) data;
        }
        if (data instanceof JSONObject) {
            JSONArray list = ((JSONObject) data).optJSONArray("list");
            if (list != null) {
                return list;
            }
        }
        throw new FnApiException("NAS returned a non-list search response");
    }

    private static JSONArray asArray(Object data, String label) throws FnApiException {
        if (data instanceof JSONArray) {
            return (JSONArray) data;
        }
        if (data instanceof JSONObject) {
            JSONObject object = (JSONObject) data;
            JSONArray list = object.optJSONArray("list");
            if (list != null) {
                return list;
            }
        }
        throw new FnApiException("NAS returned an invalid " + label + " response");
    }

    private static JSONObject asObject(Object data, String label) throws FnApiException {
        if (data instanceof JSONObject) {
            return (JSONObject) data;
        }
        throw new FnApiException("NAS returned an invalid " + label + " response");
    }

    private static String firstDirectLink(JSONObject data) {
        JSONArray qualities = data.optJSONArray("direct_link_qualities");
        if (qualities == null) {
            return "";
        }
        for (int i = 0; i < qualities.length(); i++) {
            JSONObject quality = qualities.optJSONObject(i);
            if (quality != null) {
                String url = firstString(quality, "url");
                if (!url.isEmpty()) {
                    return url;
                }
            }
        }
        return "";
    }

    private static JSONObject firstQuality(JSONObject data) {
        JSONArray values = data.optJSONArray("qualities");
        if (values == null || values.length() == 0) {
            return null;
        }
        // Browser playback starts with its selected quality; v1 has no UI
        // quality preference, so preserve server order and choose the first.
        return values.optJSONObject(0);
    }

    private static JSONObject firstObject(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    private static JSONObject firstObjectInArray(JSONObject object, String key) {
        JSONArray values = object.optJSONArray(key);
        return values == null ? null : values.optJSONObject(0);
    }

    private static JSONArray firstArray(JSONObject object, String key) {
        JSONArray value = object.optJSONArray(key);
        return value == null ? new JSONArray() : value;
    }

    private static JSONObject chooseDefaultStream(JSONArray values, String preferredGuid) {
        if (!preferredGuid.isEmpty()) {
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.optJSONObject(i);
                if (value != null && preferredGuid.equals(firstString(value, "guid"))) {
                    return value;
                }
            }
        }
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null && value.optBoolean("is_default", false)) {
                return value;
            }
        }
        return values.length() == 0 ? null : values.optJSONObject(0);
    }

    private static String firstPoster(JSONObject value) {
        String poster = firstString(value, "poster");
        if (!poster.isEmpty()) {
            return poster;
        }
        JSONArray posters = value.optJSONArray("posters");
        if (posters != null && posters.length() > 0) {
            return safe(posters.optString(0, ""));
        }
        return "";
    }

    private static String encoder(String codec) {
        return safe(codec).toLowerCase(java.util.Locale.US);
    }

    private static int parsePage(String cursor) throws FnApiException {
        if (safe(cursor).isEmpty()) {
            return 1;
        }
        try {
            int page = Integer.parseInt(cursor);
            if (page < 1) {
                throw new NumberFormatException();
            }
            return page;
        } catch (NumberFormatException error) {
            throw new FnApiException("Invalid page cursor");
        }
    }

    private static String firstString(JSONObject value, String... keys) {
        for (String key : keys) {
            Object raw = value.opt(key);
            if (raw == null || raw == JSONObject.NULL) {
                continue;
            }
            String text = String.valueOf(raw).trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return "";
    }

    private static long firstLong(JSONObject value, String... keys) {
        for (String key : keys) {
            Object raw = value.opt(key);
            if (raw instanceof Number) {
                return ((Number) raw).longValue();
            }
            try {
                if (raw != null && raw != JSONObject.NULL) {
                    return Long.parseLong(String.valueOf(raw));
                }
            } catch (NumberFormatException ignored) {
                // Try the next evidence-backed field.
            }
        }
        return 0L;
    }

    private static int firstInt(JSONObject value, String... keys) {
        for (String key : keys) {
            long number = firstLong(value, key);
            if (number != 0L) {
                return number > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) number;
            }
        }
        return 0;
    }

    private static String canonicalQuery(Map<String, String> values) throws Exception {
        if (values == null || values.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() != null) {
                sorted.put(entry.getKey(), entry.getValue());
            }
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (result.length() > 0) {
                result.append('&');
            }
            result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return result.toString();
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static String md5(String value) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte valueByte : bytes) {
            result.append(String.format(java.util.Locale.US, "%02x", valueByte & 0xff));
        }
        return result.toString();
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, read);
            }
            return result.toString();
        }
    }

    private static String mimeType(String url) {
        String lower = safe(url).toLowerCase(java.util.Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        if (lower.endsWith(".m3u8")) {
            return "application/x-mpegURL";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".flv")) {
            return "video/x-flv";
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** API failure without response bodies or credentials. */
    public static final class FnApiException extends RepositoryFailure {
        public final int httpStatus;
        public final int apiCode;

        FnApiException(String message) {
            this(message, 0, 0, null);
        }

        FnApiException(String message, int httpStatus, int apiCode) {
            this(message, httpStatus, apiCode, null);
        }

        FnApiException(String message, int httpStatus, int apiCode, Throwable cause) {
            super(classify(httpStatus, apiCode), message, cause);
            this.httpStatus = httpStatus;
            this.apiCode = apiCode;
        }

        private static Kind classify(int httpStatus, int apiCode) {
            if (httpStatus == 401 || apiCode == -2) return Kind.AUTHENTICATION_REQUIRED;
            if (httpStatus == 403) return Kind.PERMISSION_DENIED;
            return Kind.OTHER;
        }
    }
}
