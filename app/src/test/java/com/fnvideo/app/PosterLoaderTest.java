package com.fnvideo.app;

import android.graphics.Bitmap;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Poster credentials stay on the NAS origin across manual redirect hops. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PosterLoaderTest {
    private MockWebServer origin;
    private MockWebServer publicServer;

    @Before public void setUp() throws Exception {
        origin = new MockWebServer();
        publicServer = new MockWebServer();
        origin.start();
        publicServer.start();
    }

    @After public void tearDown() throws Exception {
        origin.shutdown();
        publicServer.shutdown();
    }

    @Test public void sameOriginImageReceivesAuthorization() throws Exception {
        origin.enqueue(imageResponse());
        PosterLoader loader = new PosterLoader(origin.url("/v").toString(), "poster-session");
        try {
            CountDownLatch loaded = new CountDownLatch(1);
            loader.load(origin.url("/v/api/v1/sys/img/poster.png").toString(),
                    200, 300, bitmap -> loaded.countDown());
            assertTrueLoaded(loaded);
            okhttp3.mockwebserver.RecordedRequest request = origin.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("poster-session", request.getHeader("Authorization"));
        } finally {
            loader.shutdown();
        }
    }

    @Test public void crossOriginRedirectDropsAuthorization() throws Exception {
        publicServer.enqueue(imageResponse());
        origin.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", publicServer.url("/public/poster.png").toString()));
        PosterLoader loader = new PosterLoader(origin.url("/v").toString(), "poster-session");
        try {
            CountDownLatch loaded = new CountDownLatch(1);
            loader.load(origin.url("/v/api/v1/sys/img/poster.png").toString(),
                    200, 300, bitmap -> loaded.countDown());
            assertTrueLoaded(loaded);
            okhttp3.mockwebserver.RecordedRequest first = origin.takeRequest(2, TimeUnit.SECONDS);
            okhttp3.mockwebserver.RecordedRequest redirected = publicServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(redirected);
            assertEquals("poster-session", first.getHeader("Authorization"));
            assertNull(redirected.getHeader("Authorization"));
        } finally {
            loader.shutdown();
        }
    }

    @Test public void sameOriginRedirectRetainsAuthorization() throws Exception {
        origin.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", origin.url("/v/api/v1/sys/img/final.png").toString()));
        origin.enqueue(imageResponse());
        PosterLoader loader = new PosterLoader(origin.url("/v").toString(), "poster-session");
        try {
            CountDownLatch loaded = new CountDownLatch(1);
            loader.load(origin.url("/v/api/v1/sys/img/poster.png").toString(),
                    200, 300, bitmap -> loaded.countDown());
            assertTrueLoaded(loaded);
            okhttp3.mockwebserver.RecordedRequest first = origin.takeRequest(2, TimeUnit.SECONDS);
            okhttp3.mockwebserver.RecordedRequest second = origin.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            assertEquals("poster-session", first.getHeader("Authorization"));
            assertEquals("poster-session", second.getHeader("Authorization"));
        } finally {
            loader.shutdown();
        }
    }

    private MockResponse imageResponse() {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(pngBytes()));
    }

    private static byte[] pngBytes() {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    private static void assertTrueLoaded(CountDownLatch loaded) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (loaded.getCount() > 0 && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            loaded.await(10, TimeUnit.MILLISECONDS);
        }
        assertEquals(0L, loaded.getCount());
    }
}
