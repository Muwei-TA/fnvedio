package com.fnvideo.app;

import org.junit.Test;
import java.util.List;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import static org.junit.Assert.*;

public class LanMediaDiscoveryTest {
    @Test public void candidatesStayInsideCurrentSubnetAndExcludeLocalAddress() {
        List<String> hosts = LanMediaDiscovery.candidateHosts(new byte[]{10, 8, 2, 5}, 16);
        assertEquals(253, hosts.size());
        assertTrue(hosts.stream().allMatch(host -> host.startsWith("10.8.2.")));
        assertFalse(hosts.contains("10.8.2.5"));
        assertFalse(hosts.contains("10.8.2.0"));
        assertFalse(hosts.contains("10.8.2.255"));
        assertEquals(java.util.Collections.singletonList("10.8.2.6"),
                LanMediaDiscovery.candidateHosts(new byte[]{10, 8, 2, 5}, 30));
        assertTrue(LanMediaDiscovery.candidateHosts(new byte[]{8, 8, 8, 8}, 24).isEmpty());
        assertTrue(LanMediaDiscovery.candidateHosts(new byte[]{127, 0, 0, 1}, 8).isEmpty());
    }

    @Test public void ordinaryPagesAndMentionsAreNotAServiceIdentity() {
        assertFalse(LanMediaDiscovery.hasProductMarker("<title>Router</title><p>飞牛影视</p>"));
        assertFalse(LanMediaDiscovery.hasProductMarker("<title>Welcome</title>"));
        assertTrue(LanMediaDiscovery.hasProductMarker("<title>飞牛影视</title>"));
        assertTrue(LanMediaDiscovery.hasProductMarker("<title>Trimemedia</title>"));
    }

    @Test public void redirectIsNotFollowedAndNoCredentialsAreSent() throws Exception {
        try (MockWebServer source = new MockWebServer(); MockWebServer target = new MockWebServer()) {
            target.enqueue(new MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<title>飞牛影视</title>"));
            source.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target.url("/v/")));
            assertFalse(LanMediaDiscovery.isMediaService(LanMediaDiscovery.discoveryClient()
                    .newCall(new Request.Builder().url(source.url("/v/")).build())));
            assertEquals(0, target.getRequestCount());
            okhttp3.mockwebserver.RecordedRequest request = source.takeRequest();
            assertNull(request.getHeader("Cookie"));
            assertNull(request.getHeader("Authorization"));
        }
    }

    @Test public void cancelledProbeCannotReportAService() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<title>飞牛影视</title>"));
            Call call = LanMediaDiscovery.discoveryClient()
                    .newCall(new Request.Builder().url(server.url("/v/")).build());
            call.cancel();
            assertFalse(LanMediaDiscovery.isMediaService(call));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void successfulHtmlProbeIsRecognized() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<title>飞牛影视</title>"));
            assertTrue(LanMediaDiscovery.isMediaService(LanMediaDiscovery.discoveryClient()
                    .newCall(new Request.Builder().url(server.url("/v/")).build())));
        }
    }
}
