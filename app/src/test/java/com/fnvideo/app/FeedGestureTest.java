package com.fnvideo.app;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.Shadows;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class FeedGestureTest {
    private final List<String> calls = new ArrayList<>();
    private FeedAdapter.VideoViewHolder holder;
    private long downTime;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        FeedAdapter.Listener listener = (FeedAdapter.Listener) Proxy.newProxyInstance(
                FeedAdapter.Listener.class.getClassLoader(),
                new Class<?>[]{FeedAdapter.Listener.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return null;
                });
        holder = new FeedAdapter(context, listener).onCreateViewHolder(new FrameLayout(context), 0);
        holder.itemView.layout(0, 0, 1080, 1920);
        downTime = SystemClock.uptimeMillis();
    }

    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        assertTrue(holder.playerView.dispatchTouchEvent(event));
        event.recycle();
    }

    @Test public void downAndSmallJitterDoNotPauseOrSeek() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));
        touch(MotionEvent.ACTION_MOVE, 130, 100);
        assertTrue(calls.isEmpty());
        touch(MotionEvent.ACTION_CANCEL, 130, 100);
        assertTrue(calls.isEmpty());
    }

    @Test public void tapOnlyFiresOnceAfterRelease() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        assertTrue(calls.isEmpty());
        touch(MotionEvent.ACTION_UP, 100, 100);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(Arrays.asList("onPageTapped"), calls);
    }

    @Test public void horizontalDragStartsOnceAndNeverClicks() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        touch(MotionEvent.ACTION_MOVE, 200, 100);
        touch(MotionEvent.ACTION_MOVE, 240, 100);
        touch(MotionEvent.ACTION_UP, 240, 100);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(Arrays.asList("onHorizontalSeekStart", "onHorizontalSeek",
                "onHorizontalSeek", "onHorizontalSeekEnd"), calls);
    }

    @Test public void verticalDragCannotTurnIntoSeek() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        touch(MotionEvent.ACTION_MOVE, 100, 200);
        touch(MotionEvent.ACTION_MOVE, 400, 200);
        touch(MotionEvent.ACTION_UP, 400, 200);
        assertTrue(calls.isEmpty());
    }

    @Test public void cancelEndsSeekWithoutClick() {
        touch(MotionEvent.ACTION_DOWN, 100, 100);
        touch(MotionEvent.ACTION_MOVE, 200, 100);
        touch(MotionEvent.ACTION_CANCEL, 200, 100);
        assertEquals(Arrays.asList("onHorizontalSeekStart", "onHorizontalSeek",
                "onHorizontalSeekEnd"), calls);
    }
}
