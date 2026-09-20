package com.fnvideo.app;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
