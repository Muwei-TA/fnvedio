package com.fnvideo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Main-thread-owned feed state. Generation tickets discard late network responses. */
public final class FeedState {
    private final Map<String, MediaRepository.Video> items = new LinkedHashMap<>();
    private static final int MAX_EMPTY_PAGES = 5;
    private final Set<String> completedCursors = new HashSet<>();
    private int emptyPages;
    private long generation;
    private String nextCursor = "";
    private boolean firstPageLoaded;

    public long reset() {
        generation++;
        items.clear();
        completedCursors.clear();
        emptyPages = 0;
        nextCursor = "";
        firstPageLoaded = false;
        return generation;
    }

    public long generation() { return generation; }
    public String nextCursor() { return nextCursor; }
    public boolean hasMore() { return !firstPageLoaded || !nextCursor.isEmpty(); }

    public boolean append(long ticket, MediaRepository.Page page) {
        if (ticket != generation) return false;
        if (page == null) throw new IllegalArgumentException("Missing page");
        if (!page.nextCursor.isEmpty() && (page.nextCursor.equals(nextCursor)
                || completedCursors.contains(page.nextCursor))) {
            throw new IllegalStateException("服务端分页游标未推进，请重新加载");
        }
        int previousSize = items.size();
        completedCursors.add(nextCursor);
        for (MediaRepository.Video item : page.items) {
            if (FeedPolicy.isPlayable(item)) items.putIfAbsent(item.id, item);
        }
        emptyPages = items.size() == previousSize ? emptyPages + 1 : 0;
        nextCursor = page.nextCursor;
        firstPageLoaded = true;
        return true;
    }

    public boolean isNearEnd(int position) {
        return items.size() - Math.max(0, position) <= 3;
    }

    public boolean shouldAutoLoad(int position) {
        return hasMore() && isNearEnd(position) && emptyPages < MAX_EMPTY_PAGES;
    }

    /** A user gesture can start another bounded scan after empty/filtered pages. */
    public void restartEmptyPageScan() { emptyPages = 0; }

    public List<MediaRepository.Video> items() {
        return Collections.unmodifiableList(new ArrayList<>(items.values()));
    }
}
