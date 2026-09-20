package com.fnvideo.app;

import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

/** Authentication classification must survive localization and HTTP-200 API failures. */
public class RepositoryFailureTest {
    @Test public void nasAuthenticationCodeMapsToAppOwnedFailure() throws Exception {
        assertFailure(new MockResponse().setBody("{\"code\":-2,\"msg\":\"任意本地化消息\"}"),
                RepositoryFailure.Kind.AUTHENTICATION_REQUIRED);
    }

    @Test public void http401RequiresLogin() throws Exception {
        assertFailure(new MockResponse().setResponseCode(401).setBody("{}"),
                RepositoryFailure.Kind.AUTHENTICATION_REQUIRED);
    }

    @Test public void http403KeepsSessionAndReportsPermissionDenial() throws Exception {
        assertFailure(new MockResponse().setResponseCode(403).setBody("{}"),
                RepositoryFailure.Kind.PERMISSION_DENIED);
    }

    @Test public void serverFailureDoesNotBecomeAuthenticationBasedOnText() throws Exception {
        assertFailure(new MockResponse().setBody("{\"code\":5001,\"msg\":\"401 unauthorized\"}"),
                RepositoryFailure.Kind.OTHER);
    }

    @Test public void invalidAddressIsRejectedBeforeCreatingRequests() {
        try {
            new FnApi("nas", "synthetic-token");
            fail("Expected invalid address");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("synthetic-token"));
        }
    }

    @Test public void equivalentServerEntriesProduceExactlyOneVideoApiPrefix() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String[] entries = {"/", "/v", "/v/", "/v/login", "/v/api/v1/", "/v/api/v2/"};
            for (String entry : entries) {
                server.enqueue(emptyLibrary());
                FnApi api = new FnApi(server.url(entry).toString(), "synthetic-token");
                assertTrue(api.libraries().isEmpty());
                RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull("No request for " + entry, request);
                assertEquals("/v/api/v1/mdb/list", request.getPath());
                assertEquals("synthetic-token", request.getHeader("Authorization"));
            }
        }
    }

    @Test public void changingServerPortRemovesTokenBeforeTheNextApiRequest() throws Exception {
        try (MockWebServer original = new MockWebServer(); MockWebServer replacement = new MockWebServer()) {
            original.start();
            replacement.start();
            original.enqueue(emptyLibrary());
            new FnApi(original.url("/").toString(), "synthetic-token").libraries();
            RecordedRequest authenticated = original.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(authenticated);
            assertEquals("synthetic-token", authenticated.getHeader("Authorization"));

            String retained = ServerAddress.retainedToken(original.url("/").toString(),
                    replacement.url("/").toString(), "synthetic-token");
            assertEquals("", retained);
            replacement.enqueue(emptyLibrary());
            new FnApi(replacement.url("/").toString(), retained).libraries();
            RecordedRequest unauthenticated = replacement.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(unauthenticated);
            assertNull(unauthenticated.getHeader("Authorization"));
        }
    }

    @Test public void equivalentEntryPathRetainsTheSameOriginToken() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String retained = ServerAddress.retainedToken(server.url("/v").toString(),
                    server.url("/").toString(), "synthetic-token");
            server.enqueue(emptyLibrary());
            new FnApi(server.url("/").toString(), retained).libraries();
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("synthetic-token", request.getHeader("Authorization"));
        }
    }

    private static MockResponse emptyLibrary() {
        return new MockResponse().setBody("{\"code\":0,\"data\":[]}");
    }

    private static void assertFailure(MockResponse response, RepositoryFailure.Kind expected) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(response);
            server.start();
            try {
                new FnApi(server.url("/").toString(), "synthetic-token").libraries();
                fail("Expected repository failure");
            } catch (RepositoryFailure failure) {
                assertEquals(expected, failure.kind);
                assertEquals(expected == RepositoryFailure.Kind.AUTHENTICATION_REQUIRED,
                        RepositoryFailure.requiresLogin(failure));
            }
        }
    }
}
