package com.fnvideo.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-Java state for the explicit 随看 queue.
 *
 * <p>This class owns no network client and makes no recommendation or catalog
 * query. The UI asks {@link MediaRepository} for ordinary Feed pages with a
 * library-bound {@link MediaRepository.Query}, then appends those pages here.
 * A non-empty page cursor is treated as proof that more work is possible;
 * only an accepted page with an empty cursor confirms the end and permits a
 * later call to {@link #next()} to wrap around.</p>
 */
public final class WatchQueueState {
    /** The caller must load the returned cursor before advancing. */
    public enum NextStatus {
        NEEDS_PAGE,
        READY,
        EXHAUSTED
    }

    /** Result of starting or advancing a queue session. */
    public static final class NextResult {
        public final NextStatus status;
        public final MediaRepository.Video item;
        /** Cursor the UI should request when {@link #status} is NEEDS_PAGE. */
        public final String nextCursor;
        /** True when advancing crossed the confirmed end and wrapped. */
        public final boolean wrapped;
        /** True when every loaded item was in the avoidance set. */
        public final boolean avoidedItemFallback;
        /** Whether an accepted page has confirmed the end of the source. */
        public final boolean confirmedEnd;

        private NextResult(NextStatus status, MediaRepository.Video item,
                           String nextCursor, boolean wrapped,
                           boolean avoidedItemFallback, boolean confirmedEnd) {
            this.status = status;
            this.item = item;
            this.nextCursor = nextCursor;
            this.wrapped = wrapped;
            this.avoidedItemFallback = avoidedItemFallback;
            this.confirmedEnd = confirmedEnd;
        }

        public boolean isReady() { return status == NextStatus.READY && item != null; }
        public boolean needsPage() { return status == NextStatus.NEEDS_PAGE; }
        public boolean isExhausted() { return status == NextStatus.EXHAUSTED; }
    }

    private final String serverOrigin;
    private final String accountId;
    private final String libraryId;
    private final String namespaceKey;
    private final LinkedHashMap<String, MediaRepository.Video> items = new LinkedHashMap<>();
    private final Set<String> seenCursors = new HashSet<>();
    private final LinkedHashSet<String> sessionAvoidIds = new LinkedHashSet<>();

    private String nextCursor = "";
    private boolean firstPageAccepted;
    private boolean confirmedEnd;
    private boolean sessionActive;
    private boolean currentNeedsResume;
    private String currentId = "";
    private String lastSessionId = "";
    private long sessionNumber;

    /**
     * Creates a queue namespace. Account identity is mandatory so two users
     * on one NAS cannot accidentally share the current queue item.
     */
    public WatchQueueState(String serverOrigin, String accountId, String libraryId) {
        String rawServer = clean(serverOrigin);
        if (rawServer.isEmpty()) throw new IllegalArgumentException("Missing server origin");
        this.serverOrigin = ServerAddress.normalize(rawServer);
        this.accountId = require(accountId, "account identity");
        this.libraryId = clean(libraryId);
        this.namespaceKey = this.serverOrigin + "\u0000" + this.accountId
                + "\u0000" + this.libraryId;
        seenCursors.add("");
    }

    public String serverOrigin() { return serverOrigin; }
    public String accountId() { return accountId; }
    public String libraryId() { return libraryId; }
    public String namespaceKey() { return namespaceKey; }

    /** The cursor to pass to the next ordinary Feed page request. */
    public String nextCursor() { return nextCursor; }

    /** True only after an accepted page explicitly returned an empty cursor. */
    public boolean isConfirmedEnd() { return confirmedEnd; }

    public boolean hasMore() { return !confirmedEnd; }
    public boolean hasAcceptedPage() { return firstPageAccepted; }

    /** The current playback identity, suitable for saving in session state. */
    public String currentId() { return currentId; }

    /** The loaded object for {@link #currentId()}, or null until its page arrives. */
    public MediaRepository.Video currentItem() {
        return currentId.isEmpty() ? null : items.get(currentId);
    }

    /** Alias for callers restoring a saved current item after page loading. */
    public MediaRepository.Video restoreCurrentItem() { return currentItem(); }

    /** The item that the previous session was showing when it was replaced. */
    public String lastSessionId() { return lastSessionId; }

    /** Returns a stable snapshot in first-seen order. */
    public List<MediaRepository.Video> items() {
        return Collections.unmodifiableList(new ArrayList<>(items.values()));
    }

    /** Alias that makes the UI call site read naturally. */
    public List<MediaRepository.Video> queue() { return items(); }

    /**
     * Adds one Feed page. Rows are restricted to Movie/Video and deduplicated
     * by their stable id. A non-empty next cursor keeps the queue pending;
     * an empty cursor confirms the source end.
     */
    public boolean acceptPage(MediaRepository.Page page) {
        if (page == null) throw new IllegalArgumentException("Missing watch queue page");
        if (confirmedEnd) throw new IllegalStateException("Watch queue already reached its confirmed end");

        String outgoing = clean(page.nextCursor);
        if (!outgoing.isEmpty()) {
            if (!seenCursors.add(outgoing)) {
                throw new IllegalStateException("Watch queue cursor did not advance");
            }
        }
        for (MediaRepository.Video item : page.items) {
            if (isCandidate(item)) items.putIfAbsent(item.id, item);
        }
        firstPageAccepted = true;
        nextCursor = outgoing;
        confirmedEnd = outgoing.isEmpty();
        return true;
    }

    /**
     * Saves the current stable identity. The object can arrive on a later
     * page; {@link #currentItem()} then becomes non-null as soon as it does.
     */
    public boolean setCurrentId(String itemId) {
        String value = clean(itemId);
        if (value.isEmpty()) {
            currentId = "";
            currentNeedsResume = false;
            return false;
        }
        currentId = value;
        currentNeedsResume = true;
        return true;
    }

    /** Alias used by callers that persist a session checkpoint. */
    public boolean saveCurrentId(String itemId) { return setCurrentId(itemId); }

    /**
     * Starts a new session and avoids the previous current item plus supplied
     * resumable IDs whenever another loaded candidate exists.
     */
    public NextResult beginSession(Set<String> resumeIds) {
        return beginSession((Collection<String>) resumeIds);
    }

    public NextResult beginSession() { return beginSession(Collections.emptySet()); }

    public NextResult beginSession(Collection<String> resumeIds) {
        lastSessionId = currentId;
        sessionAvoidIds.clear();
        addIds(sessionAvoidIds, resumeIds);
        if (!lastSessionId.isEmpty()) sessionAvoidIds.add(lastSessionId);
        sessionNumber++;
        sessionActive = true;
        currentId = "";
        currentNeedsResume = false;
        return chooseInitial();
    }

    /**
     * Advances one item. It never wraps while the current page cursor is
     * non-empty, so a 48-item window cannot masquerade as a complete queue.
     */
    public NextResult next() {
        if (!sessionActive) {
            return beginSession(Collections.emptySet());
        }
        MediaRepository.Video restored = currentItem();
        if (currentNeedsResume) {
            if (restored != null) {
                currentNeedsResume = false;
                return ready(restored, false, false);
            }
            if (!confirmedEnd) return needsPage();
            currentId = "";
            currentNeedsResume = false;
        }
        if (currentId.isEmpty()) return chooseInitial();

        int currentIndex = indexOf(currentId);
        if (currentIndex < 0) {
            if (!confirmedEnd) return needsPage();
            currentId = "";
            return chooseInitial();
        }
        List<MediaRepository.Video> values = new ArrayList<>(items.values());
        for (int index = currentIndex + 1; index < values.size(); index++) {
            return select(values.get(index), false, false);
        }
        if (!confirmedEnd) return needsPage();
        if (values.isEmpty()) return exhausted();
        for (int index = 0; index <= currentIndex && index < values.size(); index++) {
            return select(values.get(index), true, false);
        }
        return exhausted();
    }

    /** Returns the state without changing the current selection. */
    public NextStatus status() {
        if (!currentId.isEmpty() && currentItem() != null) return NextStatus.READY;
        if (!confirmedEnd) return NextStatus.NEEDS_PAGE;
        return items.isEmpty() ? NextStatus.EXHAUSTED : NextStatus.READY;
    }

    /** Starts a clean library/session scan while retaining the namespace. */
    public void reset() {
        items.clear();
        seenCursors.clear();
        seenCursors.add("");
        sessionAvoidIds.clear();
        nextCursor = "";
        firstPageAccepted = false;
        confirmedEnd = false;
        sessionActive = false;
        currentNeedsResume = false;
        currentId = "";
        lastSessionId = "";
        sessionNumber = 0L;
    }

    private NextResult chooseInitial() {
        if (items.isEmpty()) return confirmedEnd ? exhausted() : needsPage();
        List<MediaRepository.Video> values = new ArrayList<>(items.values());
        int start = Math.floorMod(namespaceKey.hashCode() + (int) sessionNumber, values.size());
        MediaRepository.Video fallback = values.get(start);
        for (int offset = 0; offset < values.size(); offset++) {
            MediaRepository.Video candidate = values.get((start + offset) % values.size());
            if (!sessionAvoidIds.contains(candidate.id)) {
                return select(candidate, false, false);
            }
        }
        return select(fallback, false, true);
    }

    private NextResult select(MediaRepository.Video item, boolean wrapped, boolean fallback) {
        currentId = item.id;
        currentNeedsResume = false;
        return ready(item, wrapped, fallback);
    }

    private NextResult ready(MediaRepository.Video item, boolean wrapped, boolean fallback) {
        return new NextResult(NextStatus.READY, item, nextCursor, wrapped, fallback, confirmedEnd);
    }

    private NextResult needsPage() {
        return new NextResult(NextStatus.NEEDS_PAGE, null, nextCursor,
                false, false, confirmedEnd);
    }

    private NextResult exhausted() {
        return new NextResult(NextStatus.EXHAUSTED, null, nextCursor,
                false, false, confirmedEnd);
    }

    private int indexOf(String itemId) {
        int index = 0;
        for (String id : items.keySet()) {
            if (id.equals(itemId)) return index;
            index++;
        }
        return -1;
    }

    private static boolean isCandidate(MediaRepository.Video item) {
        if (item == null || clean(item.id).isEmpty()) return false;
        return "Movie".equalsIgnoreCase(item.type) || "Video".equalsIgnoreCase(item.type);
    }

    private static void addIds(Set<String> target, Collection<String> values) {
        if (values == null) return;
        for (String value : values) {
            String id = clean(value);
            if (!id.isEmpty()) target.add(id);
        }
    }

    private static String require(String value, String label) {
        String clean = clean(value);
        if (clean.isEmpty()) throw new IllegalArgumentException("Missing " + label);
        return clean;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
