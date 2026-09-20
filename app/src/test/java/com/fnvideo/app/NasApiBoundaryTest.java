package com.fnvideo.app;

import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

/** The NAS API must not follow redirects with authentication or expose raw error bodies. */
public class NasApiBoundaryTest {
    @Test public void apiRedirectsNeverReachADifferentOrigin() throws Exception {
        try (MockWebServer nas = new MockWebServer(); MockWebServer other = new MockWebServer()) {
            nas.start();
            other.start();
            for (int status : new int[]{301, 302, 307, 308}) {
                nas.enqueue(new MockResponse().setResponseCode(status)
                        .setHeader("Location", other.url("/v/api/v1/mdb/list")));
                // A queued response prevents a broken redirect policy from hanging the test.
                other.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":[]}"));
                RepositoryFailure failure = callExpectingFailure(nas);
                assertEquals(RepositoryFailure.Kind.OTHER, failure.kind);
                assertTrue(failure.getMessage().contains("HTTP " + status));
                RecordedRequest request = nas.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull("Expected the original NAS request", request);
                assertEquals("synthetic-token", request.getHeader("Authorization"));
                assertNotNull(request.getHeader("authx"));
                assertEquals("Redirect destination must receive no request", 0, other.getRequestCount());
            }
        }
    }

    @Test public void invalidJsonHasASanitizedTopLevelMessage() throws Exception {
        try (MockWebServer nas = new MockWebServer()) {
            nas.start();
            nas.enqueue(new MockResponse().setBody("not-json synthetic-response-secret"));
            RepositoryFailure failure = callExpectingFailure(nas);
            assertEquals(RepositoryFailure.Kind.OTHER, failure.kind);
            assertTrue(failure.getMessage().contains("invalid JSON"));
            assertFalse(failure.getMessage().contains("synthetic-response-secret"));
            assertFalse(failure.getMessage().contains("synthetic-token"));
        }
    }

    @Test public void httpErrorBodyIsNotUsedAsTheUserFacingFailure() throws Exception {
        try (MockWebServer nas = new MockWebServer()) {
            nas.start();
            nas.enqueue(new MockResponse().setResponseCode(503)
                    .setBody("synthetic-response-secret: 401 unauthorized"));
            RepositoryFailure failure = callExpectingFailure(nas);
            assertEquals(RepositoryFailure.Kind.OTHER, failure.kind);
            assertFalse(RepositoryFailure.requiresLogin(failure));
            assertTrue(failure.getMessage().contains("HTTP 503"));
            assertFalse(failure.getMessage().contains("synthetic-response-secret"));
        }
    }

    private static RepositoryFailure callExpectingFailure(MockWebServer nas) throws Exception {
        try {
            new FnApi(nas.url("/").toString(), "synthetic-token").libraries();
        } catch (RepositoryFailure failure) {
            return failure;
        }
        throw new AssertionError("Expected the production API adapter to reject this response");
    }
}
