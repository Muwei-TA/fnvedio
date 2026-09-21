package com.fnvideo.app;

import android.content.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Process-local handoff for the explicit 随看 cursor. It is keyed by the
 * server/account/library tuple and never contains credentials or media URLs.
 * WatchQueueState remains the durable queue authority when present; this
 * small bridge lets LibraryActivity learn which item was selected after the
 * single player Activity returns in the same process.
 */
public final class PlaybackRuntime {
    private static final Map<String, String> WATCH_CURRENT = new ConcurrentHashMap<>();
    private static final String PREFS = "watch_runtime";

    private PlaybackRuntime() { }

    public static void setWatchCurrent(String serverOrigin, String accountId,
                                       String libraryId, String videoId) {
        String key = key(serverOrigin, accountId, libraryId);
        if (safe(videoId).isEmpty()) WATCH_CURRENT.remove(key);
        else WATCH_CURRENT.put(key, videoId);
    }

    public static void setWatchCurrent(Context context, String serverOrigin, String accountId,
                                       String libraryId, String videoId) {
        setWatchCurrent(serverOrigin, accountId, libraryId, videoId);
        if (context == null) return;
        String key = persistedKey(serverOrigin, accountId, libraryId);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, safe(videoId)).apply();
    }

    public static String watchCurrent(String serverOrigin, String accountId, String libraryId) {
        return WATCH_CURRENT.getOrDefault(key(serverOrigin, accountId, libraryId), "");
    }

    public static String watchCurrent(Context context, String serverOrigin, String accountId,
                                      String libraryId) {
        String value = watchCurrent(serverOrigin, accountId, libraryId);
        if (!value.isEmpty() || context == null) return value;
        value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(persistedKey(serverOrigin, accountId, libraryId), "");
        if (!value.isEmpty()) WATCH_CURRENT.put(key(serverOrigin, accountId, libraryId), value);
        return value;
    }

    private static String key(String serverOrigin, String accountId, String libraryId) {
        return safe(serverOrigin) + "\u0000" + safe(accountId) + "\u0000" + safe(libraryId);
    }

    private static String persistedKey(String serverOrigin, String accountId, String libraryId) {
        String raw = key(serverOrigin, accountId, libraryId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("watch-");
            for (byte item : digest) value.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
            return value.toString();
        } catch (Exception error) {
            // SHA-256 is required by every Android runtime; keep a printable
            // fallback so SharedPreferences never receives control characters.
            return "watch-" + raw.replaceAll("[^A-Za-z0-9._-]", "_");
        }
    }

    static void clearForTests() { WATCH_CURRENT.clear(); }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
