package com.fnvideo.app;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WatchStateStoreTest {
    private Context context;
    private MediaRepository.Video film;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(WatchStateStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(WatchStateStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        SessionStore.reset(context);
        film = video("movie-1", "演示影片");
    }

    @Test public void stateIsolatedByCanonicalServerAndStableAccount() {
        WatchStateStore alice = new WatchStateStore(context,
                "HTTPS://NAS.EXAMPLE.TEST:443/v/", " alice ");
        WatchStateStore bob = new WatchStateStore(context,
                "https://nas.example.test", "bob");
        WatchStateStore sameAlice = new WatchStateStore(context,
                "https://nas.example.test:443/v", "alice");
        WatchStateStore otherServer = new WatchStateStore(context,
                "https://other.example.test", "alice");

        alice.save(film, 12_345L, 90_000L, false);
        alice.toggleWatchLater(film);

        assertEquals(12_345L, sameAlice.position(film));
        assertEquals(0L, bob.position(film));
        assertEquals(0L, otherServer.position(film));
        assertTrue(sameAlice.isWatchLater(film.id));
        assertFalse(bob.isWatchLater(film.id));
        assertFalse(otherServer.isWatchLater(film.id));
        assertEquals("演示影片", sameAlice.watchLater().get(0).title);
    }

    @Test public void legacyPositionIsCopiedOnceWithoutDeletingOldKey() {
        String origin = "http://nas.example.test:5666";
        String legacyKey = "position:" + origin + ":" + film.id;
        context.getSharedPreferences(WatchStateStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(legacyKey, 7_654L).commit();

        WatchStateStore first = new WatchStateStore(context, origin, "first-user");
        WatchStateStore second = new WatchStateStore(context, origin, "second-user");
        assertEquals(7_654L, first.position(film));
        assertEquals(0L, second.position(film));
        assertTrue(context.getSharedPreferences(WatchStateStore.LEGACY_PREFS_NAME,
                Context.MODE_PRIVATE).contains(legacyKey));

        // A fresh store for the owner can recover from a process interruption
        // because the claim is reusable by the same namespace.
        assertEquals(7_654L, new WatchStateStore(context, origin, "first-user").position(film));
    }

    @Test public void corruptIndexFallsBackToPerItemRecords() {
        WatchStateStore store = new WatchStateStore(context,
                "http://nas.example.test:5666", "alice");
        store.save(film, 4_321L, 80_000L, false);
        Context prefsContext = context;
        String indexKey = null;
        for (String key : prefsContext.getSharedPreferences(WatchStateStore.PREFS_NAME,
                Context.MODE_PRIVATE).getAll().keySet()) {
            if (key.startsWith("index.")) {
                indexKey = key;
                break;
            }
        }
        assertNotNull(indexKey);
        prefsContext.getSharedPreferences(WatchStateStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(indexKey, "{not-json").commit();

        List<WatchStateStore.Entry> recent = store.recent();
        assertEquals(1, recent.size());
        assertEquals(film.id, recent.get(0).video.id);
        assertEquals(4_321L, recent.get(0).positionMs);
        assertEquals(4_321L, store.position(film));
    }

    @Test public void snapshotLimitDoesNotDeleteOlderPosition() {
        WatchStateStore store = new WatchStateStore(context,
                "http://nas.example.test:5666", "alice");
        store.save(film, 9_999L, 100_000L, false);
        for (int i = 0; i < WatchStateStore.MAX_RECENT_ENTRIES + 25; i++) {
            MediaRepository.Video next = video("movie-" + (i + 2), "影片 " + i);
            store.save(next, i + 1L, 100_000L, false);
        }

        assertTrue(store.recent().size() <= WatchStateStore.MAX_RECENT_ENTRIES);
        assertEquals(9_999L, store.position(film));
    }

    @Test public void completedStateIsRetainedButNotResumable() {
        WatchStateStore store = new WatchStateStore(context,
                "http://nas.example.test:5666", "alice");
        store.save(film, 90_000L, 90_000L, true);

        assertEquals(0L, store.position(film));
        assertEquals(1, store.recent().size());
        assertTrue(store.recent().get(0).completed);
        assertEquals(90_000L, store.recent().get(0).positionMs);
    }

    @Test public void toggleLaterUsesSnapshotAndCanBeRemoved() {
        WatchStateStore store = new WatchStateStore(context,
                "http://nas.example.test:5666", "alice");
        store.toggleWatchLater(film);
        assertTrue(store.isWatchLater(film.id));
        assertEquals(film.id, store.watchLater().get(0).id);
        assertEquals(film.title, store.watchLater().get(0).title);

        store.toggleWatchLater(film);
        assertFalse(store.isWatchLater(film.id));
        assertTrue(store.watchLater().isEmpty());
    }

    @Test public void accountIdentityIsUsernameAndNotPasswordOrToken() {
        SessionStore.rememberAccountId(context, "  synthetic-user  ");
        assertEquals("synthetic-user", SessionStore.accountId(context));
        assertFalse(SessionStore.accountId(context).contains("synthetic-password"));
        assertFalse(SessionStore.accountId(context).contains("synthetic-token"));
        SessionStore.clear(context);
        assertEquals("", SessionStore.accountId(context));
    }

    private static MediaRepository.Video video(String id, String title) {
        MediaRepository.Video value = new MediaRepository.Video();
        value.id = id;
        value.title = title;
        value.subtitle = "副标题";
        value.poster = "poster://" + id;
        value.type = "Movie";
        value.season = 0;
        value.episode = 0;
        value.parentId = "";
        return value;
    }
}
