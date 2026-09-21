package com.fnvideo.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Device-local watch state. A store is scoped to one canonical server and one
 * stable account identity; authentication tokens are deliberately not part of
 * that scope.
 *
 * <p>Position records are kept separately from presentation snapshots. The
 * latter are bounded so a long viewing history cannot grow without bound,
 * while an evicted snapshot never removes the position needed for resuming.</p>
 */
public final class WatchStateStore {
    /** Package-visible for focused storage tests; callers should use the API. */
    static final String PREFS_NAME = "watch_state";
    static final String LEGACY_PREFS_NAME = "resume_positions";
    static final int MAX_RECENT_ENTRIES = 100;
    private static final String INDEX_VERSION = "watch-state-v2";
    private static final String INDEX_PREFIX = "index.";
    private static final String POSITION_PREFIX = "position.";
    private static final String SNAPSHOT_PREFIX = "snapshot.";
    private static final String LATER_PREFIX = "later.";
    private static final String LEGACY_CLAIM_PREFIX = "watch_state_claim_v2:";
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;
    private final SharedPreferences legacyPreferences;
    private final String serverOrigin;
    private final String accountId;
    private final String namespace;
    private final String namespaceHash;
    private final boolean canMigrateLegacy;

    /**
     * Creates a local state namespace. The server address is canonicalized so
     * paths, host case, and default ports cannot accidentally split a session.
     */
    public WatchStateStore(Context context, String serverOrigin, String accountId) {
        if (context == null) throw new IllegalArgumentException("Missing context");
        this.serverOrigin = ServerAddress.normalize(serverOrigin);
        this.accountId = cleanAccount(accountId);
        this.namespace = this.serverOrigin + "\u0000" + this.accountId;
        this.namespaceHash = digest(namespace);
        this.canMigrateLegacy = !this.accountId.isEmpty();
        Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        this.preferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.legacyPreferences = app.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Saves a playback checkpoint and a bounded display snapshot. */
    public void save(MediaRepository.Video video, long positionMs, long durationMs,
                     boolean completed) {
        String itemId = requireItemId(video);
        long safePosition = nonNegative(positionMs);
        long safeDuration = nonNegativeDuration(durationMs);
        synchronized (LOCK) {
            if (readPositionStateLocked(itemId) == null) migrateLegacyLocked(video, itemId);
            PositionState previous = readPositionStateLocked(itemId);
            long lastPlayedAt = nextPositionTimestampLocked();
            if (previous != null && previous.lastPlayedAt >= lastPlayedAt) {
                lastPlayedAt = previous.lastPlayedAt + 1L;
            }
            PositionState state = new PositionState(itemId, safePosition, safeDuration,
                    lastPlayedAt, completed);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(positionKey(itemId), state.toJson().toString());
            editor.putString(snapshotKey(itemId), snapshotJson(video, state).toString());
            Map<String, PositionState> pending = new HashMap<>();
            pending.put(itemId, state);
            writeIndexLocked(editor, pending, Collections.emptySet(), Collections.emptySet());
            editor.apply();
        }
    }

    /**
     * Returns the resumable position. Completed entries intentionally return
     * zero while retaining their completion and duration metadata for history.
     */
    public long position(MediaRepository.Video video) {
        String itemId = requireItemId(video);
        synchronized (LOCK) {
            PositionState state = readPositionStateLocked(itemId);
            if (state == null) {
                migrateLegacyLocked(video, itemId);
                state = readPositionStateLocked(itemId);
            }
            if (state == null || state.completed) return 0L;
            return state.positionMs;
        }
    }

    /** Returns the most recently touched local watch entries, newest first. */
    public List<Entry> recent() {
        synchronized (LOCK) {
            Map<String, PositionState> positions = readAllPositionStatesLocked();
            Map<String, VideoSnapshot> snapshots = readAllSnapshotsLocked();
            Index index = readIndexLocked();
            List<String> orderedIds = orderedRecentIds(index, positions, snapshots);
            List<Entry> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String itemId : orderedIds) {
                if (itemId == null || !seen.add(itemId)) continue;
                PositionState state = positions.get(itemId);
                VideoSnapshot snapshot = snapshots.get(itemId);
                if (state == null) continue;
                result.add(entry(itemId, state, snapshot));
                if (result.size() >= MAX_RECENT_ENTRIES) break;
            }
            return Collections.unmodifiableList(result);
        }
    }

    /** Returns the local later-list entries, newest added first. */
    public List<MediaRepository.Video> watchLater() {
        synchronized (LOCK) {
            List<LaterEntry> later = readAllLaterLocked();
            Map<String, VideoSnapshot> snapshots = readAllSnapshotsLocked();
            List<MediaRepository.Video> result = new ArrayList<>();
            for (LaterEntry value : later) {
                VideoSnapshot snapshot = snapshots.get(value.itemId);
                result.add(snapshot == null ? bareVideo(value.itemId) : copyVideo(snapshot.video));
            }
            return Collections.unmodifiableList(result);
        }
    }

    /** Tests whether an item is in the local later list. */
    public boolean isWatchLater(String itemId) {
        String safeId = cleanItemId(itemId);
        if (safeId.isEmpty()) return false;
        synchronized (LOCK) {
            return preferences.contains(laterKey(safeId));
        }
    }

    /** Adds or removes an item from the local later list. */
    public void toggleWatchLater(MediaRepository.Video video) {
        String itemId = requireItemId(video);
        synchronized (LOCK) {
            SharedPreferences.Editor editor = preferences.edit();
            String markerKey = laterKey(itemId);
            if (preferences.contains(markerKey)) {
                editor.remove(markerKey);
                PositionState state = readPositionStateLocked(itemId);
                if (state == null) {
                    editor.remove(snapshotKey(itemId));
                } else {
                    editor.putString(snapshotKey(itemId), snapshotJson(video, state).toString());
                }
                Map<String, PositionState> pending = new HashMap<>();
                if (state != null) pending.put(itemId, state);
                writeIndexLocked(editor, pending, Collections.emptySet(),
                        Collections.singleton(itemId));
            } else {
                long addedAt = nextLaterTimestampLocked();
                editor.putString(markerKey, laterJson(itemId, addedAt).toString());
                PositionState state = readPositionStateLocked(itemId);
                if (state == null) {
                    state = new PositionState(itemId, 0L, 0L, 0L, false);
                }
                editor.putString(snapshotKey(itemId), snapshotJson(video, state).toString());
                Map<String, PositionState> pending = new HashMap<>();
                pending.put(itemId, state);
                writeIndexLocked(editor, pending, Collections.singleton(itemId),
                        Collections.emptySet());
            }
            editor.apply();
        }
    }

    /** Public immutable row returned by {@link #recent()}. */
    public static final class Entry {
        public final MediaRepository.Video video;
        public final long positionMs;
        public final long durationMs;
        public final long lastPlayedAt;
        public final boolean completed;

        public Entry(MediaRepository.Video video, long positionMs, long durationMs,
                     long lastPlayedAt, boolean completed) {
            this.video = copyVideo(video);
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.lastPlayedAt = lastPlayedAt;
            this.completed = completed;
        }
    }

    private Entry entry(String itemId, PositionState state, VideoSnapshot snapshot) {
        MediaRepository.Video video = snapshot == null ? bareVideo(itemId) : snapshot.video;
        if (state == null) {
            state = new PositionState(itemId, 0L, 0L,
                    snapshot == null ? 0L : snapshot.lastPlayedAt, false);
        }
        long lastPlayedAt = Math.max(state.lastPlayedAt,
                snapshot == null ? 0L : snapshot.lastPlayedAt);
        return new Entry(video, state.positionMs, state.durationMs, lastPlayedAt, state.completed);
    }

    private List<String> orderedRecentIds(Index index, Map<String, PositionState> positions,
                                          Map<String, VideoSnapshot> snapshots) {
        List<String> result = new ArrayList<>();
        if (index != null) result.addAll(index.recentIds);
        List<String> remainder = new ArrayList<>();
        Set<String> included = new HashSet<>(result);
        Set<String> all = new HashSet<>();
        all.addAll(positions.keySet());
        for (String itemId : all) {
            if (!included.contains(itemId)) remainder.add(itemId);
        }
        Collections.sort(remainder, (left, right) -> Long.compare(lastPlayedAt(right, positions, snapshots),
                lastPlayedAt(left, positions, snapshots)));
        result.addAll(remainder);
        return result;
    }

    private long lastPlayedAt(String itemId, Map<String, PositionState> positions,
                              Map<String, VideoSnapshot> snapshots) {
        PositionState state = positions.get(itemId);
        VideoSnapshot snapshot = snapshots.get(itemId);
        return Math.max(state == null ? 0L : state.lastPlayedAt,
                snapshot == null ? 0L : snapshot.lastPlayedAt);
    }

    private long nextPositionTimestampLocked() {
        long timestamp = System.currentTimeMillis();
        for (PositionState state : readAllPositionStatesLocked().values()) {
            if (state.lastPlayedAt >= timestamp) timestamp = state.lastPlayedAt + 1L;
        }
        return timestamp;
    }

    private long nextLaterTimestampLocked() {
        long timestamp = System.currentTimeMillis();
        for (LaterEntry value : readAllLaterLocked()) {
            if (value.addedAt >= timestamp) timestamp = value.addedAt + 1L;
        }
        return timestamp;
    }

    private void migrateLegacyLocked(MediaRepository.Video video, String itemId) {
        if (!canMigrateLegacy) return;
        String oldKey = "position:" + serverOrigin + ":" + itemId;
        Long oldPosition = readLegacyLong(oldKey);
        if (oldPosition == null || oldPosition <= 0L) return;
        String claimKey = LEGACY_CLAIM_PREFIX + digest(oldKey);
        String owner = preferenceString(legacyPreferences, claimKey);
        if (owner != null && !owner.equals(namespaceHash)) return;

        // Claim first. If the process dies before copying, the same owner can
        // retry while another account can never acquire the old position.
        if (owner == null) {
            legacyPreferences.edit().putString(claimKey, namespaceHash).apply();
        }
        if (readPositionStateLocked(itemId) != null) return;
        PositionState state = new PositionState(itemId, oldPosition, 0L, 0L, false);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(positionKey(itemId), state.toJson().toString())
                .putString(snapshotKey(itemId), snapshotJson(video, state).toString());
        Map<String, PositionState> pending = new HashMap<>();
        pending.put(itemId, state);
        writeIndexLocked(editor, pending, Collections.emptySet(), Collections.emptySet());
        editor.apply();
    }

    private Long readLegacyLong(String key) {
        if (!legacyPreferences.contains(key)) return null;
        try {
            long value = legacyPreferences.getLong(key, 0L);
            return value > 0L ? value : null;
        } catch (ClassCastException invalidType) {
            return null;
        }
    }

    private PositionState readPositionStateLocked(String itemId) {
        String raw = preferenceString(preferences, positionKey(itemId));
        return raw == null ? null : PositionState.parse(raw, itemId);
    }

    private Map<String, PositionState> readAllPositionStatesLocked() {
        Map<String, PositionState> result = new HashMap<>();
        String prefix = POSITION_PREFIX + namespaceHash + ".";
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(prefix)) continue;
            String raw = preferenceString(preferences, key);
            PositionState value = raw == null ? null : PositionState.parse(raw, "");
            if (value != null && !value.itemId.isEmpty()) result.put(value.itemId, value);
        }
        return result;
    }

    private Map<String, VideoSnapshot> readAllSnapshotsLocked() {
        Map<String, VideoSnapshot> result = new HashMap<>();
        String prefix = SNAPSHOT_PREFIX + namespaceHash + ".";
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(prefix)) continue;
            String raw = preferenceString(preferences, key);
            VideoSnapshot value = raw == null ? null : VideoSnapshot.parse(raw);
            if (value != null && !value.itemId.isEmpty()) result.put(value.itemId, value);
        }
        return result;
    }

    private List<LaterEntry> readAllLaterLocked() {
        List<LaterEntry> result = new ArrayList<>();
        String prefix = LATER_PREFIX + namespaceHash + ".";
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(prefix)) continue;
            String raw = preferenceString(preferences, key);
            LaterEntry value = raw == null ? null : LaterEntry.parse(raw);
            if (value != null && !value.itemId.isEmpty()) result.add(value);
        }
        Collections.sort(result, (left, right) -> Long.compare(right.addedAt, left.addedAt));
        return result;
    }

    private Index readIndexLocked() {
        String raw = preferenceString(preferences, INDEX_PREFIX + namespaceHash);
        return raw == null ? null : Index.parse(raw);
    }

    /** Writes a recoverable advisory index and trims snapshots only. */
    private void writeIndexLocked(SharedPreferences.Editor editor,
                                  Map<String, PositionState> pendingPositions,
                                  Set<String> addedLaterIds,
                                  Set<String> removedLaterIds) {
        Map<String, PositionState> positions = readAllPositionStatesLocked();
        Map<String, VideoSnapshot> snapshots = readAllSnapshotsLocked();
        positions.putAll(pendingPositions);

        List<String> recentIds = new ArrayList<>();
        recentIds.addAll(positions.keySet());
        Collections.sort(recentIds, (left, right) -> Long.compare(lastPlayedAt(right, positions, snapshots),
                lastPlayedAt(left, positions, snapshots)));
        if (recentIds.size() > MAX_RECENT_ENTRIES) {
            recentIds = new ArrayList<>(recentIds.subList(0, MAX_RECENT_ENTRIES));
        }

        List<String> laterIds = new ArrayList<>();
        for (LaterEntry value : readAllLaterLocked()) laterIds.add(value.itemId);
        for (String itemId : addedLaterIds) {
            if (!laterIds.contains(itemId)) laterIds.add(itemId);
        }
        laterIds.removeAll(removedLaterIds);
        JSONObject index = new JSONObject();
        try {
            index.put("version", INDEX_VERSION);
            index.put("recent", new JSONArray(recentIds));
            index.put("later", new JSONArray(laterIds));
        } catch (JSONException impossible) {
            return;
        }
        editor.putString(INDEX_PREFIX + namespaceHash, index.toString());

        // Presentation snapshots are disposable. Position keys are never
        // removed here, even when their snapshot falls outside the index.
        Set<String> keepSnapshots = new HashSet<>(recentIds);
        keepSnapshots.addAll(laterIds);
        String snapshotPrefix = SNAPSHOT_PREFIX + namespaceHash + ".";
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(snapshotPrefix)) continue;
            String raw = preferenceString(preferences, key);
            VideoSnapshot snapshot = raw == null ? null : VideoSnapshot.parse(raw);
            if (snapshot != null && !keepSnapshots.contains(snapshot.itemId)) editor.remove(key);
        }
    }

    private String positionKey(String itemId) {
        return POSITION_PREFIX + namespaceHash + "." + digest(itemId);
    }

    private String snapshotKey(String itemId) {
        return SNAPSHOT_PREFIX + namespaceHash + "." + digest(itemId);
    }

    private String laterKey(String itemId) {
        return LATER_PREFIX + namespaceHash + "." + digest(itemId);
    }

    private static JSONObject snapshotJson(MediaRepository.Video video, PositionState state) {
        JSONObject value = new JSONObject();
        try {
            value.put("itemId", state.itemId);
            value.put("lastPlayedAt", state.lastPlayedAt);
            value.put("video", videoJson(video));
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode watch snapshot", impossible);
        }
        return value;
    }

    private static JSONObject laterJson(String itemId, long addedAt) {
        JSONObject value = new JSONObject();
        try {
            value.put("itemId", itemId);
            value.put("addedAt", addedAt);
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode later item", impossible);
        }
        return value;
    }

    private static JSONObject videoJson(MediaRepository.Video video) {
        JSONObject value = new JSONObject();
        try {
            value.put("id", cleanItemId(video.id));
            value.put("title", safe(video.title));
            value.put("subtitle", safe(video.subtitle));
            value.put("poster", safe(video.poster));
            value.put("type", safe(video.type));
            value.put("season", video.season);
            value.put("episode", video.episode);
            value.put("parentId", safe(video.parentId));
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode video snapshot", impossible);
        }
        return value;
    }

    private static MediaRepository.Video videoFromJson(JSONObject value, String fallbackId) {
        MediaRepository.Video video = new MediaRepository.Video();
        video.id = cleanItemId(value.optString("id", fallbackId));
        video.title = safe(value.optString("title", ""));
        video.subtitle = safe(value.optString("subtitle", ""));
        video.poster = safe(value.optString("poster", ""));
        video.type = safe(value.optString("type", ""));
        video.season = value.optInt("season", 0);
        video.episode = value.optInt("episode", 0);
        video.parentId = safe(value.optString("parentId", ""));
        return video;
    }

    private static MediaRepository.Video copyVideo(MediaRepository.Video source) {
        if (source == null) return bareVideo("");
        MediaRepository.Video copy = new MediaRepository.Video();
        copy.id = safe(source.id);
        copy.title = safe(source.title);
        copy.subtitle = safe(source.subtitle);
        copy.poster = safe(source.poster);
        copy.type = safe(source.type);
        copy.season = source.season;
        copy.episode = source.episode;
        copy.parentId = safe(source.parentId);
        return copy;
    }

    private static MediaRepository.Video bareVideo(String itemId) {
        MediaRepository.Video value = new MediaRepository.Video();
        value.id = safe(itemId);
        return value;
    }

    private static String requireItemId(MediaRepository.Video video) {
        if (video == null) throw new IllegalArgumentException("Missing media");
        String itemId = cleanItemId(video.id);
        if (itemId.isEmpty()) throw new IllegalArgumentException("Missing media id");
        return itemId;
    }

    private static String cleanItemId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanAccount(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static long nonNegative(long value) {
        return value < 0L ? 0L : value;
    }

    private static long nonNegativeDuration(long value) {
        return value <= 0L ? 0L : value;
    }

    private static String preferenceString(SharedPreferences source, String key) {
        try {
            return source.getString(key, null);
        } catch (ClassCastException invalidType) {
            return null;
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte byteValue : bytes) result.append(String.format(java.util.Locale.US,
                    "%02x", byteValue & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class PositionState {
        final String itemId;
        final long positionMs;
        final long durationMs;
        final long lastPlayedAt;
        final boolean completed;

        PositionState(String itemId, long positionMs, long durationMs,
                      long lastPlayedAt, boolean completed) {
            this.itemId = itemId;
            this.positionMs = nonNegative(positionMs);
            this.durationMs = nonNegativeDuration(durationMs);
            this.lastPlayedAt = nonNegative(lastPlayedAt);
            this.completed = completed;
        }

        JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("itemId", itemId);
                value.put("positionMs", positionMs);
                value.put("durationMs", durationMs);
                value.put("lastPlayedAt", lastPlayedAt);
                value.put("completed", completed);
            } catch (JSONException impossible) {
                throw new IllegalStateException("Unable to encode watch position", impossible);
            }
            return value;
        }

        static PositionState parse(String raw, String expectedItemId) {
            try {
                JSONObject value = new JSONObject(raw);
                String itemId = cleanItemId(value.optString("itemId", expectedItemId));
                if (itemId.isEmpty() || (!expectedItemId.isEmpty() && !expectedItemId.equals(itemId))) {
                    return null;
                }
                long position = value.optLong("positionMs", -1L);
                long duration = value.optLong("durationMs", -1L);
                long last = value.optLong("lastPlayedAt", -1L);
                if (position < 0L || duration < 0L || last < 0L) return null;
                return new PositionState(itemId, position, duration, last,
                        value.optBoolean("completed", false));
            } catch (JSONException | RuntimeException invalid) {
                return null;
            }
        }
    }

    private static final class VideoSnapshot {
        final String itemId;
        final MediaRepository.Video video;
        final long lastPlayedAt;

        VideoSnapshot(String itemId, MediaRepository.Video video, long lastPlayedAt) {
            this.itemId = itemId;
            this.video = copyVideo(video);
            this.lastPlayedAt = nonNegative(lastPlayedAt);
        }

        static VideoSnapshot parse(String raw) {
            try {
                JSONObject value = new JSONObject(raw);
                String itemId = cleanItemId(value.optString("itemId", ""));
                JSONObject video = value.optJSONObject("video");
                if (itemId.isEmpty() || video == null) return null;
                MediaRepository.Video result = videoFromJson(video, itemId);
                if (!itemId.equals(result.id)) return null;
                long last = value.optLong("lastPlayedAt", -1L);
                if (last < 0L) return null;
                return new VideoSnapshot(itemId, result, last);
            } catch (JSONException | RuntimeException invalid) {
                return null;
            }
        }
    }

    private static final class LaterEntry {
        final String itemId;
        final long addedAt;

        LaterEntry(String itemId, long addedAt) {
            this.itemId = itemId;
            this.addedAt = nonNegative(addedAt);
        }

        static LaterEntry parse(String raw) {
            try {
                JSONObject value = new JSONObject(raw);
                String itemId = cleanItemId(value.optString("itemId", ""));
                long addedAt = value.optLong("addedAt", -1L);
                return itemId.isEmpty() || addedAt < 0L ? null : new LaterEntry(itemId, addedAt);
            } catch (JSONException | RuntimeException invalid) {
                return null;
            }
        }
    }

    private static final class Index {
        final List<String> recentIds;

        Index(List<String> recentIds) {
            this.recentIds = recentIds;
        }

        static Index parse(String raw) {
            try {
                JSONObject value = new JSONObject(raw);
                if (!INDEX_VERSION.equals(value.optString("version", ""))) return null;
                JSONArray recent = value.optJSONArray("recent");
                if (recent == null) return null;
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < recent.length(); i++) {
                    String itemId = cleanItemId(recent.optString(i, ""));
                    if (!itemId.isEmpty()) ids.add(itemId);
                }
                return new Index(ids);
            } catch (JSONException | RuntimeException invalid) {
                return null;
            }
        }
    }
}
