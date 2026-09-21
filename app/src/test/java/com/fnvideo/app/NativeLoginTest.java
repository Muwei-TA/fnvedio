package com.fnvideo.app;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** All passwords and tokens in these fixtures are synthetic. */
public class NativeLoginTest {
    private MockWebServer server;

    @Before public void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After public void stop() throws Exception { server.shutdown(); }

    private String origin() { return server.url("/").toString(); }

    private void assertCleared(char[] password) {
        assertArrayEquals(new char[password.length], password);
    }

    @Test public void loginUsesV2SignedPostAndHasNoExistingSessionHeader() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"token\":\"synthetic-session\"}}"));
        char[] password = "synthetic-密码".toCharArray();
        assertEquals("synthetic-session", FnApi.login(origin(), "  synthetic-user  ", password));
        assertCleared(password);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/v/api/v2/user/loginByPassword", request.getPath());
        assertNotNull(request.getHeader("authx"));
        assertFalse(request.getHeader("authx").isEmpty());
        assertNull(request.getHeader("Authorization"));
        String rawBody = request.getBody().readUtf8();
        JSONObject body = new JSONObject(rawBody);
        assertEquals("synthetic-user", body.getString("username"));
        assertEquals("trimemedia-web", body.getString("app_name"));
        StringBuilder expectedHash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
                .digest("synthetic-密码".getBytes(StandardCharsets.UTF_8))) {
            expectedHash.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        assertEquals(expectedHash.toString(), body.getString("password"));
        assertFalse(rawBody.contains("synthetic-密码"));
    }

    @Test public void redirectsNeverSendLoginToTheTargetServer() throws Exception {
        try (MockWebServer target = new MockWebServer()) {
            target.start();
            target.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"token\":\"wrong-origin\"}}"));
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", target.url("/login")));
            char[] password = "synthetic-password".toCharArray();
            try {
                FnApi.login(origin(), "synthetic-user", password);
                fail("Login redirect must be rejected");
            } catch (IOException expected) {
                assertEquals("服务器要求跳转，请直接填写最终影视服务地址", expected.getMessage());
                assertNull(expected.getCause());
            }
            assertCleared(password);
            assertNull(target.takeRequest(200, TimeUnit.MILLISECONDS));
            assertEquals(0, target.getRequestCount());
        }
    }

    @Test public void missingTokenAndServerFailuresExposeOnlySafeMessages() throws Exception {
        String echoed = "synthetic-sensitive-server-detail";
        String[] responses = {
                "{\"code\":0,\"data\":{}}",
                "{\"code\":5001,\"msg\":\"" + echoed + "\"}",
                "{\"errno\":-2,\"message\":\"" + echoed + "\"}",
                "not-json-" + echoed
        };
        for (String response : responses) {
            server.enqueue(new MockResponse().setBody(response));
            char[] password = "synthetic-password".toCharArray();
            try {
                FnApi.login(origin(), "synthetic-user", password);
                fail("Invalid login response must fail");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().contains(echoed));
                assertFalse(expected.getMessage().contains("synthetic-password"));
                assertTrue(expected.getMessage().startsWith("登录"));
                assertNull(expected.getCause());
            }
            assertCleared(password);
        }
    }

    @Test public void validationFailureAlsoClearsPasswordWithoutRequest() throws Exception {
        char[] password = "synthetic-password".toCharArray();
        try {
            FnApi.login(origin(), "  ", password);
            fail("Blank username must fail");
        } catch (IOException expected) {
            assertEquals("请输入用户名和密码", expected.getMessage());
        }
        assertCleared(password);
        assertEquals(0, server.getRequestCount());
    }

    @Test public void cancellingBeforeExecutionClearsPasswordAndSendsNothing() throws Exception {
        char[] password = "synthetic-password".toCharArray();
        FnApi.LoginCall call = new FnApi.LoginCall(origin(), "synthetic-user", password);
        call.cancel();
        assertCleared(password);
        try {
            call.execute();
            fail("Cancelled login must fail");
        } catch (IOException expected) {
            assertEquals("登录已取消", expected.getMessage());
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void cancellingAStalledResponseReleasesTheLoginWorker() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
        char[] password = "synthetic-password".toCharArray();
        FnApi.LoginCall call = new FnApi.LoginCall(origin(), "synthetic-user", password);
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> result = worker.submit(call::execute);
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            call.cancel();
            try {
                result.get(2, TimeUnit.SECONDS);
                fail("Cancelled login must fail");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertEquals("登录已取消", expected.getCause().getMessage());
            }
            assertCleared(password);
        } finally {
            call.cancel();
            worker.shutdownNow();
        }
    }
}
