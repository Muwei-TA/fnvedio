package com.fnvideo.app;

import org.junit.Test;
import okhttp3.HttpUrl;
import okhttp3.Request;
import static org.junit.Assert.*;

public class PlaybackDataSourceTest {
    private final HttpUrl nas = HttpUrl.parse("http://192.0.2.1:5666");

    private Request request(String url) {
        return new Request.Builder().url(url)
                .header("Authorization", "test-session")
                .header("Play-Link", "test-play-link")
                .header("Cookie", "test-cookie")
                .header("Range", "bytes=100-").build();
    }

    @Test public void nasSegmentsKeepAuthentication() {
        Request result = PlaybackDataSource.scopedRequest(nas, request("http://192.0.2.1:5666/segment.ts"));
        assertEquals("test-session", result.header("Authorization"));
        assertEquals("test-play-link", result.header("Play-Link"));
    }

    @Test public void redirectedStorageNeverReceivesNasCredentials() {
        Request result = PlaybackDataSource.scopedRequest(nas, request("http://storage.example/video.mp4"));
        assertNull(result.header("Authorization"));
        assertNull(result.header("Play-Link"));
        assertNull(result.header("Cookie"));
        assertEquals("bytes=100-", result.header("Range"));
    }

    @Test public void differentPortIsNotTrustedOrigin() {
        Request result = PlaybackDataSource.scopedRequest(nas, request("http://192.0.2.1:8080/video"));
        assertNull(result.header("Authorization"));
    }
}
