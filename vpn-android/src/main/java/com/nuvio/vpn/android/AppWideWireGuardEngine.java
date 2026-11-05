package com.nuvio.vpn.android;

import android.content.Context;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

/** Android UID routing; HTTPS and all other app sockets use the same VPN interface. */
final class AppWideWireGuardEngine implements TunnelEngine {
    private final Context context;
    private final GoBackend backend;
    private final Tunnel tunnel;
    private volatile boolean closed;

    AppWideWireGuardEngine(Context context, Config config, java.util.function.Consumer<AppWideWireGuardEngine> down) throws Exception {
        this.context = context.getApplicationContext();
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
    @Override public TunnelTraffic statistics() {
        Statistics stats = backend.getStatistics(tunnel);
        long handshake = 0;
        for (com.wireguard.crypto.Key peer : stats.peers()) {
            Statistics.PeerStats value = stats.peer(peer);
            if (value != null) handshake = Math.max(handshake, value.latestHandshakeEpochMillis());
        }
        return new TunnelTraffic(stats.totalRx(), stats.totalTx(), handshake);
    }
    @Override public boolean probe() { return AppVpnProbe.verify(context); }
    @Override public String egress() { return AppVpnProbe.egress(context); }
    @Override public void close() throws Exception {
        closed = true;
        backend.setState(tunnel, Tunnel.State.DOWN, null);
    }
}
