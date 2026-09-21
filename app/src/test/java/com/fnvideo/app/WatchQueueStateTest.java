package com.fnvideo.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

public class WatchQueueStateTest {
    @Test public void namespaceAndPageAppendKeepOnlyMovieVideoAndStableIds() {
        WatchQueueState state = state("library-a");
        state.acceptPage(page("2",
                video("movie-1", "Movie"),
                video("episode-1", "Episode"),
                video("video-1", "Video"),
                video("movie-1", "Movie")));

        assertEquals("http://nas.example:5666", state.serverOrigin());
        assertEquals("account-a", state.accountId());
        assertEquals("library-a", state.libraryId());
        assertEquals(2, state.items().size());
        assertEquals("2", state.nextCursor());
        assertFalse(state.isConfirmedEnd());
        assertEquals("movie-1", state.items().get(0).id);
        assertEquals("video-1", state.items().get(1).id);
    }

    @Test public void pendingCursorPreventsWrappingUntilAnEmptyCursorIsAccepted() {
        WatchQueueState state = state("library-a");
        state.acceptPage(page("2", video("movie-1", "Movie"), video("movie-2", "Movie")));
        WatchQueueState.NextResult first = state.beginSession();
        assertTrue(first.isReady());

        state.setCurrentId("movie-2");
        assertEquals("movie-2", state.next().item.id); // restore the saved current item
        WatchQueueState.NextResult pending = state.next();
        assertEquals(WatchQueueState.NextStatus.NEEDS_PAGE, pending.status);
        assertEquals("2", pending.nextCursor);
        assertFalse(pending.wrapped);

        state.acceptPage(page("", video("movie-3", "Video")));
        WatchQueueState.NextResult next = state.next();
        assertEquals(WatchQueueState.NextStatus.READY, next.status);
        assertEquals("movie-3", next.item.id);
        assertTrue(state.isConfirmedEnd());

        WatchQueueState.NextResult wrapped = state.next();
        assertEquals(WatchQueueState.NextStatus.READY, wrapped.status);
        assertTrue(wrapped.wrapped);
        assertEquals("movie-1", wrapped.item.id);
    }

    @Test public void aFullFortyEightItemWindowRemainsPending() {
        WatchQueueState state = state("library-a");
        MediaRepository.Video[] values = new MediaRepository.Video[48];
        for (int i = 0; i < values.length; i++) values[i] = video("movie-" + i, "Movie");
        state.acceptPage(page("next-page", values));
        state.beginSession();
        state.setCurrentId("movie-47");
        state.next(); // restore current
        WatchQueueState.NextResult result = state.next();
        assertEquals(WatchQueueState.NextStatus.NEEDS_PAGE, result.status);
        assertFalse(result.confirmedEnd);
        assertEquals("next-page", result.nextCursor);
    }

    @Test public void newSessionAvoidsPreviousAndResumableIdsWhenAlternativesExist() {
        WatchQueueState state = state("library-a");
        state.acceptPage(page("", video("movie-1", "Movie"), video("movie-2", "Movie"),
                video("movie-3", "Movie")));

        String first = state.beginSession().item.id;
        String second = state.beginSession().item.id;
        assertNotEquals(first, second);
        String target = "movie-1";
        if (target.equals(first) || target.equals(second)) target = "movie-2";
        if (target.equals(first) || target.equals(second)) target = "movie-3";
        HashSet<String> resumeIds = new HashSet<>(Arrays.asList(
                "movie-1", "movie-2", "movie-3"));
        resumeIds.remove(target);
        WatchQueueState.NextResult resumeAvoided = state.beginSession(
                resumeIds);
        assertEquals(WatchQueueState.NextStatus.READY, resumeAvoided.status);
        assertEquals(target, resumeAvoided.item.id);
        assertEquals(second, state.lastSessionId());
    }

    @Test public void savedCurrentIdIsRestoredWhenItsLaterPageArrives() {
        WatchQueueState state = state("library-a");
        state.acceptPage(page("2", video("movie-1", "Movie")));
        state.beginSession();
        state.setCurrentId("movie-2");
        assertNull(state.currentItem());
        assertEquals(WatchQueueState.NextStatus.NEEDS_PAGE, state.next().status);

        state.acceptPage(page("", video("movie-2", "Video")));
        WatchQueueState.NextResult restored = state.next();
        assertEquals(WatchQueueState.NextStatus.READY, restored.status);
        assertEquals("movie-2", restored.item.id);
        assertSame(restored.item, state.restoreCurrentItem());
    }

    @Test public void repeatedNonEmptyCursorFailsInsteadOfPretendingToReachTheEnd() {
        WatchQueueState state = state("library-a");
        state.acceptPage(page("2", video("movie-1", "Movie")));
        try {
            state.acceptPage(page("2", video("movie-2", "Movie")));
            fail("Expected a non-advancing cursor failure");
        } catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains("cursor"));
        }
    }

    private WatchQueueState state(String libraryId) {
        return new WatchQueueState("http://NAS.example:5666/v", "account-a", libraryId);
    }

    private MediaRepository.Page page(String cursor, MediaRepository.Video... values) {
        return new MediaRepository.Page(Arrays.asList(values), cursor);
    }

    private MediaRepository.Video video(String id, String type) {
        MediaRepository.Video value = new MediaRepository.Video();
        value.id = id;
        value.type = type;
        value.title = id;
        return value;
    }
}
