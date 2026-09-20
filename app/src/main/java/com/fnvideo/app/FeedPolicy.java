package com.fnvideo.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Feed eligibility has one owner; catalog adapters only translate this query. */
public final class FeedPolicy {
    public static final List<String> PLAYABLE_TYPES = Collections.unmodifiableList(
            Arrays.asList("Movie", "Video", "Episode"));

    private FeedPolicy() { }

    public static boolean isPlayable(MediaRepository.Video video) {
        if (video == null || video.id == null || video.id.isEmpty()) return false;
        for (String type : PLAYABLE_TYPES) {
            if (type.equalsIgnoreCase(video.type)) return true;
        }
        return false;
    }
}
