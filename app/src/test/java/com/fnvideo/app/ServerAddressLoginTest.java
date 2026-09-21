package com.fnvideo.app;

import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the origin policy used by LoginActivity; not a WebView integration test. */
public class ServerAddressLoginTest {
    @Test public void supportedLoginEntryPathsResolveToOneOrigin() {
        for (String path : new String[]{"", "/", "/v", "/v/", "/v/login", "/v/api/v1", "/v/api/v2/"}) {
            assertEquals("http://nas.example.test:5666",
                    ServerAddress.normalize("http://nas.example.test:5666" + path));
        }
    }

    @Test public void hostCaseAndDefaultPortDoNotForceAnotherLogin() {
        String previous = "HTTPS://NAS.EXAMPLE.TEST:443/v/login";
        assertTrue(ServerAddress.sameOrigin(previous, "https://nas.example.test/v"));
        assertEquals("synthetic-session", ServerAddress.retainedToken(
                previous, "https://nas.example.test", "synthetic-session"));
    }

    @Test public void differentPortRequiresIsolatedLoginDespiteSharedCookieHost() {
        String previous = "http://nas.example.test:5666/v/login";
        String next = "http://nas.example.test:8080/v/login";
        assertFalse(ServerAddress.sameOrigin(previous, next));
        assertEquals("", ServerAddress.retainedToken(previous, next, "synthetic-session"));
    }

    @Test public void httpsDowngradeDoesNotRetainCredentials() {
        String previous = "https://nas.example.test/v";
        assertFalse(ServerAddress.sameOrigin(previous, "http://nas.example.test/v"));
        assertEquals("", ServerAddress.retainedToken(previous,
                "http://nas.example.test", "synthetic-session"));
    }

    @Test public void hostLookalikesAndEmbeddedCredentialsAreNotTrusted() {
        String expected = "https://nas.example.test";
        assertFalse(ServerAddress.sameOrigin(expected, "https://nas.example.test.attacker.invalid/v"));
        assertFalse(ServerAddress.sameOrigin(expected, "https://nas.example.test@attacker.invalid/v"));
        assertFalse(ServerAddress.sameOrigin(expected, "https://user:password@nas.example.test/v"));
    }

    @Test public void canonicalAddressIsIdempotentIncludingIpv6() {
        for (String value : new String[]{"http://[::1]:5666/v/login", "HTTPS://NAS.EXAMPLE.TEST:443/v/",
                "http://nas.example.test:80/v/api/v1"}) {
            String normalized = ServerAddress.normalize(value);
            assertEquals(normalized, ServerAddress.normalize(normalized));
        }
    }

    @Test public void loginSettingsRejectQueriesFragmentsAndArbitraryPaths() {
        for (String suffix : new String[]{"/v/login?token=synthetic", "/v#login", "/other", "/v/../other"}) {
            try {
                ServerAddress.normalize("https://nas.example.test" + suffix);
                fail("Expected an invalid login address");
            } catch (IllegalArgumentException expected) {
                // Rejection happens before the login Activity changes its origin.
            }
        }
    }
    @Test public void nativeInputUsesDefaultNasPortOnlyForBareHosts() {
        assertEquals("http://192.0.2.10:5666", ServerAddress.fromUserInput(" 192.0.2.10 "));
        assertEquals("http://192.0.2.10:8080", ServerAddress.fromUserInput("192.0.2.10:8080"));
        assertEquals("http://nas.example.test:5666", ServerAddress.fromUserInput("NAS.EXAMPLE.TEST/v"));
        assertEquals("https://nas.example.test", ServerAddress.fromUserInput("HTTPS://NAS.EXAMPLE.TEST:443/v/login"));
        assertEquals("https://nas.example.test:8443", ServerAddress.fromUserInput("https://nas.example.test:8443"));
        assertEquals("http://192.0.2.10", ServerAddress.fromUserInput("http://192.0.2.10"));
        assertEquals("http://[::1]:5666", ServerAddress.fromUserInput("[::1]"));
    }

    @Test public void nativeInputRejectsCredentialsQueriesAndFragmentsWithoutEchoingThem() {
        for (String value : new String[]{"user:synthetic-password@nas.example.test",
                "https://user:synthetic-password@nas.example.test", "nas.example.test?token=synthetic-secret",
                "nas.example.test:5666/v/login?password=synthetic-secret", "nas.example.test#synthetic-secret",
                "ftp://nas.example.test", "nas.example.test:65536", ""}) {
            try {
                ServerAddress.fromUserInput(value);
                fail("Unsafe login address must be rejected");
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().contains("synthetic-"));
            }
        }
    }

}
