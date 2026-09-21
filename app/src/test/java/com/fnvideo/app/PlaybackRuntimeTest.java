package com.fnvideo.app;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/** The watch cursor survives a process-local map reset without persisting a NUL key. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class PlaybackRuntimeTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("watch_runtime", Context.MODE_PRIVATE)
                .edit().clear().commit();
        PlaybackRuntime.clearForTests();
    }

    @Test public void currentIdRestoresFromPrintableNamespacedPreference() {
        PlaybackRuntime.setWatchCurrent(context, "http://nas.example.test:5666",
                "user-a", "库\u00001", "movie-8");
        PlaybackRuntime.clearForTests();
        assertEquals("movie-8", PlaybackRuntime.watchCurrent(context,
                "http://nas.example.test:5666", "user-a", "库\u00001"));
    }
}
