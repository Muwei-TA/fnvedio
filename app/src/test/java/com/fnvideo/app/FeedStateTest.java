package com.fnvideo.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class FeedStateTest {
    private MediaRepository.Video video(String id) {
        MediaRepository.Video value = new MediaRepository.Video(); value.id = id; value.type = "Video"; return value;
    }

    @Test public void changingLibraryDiscardsInFlightOldPage() {
        FeedState state = new FeedState();
        long oldRequest = state.reset();
        long newRequest = state.reset();
        assertFalse(state.append(oldRequest, new MediaRepository.Page(Arrays.asList(video("old")), "2")));
        assertTrue(state.items().isEmpty());
        assertTrue(state.append(newRequest, new MediaRepository.Page(Arrays.asList(video("new")), "")));
        assertEquals("new", state.items().get(0).id);
    }

    @Test public void overlappingPagesPreserveOrderAndDeduplicate() {
        FeedState state = new FeedState(); long request = state.reset();
        state.append(request, new MediaRepository.Page(Arrays.asList(video("a"), video("b")), "2"));
        assertTrue(state.hasMore()); assertEquals("2", state.nextCursor());
        state.append(request, new MediaRepository.Page(Arrays.asList(video("b"), video("c")), ""));
        assertEquals(3, state.items().size()); assertEquals("c", state.items().get(2).id);
        assertFalse(state.hasMore());
    }

    @Test public void emptyLibraryTerminatesPaginationAndResetReopensIt() {
        FeedState state = new FeedState(); long request = state.reset();
        state.append(request, new MediaRepository.Page(Collections.emptyList(), ""));
        assertFalse(state.hasMore()); state.reset(); assertTrue(state.hasMore());
    }

    @Test public void directoryContainersNeverBecomePlayableFeedItems() {
        FeedState state = new FeedState(); long ticket = state.reset();
        MediaRepository.Video directory = video("folder"); directory.type = "Directory";
        MediaRepository.Video episode = video("episode"); episode.type = "Episode";
        state.append(ticket, new MediaRepository.Page(Arrays.asList(directory, episode), ""));
        assertEquals(1, state.items().size());
        assertEquals("episode", state.items().get(0).id);
    }
}
