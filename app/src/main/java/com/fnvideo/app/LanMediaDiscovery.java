package com.fnvideo.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Credential-free discovery, limited to the current private IPv4 LAN and at most one /24. */
public final class LanMediaDiscovery {
    public interface Callback {
        void onFound(String origin);
        void onFinished(String message);
    }

    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    public LanMediaDiscovery(Context context) {
        connectivity = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Call on the main thread. Callbacks are delivered on the main thread. */
    public void start(Callback callback) {
        cancel();
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null
                : connectivity.getNetworkCapabilities(network);
        LinkProperties properties = network == null ? null : connectivity.getLinkProperties(network);
        if (capabilities == null || properties == null
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            callback.onFinished("请连接 Wi-Fi 或有线局域网，也可以手动输入地址");
            return;
        }
        List<String> hosts = Collections.emptyList();
        for (LinkAddress address : properties.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) {
                hosts = candidateHosts(address.getAddress().getAddress(), address.getPrefixLength());
                if (!hosts.isEmpty()) break;
            }
        }
        if (hosts.isEmpty()) {
            callback.onFinished("未找到可搜索的局域网 IPv4 地址，请手动输入地址");
            return;
        }
        Session current = new Session(discoveryClient().newBuilder()
                .socketFactory(network.getSocketFactory()).build());
        session = current;
        current.remaining.set(hosts.size() * 2);
        for (String host : hosts) {
            for (int port : new int[]{5666, 80}) {
                String origin = "http://" + host + (port == 80 ? "" : ":" + port);
                current.workers.execute(() -> {
                    try {
                        if (current.cancelled) return;
                        Call call = current.client.newCall(new Request.Builder().url(origin + "/v/").build());
                        current.calls.add(call);
                        try {
                            if (current.cancelled) { call.cancel(); return; }
                            if (isMediaService(call)) {
                                current.found.incrementAndGet();
                                main.post(() -> {
                                    if (!current.cancelled && session == current) callback.onFound(origin);
                                });
                            }
                        } finally {
                            current.calls.remove(call);
                        }
                    } finally {
                        if (current.remaining.decrementAndGet() == 0) {
                            current.workers.shutdown();
                            main.post(() -> {
                                if (!current.cancelled && session == current) callback.onFinished(
                                        current.found.get() == 0
                                                ? "未发现影视库，可手动输入地址（仅搜索当前局域网网段）"
                                                : "搜索完成");
                            });
                        }
                    }
                });
            }
        }
    }

    /** Stops pending requests and suppresses all callbacks from this search. */
    public void cancel() {
        Session previous = session;
        session = null;
        if (previous != null) {
            previous.cancelled = true;
            for (Call call : previous.calls) call.cancel();
            previous.workers.shutdownNow();
        }
    }

    static OkHttpClient discoveryClient() {
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(700, TimeUnit.MILLISECONDS).callTimeout(1200, TimeUnit.MILLISECONDS).build();
    }

    static boolean isMediaService(Call call) {
        try (Response response = call.execute()) {
            if (response.code() != 200 || response.body() == null) return false;
            String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (!type.contains("text/html")) return false;
            String html = new String(response.peekBody(64 * 1024).bytes(), StandardCharsets.UTF_8);
            return hasProductMarker(html);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean hasProductMarker(String html) {
        // Match product identity in the document title, not arbitrary body text mentioning a NAS.
        return Pattern.compile("<title[^>]*>[^<]*(飞牛影视|trimemedia|fn\\s*video)[^<]*</title>",
                Pattern.CASE_INSENSITIVE).matcher(html).find();
    }

    static List<String> candidateHosts(byte[] ip, int prefixLength) {
        if (ip.length != 4 || prefixLength < 1 || prefixLength > 30) return Collections.emptyList();
        int first = ip[0] & 255;
        int second = ip[1] & 255;
        if (!(first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168))) return Collections.emptyList();
        int address = ((ip[0] & 255) << 24) | ((ip[1] & 255) << 16)
                | ((ip[2] & 255) << 8) | (ip[3] & 255);
        int prefix = Math.max(24, prefixLength);
        int mask = -1 << (32 - prefix);
        int base = address & mask;
        int count = 1 << (32 - prefix);
        List<String> hosts = new ArrayList<>();
        for (int offset = 1; offset < count - 1; offset++) {
            int host = base + offset;
            if (host == address) continue;
            hosts.add(((host >>> 24) & 255) + "." + ((host >>> 16) & 255) + "."
                    + ((host >>> 8) & 255) + "." + (host & 255));
        }
        return hosts;
    }

    private static final class Session {
        final ExecutorService workers = Executors.newFixedThreadPool(12);
        final Set<Call> calls = ConcurrentHashMap.newKeySet();
        final AtomicInteger remaining = new AtomicInteger();
        final AtomicInteger found = new AtomicInteger();
        final OkHttpClient client;
        volatile boolean cancelled;

        Session(OkHttpClient client) {
            this.client = client;
        }
    }
}
