package com.nuvio.vpn.android;

import android.util.Base64;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;
import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A WireGuard userspace netstack, restricted to authenticated local playback proxy requests. */
final class HttpStreamTunnel implements TunnelEngine {
    static { System.loadLibrary("nuvio-http-vpn"); }
    private volatile long handle;
    private volatile OpenVpnSession openVpn;
    final int port;
    final String token;
    volatile boolean retiring;

    private final boolean tunnelHttps;
    HttpStreamTunnel(Config config) throws IOException { this(config, false); }
    HttpStreamTunnel(Config config, boolean tunnelHttps) throws IOException {
        this.tunnelHttps = tunnelHttps;
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        token = Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        java.util.Arrays.fill(secret, (byte) 0);
        handle = startNative(input(config));
        if (handle == 0) throw new IOException("HTTP-only playback proxy could not start.");
        port = portNative(handle);
        if (port <= 0) { close(); throw new IOException("HTTP-only playback proxy could not start."); }
        configured = config != null;
    }

    private volatile boolean configured;
    boolean isConfigured() { return configured; }
    void configure(Config config) throws IOException {
        if (handle == 0 || !configureNative(handle, input(config))) throw new IOException("HTTP-only WireGuard could not start.");
        configured = true;
    }
    void configureOpenVpn(VpnConnectionService service, String id) throws Exception {
        openVpn = OpenVpnSession.selected(service, id, this);
        configured = true;
    }
    int attachOpenVpn(String[] addresses, String[] dns, int mtu) throws JSONException {
        JSONArray ips = new JSONArray();
        for (String cidr : addresses) ips.put(cidr.substring(0, cidr.lastIndexOf('/')));
        return attachOpenVpnNative(handle, new JSONObject().put("addresses", ips)
                .put("dns", new JSONArray(java.util.Arrays.asList(dns))).put("mtu", mtu).toString());
    }
    private String input(Config config) throws IOException {
        try {
            JSONArray addresses = new JSONArray();
            JSONArray dns = new JSONArray();
            if (config != null) {
                for (InetNetwork address : config.getInterface().getAddresses()) addresses.put(address.getAddress().getHostAddress());
                for (InetAddress address : config.getInterface().getDnsServers()) dns.put(address.getHostAddress());
                if (!config.getInterface().getDnsSearchDomains().isEmpty())
                    throw new IOException("HTTP-only WireGuard does not support DNS search domains.");
            }
            return new JSONObject().put("uapi", config == null ? "" : config.toWgUserspaceString())
                    .put("addresses", addresses).put("dns", dns)
                    .put("mtu", config == null ? 1280 : config.getInterface().getMtu().orElse(1280))
                    .put("token", token).put("tunnelHttps", tunnelHttps).toString();
        } catch (JSONException failure) { throw new IOException("WireGuard configuration could not be prepared."); }
    }
    private static boolean preparePlayback(String address, long request) {
        try { VpnPlaybackProtection.beforeRequest(address, () -> requestCancelledNative(request)); return true; }
        catch (IOException | RuntimeException failure) { return false; }
    }

    @Override public boolean isUp() { return handle != 0 && (openVpn == null || openVpn.isUp()); }
    @Override public boolean authRejected() { return openVpn != null && openVpn.authRejected(); }
    @Override public String lastDetail() { return openVpn == null ? null : openVpn.lastDetail(); }
    @Override public TunnelTraffic statistics() throws IOException {
        if (openVpn != null) return openVpn.statistics();
        long[] stats = statsNative(handle);
        if (stats == null || stats.length != 3) throw new IOException("WireGuard statistics are unavailable.");
        return new TunnelTraffic(stats[0], stats[1], stats[2]);
    }
    @Override public boolean probe() { return handle != 0 && probeNative(handle); }
    @Override public String egress() { return handle == 0 ? null : egressNative(handle); }
    @Override public void close() {
        if (openVpn != null) { openVpn.close(); openVpn = null; }
        long old = handle;
        handle = 0;
        if (old != 0) stopNative(old);
    }
    private static native int attachOpenVpnNative(long handle, String input);
    private static native long startNative(String configuration);
    private static native void stopNative(long handle);
    private static native boolean configureNative(long handle, String configuration);
    private static native int portNative(long handle);
    private static native long[] statsNative(long handle);
    private static native boolean probeNative(long handle);
    private static native String egressNative(long handle);
    private static native boolean requestCancelledNative(long request);
}
