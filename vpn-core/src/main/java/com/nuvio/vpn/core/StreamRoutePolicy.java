package com.nuvio.vpn.core;

import java.net.URI;

/** Evaluate every network request, including redirects and playlist child requests. */
public final class StreamRoutePolicy {
    public enum Route { DIRECT, TUNNEL, BLOCK }

    private StreamRoutePolicy() {}

    /**
     * This is a decision only: it does not establish or verify a tunnel.
     * A transport must enforce TUNNEL and recheck its session before opening a socket.
     * Protection is opt-in; when enabled, HTTP never falls back to DIRECT.
     */
    public static Route decide(URI request, boolean protectionEnabled, boolean tunnelReady) {
        return decide(request, protectionEnabled ? ProtectionMode.HTTP_ONLY : ProtectionMode.OFF, tunnelReady);
    }

    /** APP_WIDE also requires the Android backend to cover non-HTTP sockets and DNS. */
    public static Route decide(URI request, ProtectionMode mode, boolean tunnelReady) {
        if (mode == null || request == null || request.isOpaque() || request.getHost() == null
                || request.getRawFragment() != null || request.getRawUserInfo() != null
                || request.getPort() > 65535 || request.getPort() == 0) {
            return Route.BLOCK;
        }
        String scheme = request.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        if (!https && !"http".equalsIgnoreCase(scheme)) return Route.BLOCK;
        // Local playback proxies must stay local; do not resolve names to decide bypasses.
        if (isLoopback(request.getHost())) return Route.DIRECT;
        if (mode == ProtectionMode.OFF || (https && mode == ProtectionMode.HTTP_ONLY)) return Route.DIRECT;
        return tunnelReady ? Route.TUNNEL : Route.BLOCK;
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)
                || "[::1]".equals(host) || "[0:0:0:0:0:0:0:1]".equals(host)) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4 || !"127".equals(parts[0])) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
