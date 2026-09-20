package com.fnvideo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Main-thread-owned feed state. Generation tickets discard late network responses. */
public final class FeedState {
    private final Map<String, MediaRepository.Video> items = new LinkedHashMap<>();
    private long generation;
    private String nextCursor = "";
    private boolean firstPageLoaded;

    public long reset() {
        generation++;
        items.clear();
        nextCursor = "";
        firstPageLoaded = false;
        return generation;
    }

    public long generation() { return generation; }
    public String nextCursor() { return nextCursor; }
    public boolean hasMore() { return !firstPageLoaded || !nextCursor.isEmpty(); }

    public boolean append(long ticket, MediaRepository.Page page) {
        if (ticket != generation) return false;
        for (MediaRepository.Video item : page.items) {
            if (FeedPolicy.isPlayable(item)) items.putIfAbsent(item.id, item);
        }
        nextCursor = page.nextCursor;
        firstPageLoaded = true;
        return true;
    }

    public List<MediaRepository.Video> items() {
        return Collections.unmodifiableList(new ArrayList<>(items.values()));
    }
}
