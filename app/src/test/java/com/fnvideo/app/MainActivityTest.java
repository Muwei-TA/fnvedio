package com.fnvideo.app;

import android.content.Context;
import android.content.Intent;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Regression coverage for playback state transitions that must stay identity-safe. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class MainActivityTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        SessionStore.reset(context);
        context.getSharedPreferences(WatchStateStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(WatchStateStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void manuallySelectingAnotherQueueItemPreservesIncompleteState() throws Exception {
        assertManualQueueJumpPreservesCompletion(false);
    }

    @Test public void manuallySelectingAnotherQueueItemPreservesCompletedState() throws Exception {
        assertManualQueueJumpPreservesCompletion(true);
    }

    private void assertManualQueueJumpPreservesCompletion(boolean initialCompleted) throws Exception {
        MediaRepository.Video first = episode("episode-1", "第一集", 1);
        MediaRepository.Video second = episode("episode-2", "第二集", 2);
        Intent intent = new Intent(context, MainActivity.class);
        PlaybackRequest.put(intent, first, Arrays.asList(first, second), 0, true, "series");

        try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            MainActivity activity = controller.get();
            String origin = "http://nas.example.test:5666";
            WatchStateStore store = new WatchStateStore(activity, origin, "synthetic-user");
            setField(activity, "serverBase", origin);
            setField(activity, "sessionToken", "synthetic-session");
            setField(activity, "accountId", "synthetic-user");
            setField(activity, "watchStore", store);
            setField(activity, "foreground", true);
            setField(activity, "currentCompleted", initialCompleted);

            ExoPlayer player = getField(activity, "player");
            if (player == null) {
                player = new ExoPlayer.Builder(activity).build();
                setField(activity, "player", player);
            }
            player.setMediaItem(new MediaItem.Builder()
                    .setMediaId(first.id)
                    .setUri("http://nas.example.test/media/" + first.id)
                    .build());
            setField(activity, "player", player);
            PlaybackSession session = getField(activity, "playbackSession");
            PlaybackSession.Ticket ticket = session.select(origin, first.id, 1L);
            assertTrue(session.markLoaded(ticket));

            invoke(activity, "selectQueueIndex", 1);

            WatchStateStore.Entry previous = null;
            List<WatchStateStore.Entry> entries = store.recent();
            for (WatchStateStore.Entry entry : entries) {
                if (entry.video != null && first.id.equals(entry.video.id)) previous = entry;
            }
            assertNotNull("The previous item should retain a watch checkpoint", previous);
            assertEquals("Manual queue navigation must preserve the previous completion state",
                    initialCompleted, previous.completed);
        }
    }

    @Test public void pauseInvalidatesPendingResolveBeforeLateCallbackCanAttach() throws Exception {
        MediaRepository.Video video = episode("episode-1", "第一集", 1);
        Intent intent = new Intent(context, MainActivity.class);
        PlaybackRequest.put(intent, video, Arrays.asList(video), 0, false, "series");

        try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            MainActivity activity = controller.get();
            PlaybackSession session = getField(activity, "playbackSession");
            PlaybackSession.Ticket pending = session.select(
                    "http://nas.example.test:5666", video.id, 7L);
            activity.onPause();

            assertFalse("A source resolved after pause must not attach to the old request",
                    session.markLoaded(pending));
        }
    }

    private static MediaRepository.Video episode(String id, String title, int number) {
        MediaRepository.Video value = new MediaRepository.Video();
        value.id = id;
        value.title = title;
        value.type = "Episode";
        value.seriesId = "series-1";
        value.seasonId = "season-1";
        value.season = 1;
        value.episode = number;
        return value;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length) continue;
            method.setAccessible(true);
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw error;
            }
        }
        throw new NoSuchMethodException(name);
    }
}
