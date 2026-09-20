package com.fnvideo.app;

import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real redirect exchanges using the same scoped client that Media3 receives. */
public class PlaybackRedirectTest {
    @Test public void crossOriginRedirectStripsCredentialsButKeepsRangeAndUserAgent() throws Exception {
        try (MockWebServer nas = new MockWebServer(); MockWebServer storage = new MockWebServer()) {
            nas.start();
            storage.start();
            nas.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", storage.url("/video.mp4")));
            storage.enqueue(new MockResponse().setBody("synthetic-media"));

            execute(nas.url("/"), nas.url("/media/range/test"));
            RecordedRequest original = take(nas);
            assertEquals("synthetic-token", original.getHeader("Authorization"));
            assertEquals("synthetic-link", original.getHeader("Play-Link"));
            assertUntrustedRequest(take(storage));
        }
    }

    @Test public void sameOriginRedirectRetainsNasHeaders() throws Exception {
        try (MockWebServer nas = new MockWebServer()) {
            nas.start();
            nas.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", nas.url("/segment.ts")));
            nas.enqueue(new MockResponse().setBody("synthetic-segment"));

            execute(nas.url("/"), nas.url("/playlist"));
            take(nas);
            RecordedRequest redirected = take(nas);
            assertEquals("/segment.ts", redirected.getPath());
            assertEquals("synthetic-token", redirected.getHeader("Authorization"));
            assertEquals("synthetic-link", redirected.getHeader("Play-Link"));
            assertEquals("synthetic-cookie", redirected.getHeader("Cookie"));
            assertEquals("synthetic-signature", redirected.getHeader("authx"));
            assertEquals("bytes=100-", redirected.getHeader("Range"));
        }
    }

    @Test public void directCloudRequestIsScopedBeforeItsFirstNetworkHop() throws Exception {
        try (MockWebServer nas = new MockWebServer(); MockWebServer storage = new MockWebServer()) {
            nas.start();
            storage.start();
            storage.enqueue(new MockResponse().setBody("synthetic-media"));

            execute(nas.url("/"), storage.url("/video.mp4"));
            assertUntrustedRequest(take(storage));
            assertEquals(0, nas.getRequestCount());
        }
    }

    private static void execute(HttpUrl trusted, HttpUrl target) throws Exception {
        OkHttpClient client = PlaybackDataSource.scopedClient(trusted).newBuilder()
                .callTimeout(5, TimeUnit.SECONDS).build();
        Request request = new Request.Builder().url(target)
                .header("Authorization", "synthetic-token")
                .header("Play-Link", "synthetic-link")
                .header("Cookie", "synthetic-cookie")
                .header("authx", "synthetic-signature")
                .header("Range", "bytes=100-")
                .header("User-Agent", "FnVideo/1.0 (Android)").build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
    }

    private static RecordedRequest take(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull("Expected an actual HTTP exchange", request);
        return request;
    }

    private static void assertUntrustedRequest(RecordedRequest request) {
        assertNull(request.getHeader("Authorization"));
        assertNull(request.getHeader("Play-Link"));
        assertNull(request.getHeader("Cookie"));
        assertNull(request.getHeader("authx"));
        assertEquals("bytes=100-", request.getHeader("Range"));
        assertEquals("FnVideo/1.0 (Android)", request.getHeader("User-Agent"));
    }
}
