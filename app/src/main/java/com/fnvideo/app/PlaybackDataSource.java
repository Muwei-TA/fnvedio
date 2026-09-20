package com.fnvideo.app;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Scopes NAS credentials to its origin, including every redirect and HLS segment. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackDataSource {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build();
    private PlaybackDataSource() { }

    public static DataSource.Factory forSource(MediaRepository.Source source, String serverOrigin) {
        HttpUrl trusted = HttpUrl.parse(serverOrigin);
        if (trusted == null) throw new IllegalArgumentException("无效的服务器地址");
        OkHttpClient client = CLIENT.newBuilder()
                .addNetworkInterceptor(chain -> {
                    return chain.proceed(scopedRequest(trusted, chain.request()));
                })
                .build();
        return new OkHttpDataSource.Factory(client).setDefaultRequestProperties(source.headers);
    }

    static Request scopedRequest(HttpUrl trusted, Request request) {
        if (sameOrigin(trusted, request.url())) return request;
        return request.newBuilder()
                .removeHeader("Authorization")
                .removeHeader("Play-Link")
                .removeHeader("Cookie")
                .removeHeader("authx")
                .build();
    }

    private static boolean sameOrigin(HttpUrl a, HttpUrl b) {
        return a.scheme().equals(b.scheme()) && a.host().equals(b.host()) && a.port() == b.port();
    }
}
