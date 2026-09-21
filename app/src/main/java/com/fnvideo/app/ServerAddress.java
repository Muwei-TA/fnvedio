package com.fnvideo.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Validates connection settings and gives every session one canonical origin. */
public final class ServerAddress {
    private ServerAddress() { }

    /** Bare host/IP inputs use the standard FnOS port; explicit URLs keep their port. */
    public static String fromUserInput(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("请输入服务器 IP 或地址");
        if (!value.contains("://")) {
            URI uri = parse("http://" + value);
            if (uri.getPort() == -1) {
                try {
                    uri = new URI("http", null, uri.getHost(), 5666,
                            uri.getPath(), uri.getQuery(), uri.getFragment());
                } catch (URISyntaxException invalid) {
                    throw new IllegalArgumentException("服务器地址格式无效");
                }
            }
            value = uri.toString();
        }
        return normalize(value);
    }

    public static String normalize(String input) {
        URI uri = parse(input);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("服务器地址不能包含查询参数或片段");
        }
        String path = uri.getRawPath();
        if (path == null) path = "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!path.isEmpty() && !path.equals("/v") && !path.equals("/v/login")
                && !path.equals("/v/api/v1") && !path.equals("/v/api/v2")) {
            throw new IllegalArgumentException("请输入服务器根地址或 /v 入口");
        }
        return origin(uri);
    }

    /** Media and login URLs may have paths/queries; only their origin is compared. */
    public static boolean sameOrigin(String first, String second) {
        try {
            return origin(parse(first)).equals(origin(parse(second)));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String retainedToken(String previous, String next, String token) {
        String target = normalize(next);
        return sameOrigin(previous, target) && token != null ? token : "";
    }

    private static URI parse(String input) {
        try {
            URI uri = new URI(input == null ? "" : input.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("请输入有效的 HTTP/HTTPS 地址和端口，不要包含账号密码");
            }
            return uri;
        } catch (URISyntaxException invalid) {
            // Do not include the input: it may contain pasted credentials.
            throw new IllegalArgumentException("服务器地址格式无效");
        }
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            port = -1;
        }
        try {
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT),
                    port, null, null, null).toASCIIString();
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("服务器地址格式无效");
        }
    }
}
