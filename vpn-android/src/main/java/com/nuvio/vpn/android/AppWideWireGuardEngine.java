package com.nuvio.vpn.android;

import android.content.Context;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import java.net.HttpURLConnection;
import java.net.URL;

/** Android UID routing; HTTPS and all other app sockets use the same VPN interface. */
final class AppWideWireGuardEngine implements WireGuardEngine {
    private final GoBackend backend;
    private final Tunnel tunnel;
    private volatile boolean closed;

    AppWideWireGuardEngine(Context context, Config config, java.util.function.Consumer<AppWideWireGuardEngine> down) throws Exception {
        backend = new GoBackend(context.getApplicationContext());
        tunnel = new Tunnel() {
            @Override public String getName() { return "NuvioVPN"; }
            @Override public void onStateChange(State state) {
                if (state == State.DOWN && !closed) down.accept(AppWideWireGuardEngine.this);
            }
        };
        try { backend.setState(tunnel, Tunnel.State.UP, config); }
        catch (Exception | LinkageError failure) {
            try { close(); } catch (Exception | LinkageError ignored) { }
            throw failure;
        }
    }
    @Override public boolean isUp() { return !closed && backend.getState(tunnel) == Tunnel.State.UP; }
    @Override public WireGuardTraffic statistics() {
        Statistics stats = backend.getStatistics(tunnel);
        long handshake = 0;
        for (com.wireguard.crypto.Key peer : stats.peers()) {
            Statistics.PeerStats value = stats.peer(peer);
            if (value != null) handshake = Math.max(handshake, value.latestHandshakeEpochMillis());
        }
        return new WireGuardTraffic(stats.totalRx(), stats.totalTx(), handshake);
    }
    @Override public boolean probe() {
        HttpURLConnection connection = null;
        try {
            connection = open("https://www.gstatic.com/generate_204");
            return connection.getResponseCode() == 204;
        } catch (Exception failure) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }
    @Override public String egress() {
        HttpURLConnection connection = null;
        try {
            connection = open("https://api.ipify.org");
            if (connection.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = connection.getInputStream()) {
                int value;
                while ((value = input.read()) != -1) {
                    if (bytes.size() == 64) return null;
                    bytes.write(value);
                }
            }
            return com.wireguard.config.InetAddresses.parse(new String(bytes.toByteArray(),
                    java.nio.charset.StandardCharsets.US_ASCII).trim()).getHostAddress();
        } catch (Exception failure) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }
    @Override public void close() throws Exception {
        closed = true;
        backend.setState(tunnel, Tunnel.State.DOWN, null);
    }
}
