package com.fnvideo.app;

import android.content.Context;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Device-only live catalog diagnostic. It intentionally reports aggregate
 * counters and response metadata, never media identities, URLs, or session
 * values. Run this against an already authenticated test device/container.
 */
public final class ProductDeviceTest {
    private static final String TAG = "FnVideoProductDevice";
    private static final int MAX_POSTER_PROBES = 1;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 12_000;

    @Test public void testLiveCatalogAndPosterResponses() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String base = SessionStore.base(context);
        String token = SessionStore.token(context);
        if (token == null || token.trim().isEmpty()) {
            fail("No stored live session");
        }

        FnApi api = new FnApi(base, token);
        MediaRepository.Page page;
        try {
            // This is deliberately the catalog API's all-kind query. The
            // test does not reuse search, a UI filter, or a cached list.
            page = api.catalogPage(new MediaRepository.Query("", "", ""), "");
        } catch (Exception error) {
            fail("Live catalog request failed");
            return;
        }
        assertNotNull("Live catalog page is null", page);
        assertTrue("Live catalog is empty", page.items != null && !page.items.isEmpty());

        int movieCount = 0;
        int videoCount = 0;
        int posterFields = 0;
        int imageResponses = 0;
        int imageMimeResponses = 0;
        int httpResponses = 0;
        int invalidPosterValues = 0;
        int probes = 0;
        Map<Integer, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> mimeCounts = new LinkedHashMap<>();
        for (MediaRepository.Video item : page.items) {
            if (item == null) continue;
            if ("Movie".equalsIgnoreCase(item.type)) movieCount++;
            if ("Video".equalsIgnoreCase(item.type)) videoCount++;
            String poster = item.poster == null ? "" : item.poster.trim();
            if (poster.isEmpty()) continue;
            posterFields++;
            if (probes >= MAX_POSTER_PROBES) continue;
            probes++;
            ProbePair probe = probePoster(base, token, poster);
            Log.i(TAG, "posterBefore=" + probe.before.summary()
                    + " posterAfter=" + probe.after.summary());
            ProbeResult result = probe.after;
            if (result.status > 0) {
                httpResponses++;
                statusCounts.put(result.status, statusCounts.getOrDefault(result.status, 0) + 1);
            }
            if (result.imageResponse) imageResponses++;
            if (result.imageMime) imageMimeResponses++;
            if (result.mimeType.isEmpty()) {
                if (result.invalidUrl) invalidPosterValues++;
            } else {
                mimeCounts.put(result.mimeType,
                        mimeCounts.getOrDefault(result.mimeType, 0) + 1);
            }
        }

        Log.i(TAG, "catalogRows=" + page.items.size()
                + " movieRows=" + movieCount
                + " videoRows=" + videoCount
                + " posterFields=" + posterFields
                + " posterProbes=" + probes
                + " httpResponses=" + httpResponses
                + " imageResponses=" + imageResponses
                + " imageMimeResponses=" + imageMimeResponses
                + " invalidPosterValues=" + invalidPosterValues
                + " statuses=" + statusCounts
                + " mimes=" + mimeCounts);

        if (posterFields == 0) {
            logCatalogFieldTypes(api);
        }
        assertTrue("Catalog returned no non-empty poster fields", posterFields > 0);
        assertTrue("No valid image poster response", imageResponses > 0);
    }

    private ProbePair probePoster(String base, String token, String poster) {
        ProbeResult before = openPoster(poster, "");
        ProbeResult after = before;
        // Retry any non-image same-origin response with the raw session header.
        // Redirects are disabled and the header is never sent cross-origin.
        if (!before.imageResponse
                && ServerAddress.sameOrigin(base, poster)
                && token != null && !token.isEmpty()) {
            after = openPoster(poster, token);
        }
        return new ProbePair(before, after);
    }

    private ProbeResult openPoster(String poster, String authorization) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(poster);
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return ProbeResult.invalidUrl();
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "image/*");
            if (!authorization.isEmpty()) {
                connection.setRequestProperty("Authorization", authorization);
            }
            int status = connection.getResponseCode();
            String mime = normalizeMime(connection.getContentType());
            boolean imageMime = mime.startsWith("image/");
            boolean imageResponse = status >= 200 && status < 300 && imageMime;
            Integer apiCode = null;
            if (status >= 200 && status < 300) {
                InputStream input = connection.getInputStream();
                try (InputStream body = input) {
                    apiCode = imageMime ? null : numericCode(readBounded(body));
                }
            } else if (status >= 400) {
                InputStream input = connection.getErrorStream();
                if (input != null) {
                    try (InputStream body = input) {
                        apiCode = numericCode(readBounded(body));
                    }
                }
            }
            return new ProbeResult(status, mime, apiCode, imageResponse, imageMime, false);
        } catch (Exception error) {
            return new ProbeResult(-1, "", null, false, false, false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int remaining = 64 * 1024;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Integer numericCode(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            Object raw = new JSONObject(body).opt("code");
            if (raw instanceof Number) return ((Number) raw).intValue();
            if (raw != null && raw != JSONObject.NULL) return Integer.valueOf(String.valueOf(raw));
        } catch (Exception ignored) {
            // Non-JSON bodies intentionally remain unreported.
        }
        return null;
    }

    /**
     * The normal catalog response is enough for a pass/fail test. If every
     * poster field is empty, inspect one response through the existing private
     * request path and log only JSON field names and coarse value types.
     */
    private void logCatalogFieldTypes(FnApi api) {
        try {
            Method method = FnApi.class.getDeclaredMethod("requestData", String.class,
                    String.class, Map.class, JSONObject.class);
            method.setAccessible(true);
            JSONObject body = new JSONObject();
            body.put("tags", new JSONObject().put("type",
                    new JSONArray(Arrays.asList("Movie", "TV", "Video"))));
            body.put("sort_type", "DESC");
            body.put("sort_column", "create_time");
            body.put("exclude_grouped_video", 1);
            body.put("page", 1);
            body.put("page_size", 50);
            Object raw = method.invoke(api, "POST", "/api/v1/item/list", null, body);
            JSONObject listing = raw instanceof JSONObject ? (JSONObject) raw : null;
            JSONArray values = listing == null ? null : listing.optJSONArray("list");
            JSONObject first = values == null || values.length() == 0
                    ? null : values.optJSONObject(0);
            if (first == null) {
                Log.w(TAG, "catalogFieldTypes=none");
                return;
            }
            StringBuilder fields = new StringBuilder();
            Iterator<String> keys = first.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (fields.length() > 0) fields.append(',');
                fields.append(key).append(':').append(jsonType(first.opt(key)));
            }
            Log.w(TAG, "catalogFieldTypes=" + fields);
        } catch (Exception error) {
            Log.w(TAG, "catalogFieldTypes=unavailable:" + error.getClass().getSimpleName());
        }
    }

    private static String jsonType(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return "string";
    }

    private static String normalizeMime(String contentType) {
        if (contentType == null) return "";
        int separator = contentType.indexOf(';');
        String mime = separator >= 0 ? contentType.substring(0, separator) : contentType;
        return mime.trim().toLowerCase(java.util.Locale.US);
    }

    private static final class ProbeResult {
        final int status;
        final String mimeType;
        final Integer apiCode;
        final boolean imageResponse;
        final boolean imageMime;
        final boolean invalidUrl;

        ProbeResult(int status, String mimeType, Integer apiCode,
                    boolean imageResponse, boolean imageMime, boolean invalidUrl) {
            this.status = status;
            this.mimeType = mimeType;
            this.apiCode = apiCode;
            this.imageResponse = imageResponse;
            this.imageMime = imageMime;
            this.invalidUrl = invalidUrl;
        }

        static ProbeResult invalidUrl() {
            return new ProbeResult(-1, "", null, false, false, true);
        }

        String summary() {
            return "status=" + status + " mime=" + mimeType
                    + " code=" + (apiCode == null ? "none" : apiCode);
        }
    }

    private static final class ProbePair {
        final ProbeResult before;
        final ProbeResult after;

        ProbePair(ProbeResult before, ProbeResult after) {
            this.before = before;
            this.after = after;
        }
    }
}
