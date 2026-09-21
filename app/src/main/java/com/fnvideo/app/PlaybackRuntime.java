package com.fnvideo.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local handoff for the explicit 随看 cursor. It is keyed by the
 * server/account/library tuple and never contains credentials or media URLs.
 * WatchQueueState remains the durable queue authority when present; this
 * small bridge lets LibraryActivity learn which item was selected after the
 * single player Activity returns in the same process.
 */
public final class PlaybackRuntime {
    private static final Map<String, String> WATCH_CURRENT = new ConcurrentHashMap<>();

    private PlaybackRuntime() { }

    public static void setWatchCurrent(String serverOrigin, String accountId,
                                       String libraryId, String videoId) {
        String key = key(serverOrigin, accountId, libraryId);
        if (safe(videoId).isEmpty()) WATCH_CURRENT.remove(key);
        else WATCH_CURRENT.put(key, videoId);
    }

    public static String watchCurrent(String serverOrigin, String accountId, String libraryId) {
        return WATCH_CURRENT.getOrDefault(key(serverOrigin, accountId, libraryId), "");
    }

    private static String key(String serverOrigin, String accountId, String libraryId) {
        return safe(serverOrigin) + "\u0000" + safe(accountId) + "\u0000" + safe(libraryId);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
