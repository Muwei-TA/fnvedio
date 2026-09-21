package com.fnvideo.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded poster loader shared by the catalogue and player. Decode work stays
 * off the main thread, dimensions are bounded before allocation, and callers
 * use their own bind generation to reject a recycled view's result.
 */
public final class PosterLoader {
    public interface Target {
        void onPosterLoaded(Bitmap poster);
    }

    private static final int MAX_CACHE_ENTRIES = 24;
    private static final int DEFAULT_MAX_WIDTH = 800;
    private static final int DEFAULT_MAX_HEIGHT = 1_200;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_REDIRECTS = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final String serverOrigin;
    private final String sessionToken;

    /** Public compatibility constructor for callers that only load public URLs. */
    public PosterLoader() {
        this("", "");
    }

    /**
     * Binds this loader to one session. Credentials are never accepted per
     * request, so a recycled target cannot accidentally change their scope.
     */
    public PosterLoader(String serverOrigin, String sessionToken) {
        this.serverOrigin = normalizeOrigin(serverOrigin);
        this.sessionToken = sessionToken == null ? "" : sessionToken.trim();
    }

    public void load(String url, Target target) {
        load(url, DEFAULT_MAX_WIDTH, DEFAULT_MAX_HEIGHT, target);
    }

    /** Loads a poster with an explicit allocation bound suitable for its view. */
    public void load(String url, int maxWidth, int maxHeight, Target target) {
        String key = url == null ? "" : url.trim();
        if (key.isEmpty() || target == null) {
            return;
        }
        int boundedWidth = Math.max(1, Math.min(maxWidth, DEFAULT_MAX_WIDTH));
        int boundedHeight = Math.max(1, Math.min(maxHeight, DEFAULT_MAX_HEIGHT));
        String cacheKey = key + "#" + boundedWidth + "x" + boundedHeight;
        Bitmap cached = null;
        synchronized (cache) {
            cached = cache.get(cacheKey);
        }
        if (cached != null) {
            target.onPosterLoaded(cached);
            return;
        }
        // Keep the target alive until this queued task completes. Recycled
        // holders still guard the callback with a bind generation, while a
        // weak reference could silently drop visible grid images under load.
        executor.execute(() -> {
            Bitmap decoded = fetch(key, boundedWidth, boundedHeight);
            if (decoded != null) {
                synchronized (cache) {
                    cache.put(cacheKey, decoded);
                    while (cache.size() > MAX_CACHE_ENTRIES) {
                        String eldest = cache.keySet().iterator().next();
                        cache.remove(eldest);
                    }
                }
            }
            if (decoded != null) {
                main.post(() -> {
                    target.onPosterLoaded(decoded);
                });
            }
        });
    }

    private Bitmap fetch(String url, int maxWidth, int maxHeight) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = openFinal(url);
            if (connection == null) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(input, null, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return null;
                }
                int sample = 1;
                while (bounds.outWidth / sample > maxWidth
                        || bounds.outHeight / sample > maxHeight) {
                    sample <<= 1;
                }
                // Reopen because BitmapFactory consumed the first stream.
                connection.disconnect();
                connection = openFinal(url);
                if (connection == null) {
                    return null;
                }
                try (InputStream second = connection.getInputStream()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = Math.max(1, sample);
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeStream(second, null, options);
                }
            }
        } catch (Exception error) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Opens one hop with auth only when this exact URL is the trusted origin. */
    private HttpURLConnection open(String url) throws Exception {
        URL target = new URL(url);
        String protocol = target.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("Unsupported poster URL scheme");
        }
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        if (!sessionToken.isEmpty() && !serverOrigin.isEmpty()
                && ServerAddress.sameOrigin(serverOrigin, url)) {
            connection.setRequestProperty("Authorization", sessionToken);
        }
        return connection;
    }

    /** Follows a small public redirect chain, re-evaluating auth on each hop. */
    private HttpURLConnection openFinal(String initialUrl) throws Exception {
        String current = initialUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = open(current);
            int status = connection.getResponseCode();
            if (status / 100 == 2) return connection;
            if (!isRedirect(status)) {
                connection.disconnect();
                return null;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.trim().isEmpty()) return null;
            URI base = new URI(current);
            URI resolved = base.resolve(location.trim());
            String next = resolved.toString();
            if (!next.startsWith("http://") && !next.startsWith("https://")) return null;
            current = next;
        }
        return null;
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307 || status == 308;
    }

    private static String normalizeOrigin(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            return ServerAddress.normalize(value);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
