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
 * Read-only client for the Trim/FnOS media API.
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
        // The web season screen sends parent_guid for the container and sorts
        // by episode index; the same /item/list route serves both screens.
        JSONObject body = new JSONObject();
        body.put("parent_guid", series.id);
        body.put("sort_type", "ASC");
        body.put("sort_column", "episode");
        body.put("exclude_grouped_video", 1);
        body.put("page", 1);
        body.put("page_size", PAGE_SIZE);

        Object data = requestData("POST", API_V1 + "/item/list", null, body);
        JSONObject listing = asObject(data, "episode list");
        JSONArray values = listing.optJSONArray("list");
        List<Video> episodes = parseVideos(values == null ? new JSONArray() : values);
        episodes.sort(SERIES_ORDER);
        return episodes;
    }

    private static final java.util.Comparator<MediaRepository.Video> SERIES_ORDER =
            java.util.Comparator.comparingInt((MediaRepository.Video video) -> video.season)
                    .thenComparingInt(video -> video.episode);

    private Source resolve(Video video, boolean forceCompatible) throws Exception {
        if (video == null || safe(video.id).isEmpty()) {
            throw new FnApiException("Cannot resolve playback: item id is empty");
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
            video.subtitle = firstString(value, "subtitle", "sub_title", "overview");
            video.poster = posterUrl(firstPoster(value));
            video.season = firstInt(value, "season", "season_number");
            video.episode = firstInt(value, "episode", "episode_number");
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
