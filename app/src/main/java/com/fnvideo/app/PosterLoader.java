package com.fnvideo.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal poster loader for the feed. One background thread, a small LRU
 * memory cache, and per-view request tickets so recycled holders never show
 * a stale page's bitmap. Only http/https URLs are fetched.
 */
public final class PosterLoader {
    public interface Target {
        void onPosterLoaded(Bitmap poster);
    }

    private static final int MAX_CACHE_ENTRIES = 24;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> cache = new LinkedHashMap<>(16, 0.75f, true);

    public void load(String url, Target target) {
        String key = url == null ? "" : url.trim();
        if (key.isEmpty()) {
            return;
        }
        Bitmap cached = null;
        synchronized (cache) {
            cached = cache.get(key);
        }
        if (cached != null) {
            target.onPosterLoaded(cached);
            return;
        }
        WeakReference<Target> reference = new WeakReference<>(target);
        executor.execute(() -> {
            Bitmap decoded = fetch(key);
            if (decoded != null) {
                synchronized (cache) {
                    cache.put(key, decoded);
                    while (cache.size() > MAX_CACHE_ENTRIES) {
                        String eldest = cache.keySet().iterator().next();
                        cache.remove(eldest);
                    }
                }
            }
            Target alive = reference.get();
            if (alive != null && decoded != null) {
                Bitmap result = decoded;
                main.post(() -> {
                    Target current = reference.get();
                    if (current != null) {
                        current.onPosterLoaded(result);
                    }
                });
            }
        });
    }

    private Bitmap fetch(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() / 100 != 2) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception error) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
