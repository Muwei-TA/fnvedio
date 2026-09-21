package com.fnvideo.app;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explicit, serializable handoff from the catalogue to the one player
 * Activity. The playback URL is deliberately absent; MainActivity resolves
 * it only after the user has requested playback.
 */
public final class PlaybackRequest {
    /** Keep the Binder handoff bounded for long seasons and large 随看 queues. */
    private static final int MAX_QUEUE_ITEMS = 48;
    private static final int QUEUE_ITEMS_BEFORE = 12;
    public static final String EXTRA_VIDEO = "fnvideo.playback.video";
    public static final String EXTRA_QUEUE = "fnvideo.playback.queue";
    public static final String EXTRA_QUEUE_INDEX = "fnvideo.playback.queue_index";
    public static final String EXTRA_AUTO_ADVANCE = "fnvideo.playback.auto_advance";
    public static final String EXTRA_MODE = "fnvideo.playback.mode";
    public static final String EXTRA_LIBRARY_ID = "fnvideo.playback.library_id";
    public static final String EXTRA_WATCH_CURSOR = "fnvideo.playback.watch_cursor";

    private PlaybackRequest() { }

    public static void put(Intent intent, MediaRepository.Video video,
                           List<MediaRepository.Video> queue, int selectedIndex,
                           boolean autoAdvance, String mode) {
        put(intent, video, queue, selectedIndex, autoAdvance, mode, "");
    }

    public static void put(Intent intent, MediaRepository.Video video,
                           List<MediaRepository.Video> queue, int selectedIndex,
                           boolean autoAdvance, String mode, String libraryId) {
        put(intent, video, queue, selectedIndex, autoAdvance, mode, libraryId, "");
    }

    public static void put(Intent intent, MediaRepository.Video video,
                           List<MediaRepository.Video> queue, int selectedIndex,
                           boolean autoAdvance, String mode, String libraryId,
                           String watchCursor) {
        intent.putExtra(EXTRA_VIDEO, encode(video));
        List<MediaRepository.Video> safeQueue = queue == null
                ? Collections.emptyList() : queue;
        int safeIndex = Math.max(0, Math.min(selectedIndex, Math.max(0, safeQueue.size() - 1)));
        int start = Math.max(0, safeIndex - QUEUE_ITEMS_BEFORE);
        int end = Math.min(safeQueue.size(), start + MAX_QUEUE_ITEMS);
        if (end - start < MAX_QUEUE_ITEMS) start = Math.max(0, end - MAX_QUEUE_ITEMS);
        List<MediaRepository.Video> window = safeQueue.subList(start, end);
        intent.putExtra(EXTRA_QUEUE, encodeQueue(window));
        intent.putExtra(EXTRA_QUEUE_INDEX, Math.max(0, safeIndex - start));
        intent.putExtra(EXTRA_AUTO_ADVANCE, autoAdvance);
        intent.putExtra(EXTRA_MODE, mode == null ? "movie" : mode);
        intent.putExtra(EXTRA_LIBRARY_ID, libraryId == null ? "" : libraryId);
        intent.putExtra(EXTRA_WATCH_CURSOR, watchCursor == null ? "" : watchCursor);
    }

    public static MediaRepository.Video video(Intent intent) {
        return intent == null ? null : decode(intent.getStringExtra(EXTRA_VIDEO));
    }

    public static List<MediaRepository.Video> queue(Intent intent) {
        return intent == null ? Collections.emptyList()
                : decodeQueue(intent.getStringExtra(EXTRA_QUEUE));
    }

    public static int queueIndex(Intent intent) {
        return intent == null ? 0 : intent.getIntExtra(EXTRA_QUEUE_INDEX, 0);
    }

    public static boolean autoAdvance(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_AUTO_ADVANCE, false);
    }

    public static String mode(Intent intent) {
        return intent == null ? "movie" : safe(intent.getStringExtra(EXTRA_MODE));
    }

    public static String libraryId(Intent intent) {
        return intent == null ? "" : safe(intent.getStringExtra(EXTRA_LIBRARY_ID));
    }

    public static String watchCursor(Intent intent) {
        return intent == null ? "" : safe(intent.getStringExtra(EXTRA_WATCH_CURSOR));
    }

    public static String encode(MediaRepository.Video video) {
        if (video == null) return "";
        try {
            return videoJson(video).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String encodeQueue(List<MediaRepository.Video> values) {
        JSONArray result = new JSONArray();
        if (values != null) {
            for (MediaRepository.Video value : values) {
                if (value == null || safe(value.id).isEmpty()) continue;
                result.put(videoJson(value));
            }
        }
        return result.toString();
    }

    public static MediaRepository.Video decode(String value) {
        if (safe(value).isEmpty()) return null;
        try {
            return fromJson(new JSONObject(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static List<MediaRepository.Video> decodeQueue(String value) {
        if (safe(value).isEmpty()) return Collections.emptyList();
        try {
            JSONArray values = new JSONArray(value);
            ArrayList<MediaRepository.Video> result = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject object = values.optJSONObject(i);
                MediaRepository.Video item = object == null ? null : fromJson(object);
                if (item != null && !safe(item.id).isEmpty()) result.add(item);
            }
            return Collections.unmodifiableList(result);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static JSONObject videoJson(MediaRepository.Video video) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", safe(video.id));
            object.put("title", safe(video.title));
            object.put("poster", safe(video.poster));
            object.put("type", safe(video.type));
            object.put("season", video.season);
            object.put("episode", video.episode);
            object.put("parentId", safe(video.parentId));
            object.put("seriesId", safe(video.seriesId));
            object.put("seasonId", safe(video.seasonId));
        } catch (Exception ignored) {
            // JSONObject.put only fails for an invalid NaN/Infinity value;
            // all fields above are bounded strings or ints.
        }
        return object;
    }

    private static MediaRepository.Video fromJson(JSONObject object) {
        MediaRepository.Video video = new MediaRepository.Video();
        video.id = object.optString("id", "");
        video.title = object.optString("title", "");
        video.subtitle = object.optString("subtitle", "");
        video.poster = object.optString("poster", "");
        video.type = object.optString("type", "");
        video.season = object.optInt("season", 0);
        video.episode = object.optInt("episode", 0);
        video.parentId = object.optString("parentId", "");
        video.overview = object.optString("overview", "");
        video.year = object.optString("year", "");
        video.seriesId = object.optString("seriesId", "");
        video.seasonId = object.optString("seasonId", "");
        return video;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
