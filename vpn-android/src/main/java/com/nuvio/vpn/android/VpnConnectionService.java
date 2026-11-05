package com.nuvio.vpn.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import com.wireguard.config.Config;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One foreground lifecycle for UID-wide routing and HTTP-only userspace routing. */
public final class VpnConnectionService extends android.net.VpnService {
    private static final String CONNECT = "com.nuvio.vpn.CONNECT";
    private static final String DISCONNECT = "com.nuvio.vpn.DISCONNECT";
    private static final String DISABLE = "com.nuvio.vpn.DISABLE";
    private static final String CHANGED = "com.nuvio.vpn.SETTINGS_CHANGED";
    private static final String DEMAND_HTTP = "com.nuvio.vpn.DEMAND_HTTP";
    private static final String RELEASE_PROXY = "com.nuvio.vpn.RELEASE_PROXY";
    private static final String CHANNEL = "nuvio-vpn";
    private static final int NOTIFICATION = 7341;
    private static volatile Snapshot current = new Snapshot(Phase.IDLE, 0, 0);
    private static volatile boolean running;
    private static volatile String requestedProfile;
    private static volatile ProtectionMode requestedMode = ProtectionMode.OFF;
    private static volatile HttpStreamTunnel httpProxy;
    private static final AtomicLong epoch = new AtomicLong();
    enum Phase { IDLE, STANDBY, CONNECTING, CONNECTED, WAITING, ERROR }
    static final class Snapshot {
        final Phase phase;
        final long received, sent;
        final String egress;
        final boolean authRejected;
        final ProtectionMode mode;
        Snapshot(Phase phase, long received, long sent) { this(phase, received, sent, null); }
        Snapshot(Phase phase, long received, long sent, String egress) {
            this(phase, received, sent, egress, false);
        }
        Snapshot(Phase phase, long received, long sent, String egress, boolean authRejected) {
            this.authRejected = authRejected;
            this.phase = phase; this.received = received; this.sent = sent; this.egress = egress;
            mode = phase == Phase.IDLE ? ProtectionMode.OFF : requestedMode;
        }
    }
    static Snapshot snapshot() { return current; }
    static boolean isRunning() { return running; }
    static long epoch() { return epoch.get(); }
    static boolean ownsProfile(String profile, ProtectionMode mode) {
        return running && mode == requestedMode && profile != null && profile.equals(requestedProfile);
    }
    static boolean verifiedFor(String profile, ProtectionMode mode) {
        return ownsProfile(profile, mode) && current.phase == Phase.CONNECTED;
    }
    static HttpStreamTunnel httpProxy(String profile) {
        return requestedMode.isPlaybackScoped() && verifiedFor(profile, requestedMode) ? httpProxy : null;
    }
    static HttpStreamTunnel readyProxy(String profile) {
        HttpStreamTunnel value = httpProxy;
        return running && requestedMode.isPlaybackScoped()
                && java.util.Objects.equals(profile, requestedProfile) && value != null
                && value.isUp() && !value.retiring ? value : null;
    }
    static void standby(Context context, String profile, ProtectionMode mode) {
        Intent intent = new Intent(context, VpnConnectionService.class).setAction(CONNECT)
                .putExtra("profile", profile).putExtra("scope", mode.name()).putExtra("standby", true);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }
    static void demandHttp(Context context, String profile) {
        context.startService(new Intent(context, VpnConnectionService.class).setAction(DEMAND_HTTP).putExtra("profile", profile));
    }
    static void releaseProxy(Context context, String token) {
        if (running) context.startService(new Intent(context, VpnConnectionService.class).setAction(RELEASE_PROXY).putExtra("token", token));
    }
    static void connect(Context context, String expectedProfile) {
        connect(context, expectedProfile, ProtectionMode.APP_WIDE);
    }
    static void connect(Context context, String expectedProfile, ProtectionMode mode) {
        Intent intent = new Intent(context, VpnConnectionService.class).setAction(CONNECT)
                .putExtra("profile", expectedProfile).putExtra("scope", mode.name());
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }
    static void disconnect(Context context) {
        if (running) context.startService(new Intent(context, VpnConnectionService.class).setAction(DISCONNECT));
    }
    static void settingsChanged(Context context) {
        VpnPlaybackProtection.backupChanged();
        userProfileChanged(context);
    }
    static void userProfileChanged(Context context) {
        VpnPlaybackProtection.publishSelectionChange();
        VpnHttpClient.invalidate();
        if (running) context.startService(new Intent(context, VpnConnectionService.class).setAction(CHANGED));
    }

    // SDK/JNI calls are serialized across service destruction and recreation.
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> monitor;
    private final AtomicLong generation = new AtomicLong();
    private TunnelEngine engine;
    private String profile;
    private ProtectionMode scope = ProtectionMode.OFF;
    private volatile boolean destroyed;
    private long started, lastProbe, lastStatsLog, lastEgressProbe;
    private boolean probeVerified;
    private long verifiedSession;
    private boolean demandFailed;
    private String egress;

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.vpn_profiles_title), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        monitor = worker.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (CONNECT.equals(action)) {
            long token = generation.incrementAndGet();
            String expected = intent.getStringExtra("profile");
            ProtectionMode mode;
            try { mode = ProtectionMode.valueOf(intent.getStringExtra("scope")); }
            catch (RuntimeException invalid) { mode = ProtectionMode.OFF; }
            current = new Snapshot(Phase.CONNECTING, 0, 0);
            requestedProfile = expected;
            requestedMode = mode;
            current = new Snapshot(Phase.CONNECTING, 0, 0);
            epoch.incrementAndGet();
            startForeground(NOTIFICATION, notification());
            ProtectionMode expectedMode = mode;
            boolean standby = intent.getBooleanExtra("standby", false) && mode.isPlaybackScoped();
            worker.execute(() -> begin(expected, expectedMode, standby, token));
        } else if (DEMAND_HTTP.equals(action)) {
            String expected = intent.getStringExtra("profile");
            worker.execute(() -> {
                try {
                    if (!(engine instanceof HttpStreamTunnel) || !java.util.Objects.equals(profile, expected) || !matchesSettings())
                        throw new IllegalStateException("Proxy session changed.");
                    HttpStreamTunnel proxy = (HttpStreamTunnel) engine;
                    if (!proxy.isConfigured()) configurePlayback(proxy, profile, scope);
                    demandFailed = false;
                    current = new Snapshot(Phase.CONNECTING, 0, 0);
                    poll();
                } catch (Exception | LinkageError failure) {
                    demandFailed = true;
                    current = new Snapshot(Phase.ERROR, 0, 0);
                    event("vpn_http_demand_failed: " + failure.getClass().getSimpleName());
                    updateNotification();
                }
            });
        } else if (RELEASE_PROXY.equals(action)) {
            String token = intent.getStringExtra("token");
            worker.execute(() -> {
                HttpStreamTunnel proxy = httpProxy;
                if (proxy != null && token != null && token.equals(proxy.token)
                        && !proxy.isConfigured() && VpnPlaybackProtection.retireUnusedProxy(proxy)) end();
                else if (engine == null) stopSelf();
            });
        } else if (DISCONNECT.equals(action)) {
            current = new Snapshot(Phase.IDLE, 0, 0);
            requestedProfile = null;
            generation.incrementAndGet();
            worker.execute(this::end);
        } else if (DISABLE.equals(action)) {
            generation.incrementAndGet();
            worker.execute(() -> {
                try {
                    new AndroidVpnSettings(this).saveMode(ProtectionMode.OFF);
                    VpnPlaybackProtection.settingsChanged();
                    VpnHttpClient.invalidate();
                    end();
                } catch (Exception failure) { event("vpn_disable_settings_failed"); }
            });
        } else if (CHANGED.equals(action)) {
            generation.incrementAndGet();
            worker.execute(() -> { if (!matchesSettings()) end(); });
        }
        return START_NOT_STICKY;
    }
    private void begin(String expected, ProtectionMode mode, boolean standby, long token) {
        try {
            if (engine != null) throw new IllegalStateException("Disconnect before reconnecting.");
            VpnPreferences settings = new AndroidVpnSettings(this).read();
            if (mode == ProtectionMode.OFF || settings.getMode() != mode || (!standby && expected == null)
                    || !java.util.Objects.equals(expected, settings.getSelectedProfileId()) || generation.get() != token)
                throw new IllegalStateException("Connection selection changed.");
            if (mode == ProtectionMode.APP_WIDE && android.net.VpnService.prepare(this) != null)
                throw new IllegalStateException("VPN consent is required.");
            profile = expected;
            scope = mode;
            started = SystemClock.elapsedRealtime();
            lastProbe = lastStatsLog = lastEgressProbe = 0;
            probeVerified = false;
            demandFailed = false;
            egress = null;
            if (!matchesSettings() || generation.get() != token) throw new IllegalStateException("Connection selection changed.");
            if (mode.isPlaybackScoped()) {
                HttpStreamTunnel proxy = new HttpStreamTunnel(null, mode == ProtectionMode.HTTP_HTTPS);
                engine = proxy;
                if (!standby) configurePlayback(proxy, expected, mode);
            } else if (selectedIsOpenVpn(expected)) {
                engine = OpenVpnSession.selected(this, expected, null);
            } else {
                Config config = WireGuardProfiles.prepareSelected(this, mode);
                engine = new AppWideWireGuardEngine(this, config, failed -> {
                        if (destroyed || engine != failed) return;
                        generation.incrementAndGet();
                        current = new Snapshot(Phase.ERROR, 0, 0);
                        VpnHttpClient.invalidate();
                        event("vpn_down");
                        updateNotification();
                    });
            }
            httpProxy = engine instanceof HttpStreamTunnel ? (HttpStreamTunnel) engine : null;
            if (destroyed || !matchesSettings() || generation.get() != token) { end(); return; }
            event(standby ? "http_proxy_standby; no_vpn_connection" : "vpn_interface_up; scope=" + mode.name() + "; handshake_pending");
            current = new Snapshot(standby ? Phase.STANDBY : Phase.CONNECTING, 0, 0);
            poll();
        } catch (Exception | LinkageError failure) {
            event("vpn_connect_failed: " + failure.getClass().getSimpleName());
            closeEngine();
            if (!destroyed) { current = new Snapshot(Phase.ERROR, 0, 0); updateNotification(); }
        }
    }
    private boolean selectedIsOpenVpn(String id) throws Exception {
        try (com.nuvio.vpn.core.ProfileEnvelope.Opened profile = new AndroidProfileStore(this).open(id)) {
            return profile.getProtocol() == com.nuvio.vpn.core.ProfileImport.Protocol.OPENVPN;
        }
    }
    private void configurePlayback(HttpStreamTunnel proxy, String id, ProtectionMode mode) throws Exception {
        if (selectedIsOpenVpn(id)) proxy.configureOpenVpn(this, id);
        else proxy.configure(WireGuardProfiles.prepareSelected(this, mode));
    }
    private boolean matchesSettings() {
        try {
            VpnPreferences value = new AndroidVpnSettings(this).read();
            return scope != ProtectionMode.OFF && value.getMode() == scope && java.util.Objects.equals(profile, value.getSelectedProfileId());
        } catch (Exception failure) { return false; }
    }
    private void poll() {
        if (destroyed || engine == null) return;
        long token = generation.get();
        try {
            if (!matchesSettings()) { end(); return; }
            if (engine instanceof HttpStreamTunnel && !((HttpStreamTunnel) engine).isConfigured()) {
                current = new Snapshot(demandFailed ? Phase.ERROR : Phase.STANDBY, 0, 0);
                updateNotification();
                return;
            }
            if (!engine.isUp()) {
                current = new Snapshot(Phase.ERROR, 0, 0, null, engine.authRejected());
                updateNotification();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            TunnelTraffic stats = engine.statistics();
            if (!stats.authenticated || stats.session != verifiedSession) probeVerified = false;
            if (!probeVerified && (stats.session > 0 && stats.session != verifiedSession || now - lastProbe >= 10000)) {
                lastProbe = now;
                verifiedSession = stats.session;
                probeVerified = engine.probe();
                stats = engine.statistics();
            }
            boolean verified = stats.authenticated && stats.session == verifiedSession
                    && stats.received > 0 && probeVerified;
            if (verified && egress == null && (lastEgressProbe == 0 || now - lastEgressProbe >= 60000)) {
                lastEgressProbe = now;
                egress = engine.egress();
            }
            if (destroyed || generation.get() != token || !engine.isUp()) return;
            Phase phase = verified ? Phase.CONNECTED : now - started > 30000 ? Phase.WAITING : Phase.CONNECTING;
            if (phase != current.phase) event(verified ? "vpn_handshake_and_tunneled_probe_verified" : "vpn_waiting_for_verified_connection");
            current = new Snapshot(phase, stats.received, stats.sent, verified ? egress : null);
            if (verified && now - lastStatsLog >= 10000) {
                lastStatsLog = now;
                event("vpn_traffic: received=" + stats.received + "; sent=" + stats.sent);
            }
            updateNotification();
        } catch (Exception | LinkageError failure) {
            if (destroyed) return;
            if (current.phase != Phase.ERROR) event("vpn_monitor_failed: " + failure.getClass().getSimpleName());
            current = new Snapshot(Phase.ERROR, 0, 0);
            updateNotification();
        }
    }
    private void closeEngine() {
        TunnelEngine closing = engine;
        engine = null;
        httpProxy = null;
        if (closing != null) {
            try { closing.close(); }
            catch (Exception | LinkageError failure) { event("vpn_stop_failed: " + failure.getClass().getSimpleName()); }
        }
        profile = null;
        scope = ProtectionMode.OFF;
    }
    private void end() {
        current = new Snapshot(Phase.IDLE, 0, 0);
        requestedProfile = null;
        requestedMode = ProtectionMode.OFF;
        closeEngine();
        event("vpn_disconnected");
        stopForeground(true);
        stopSelf();
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, VpnProfilesActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VpnConnectionService.class).setAction(DISABLE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int message = current.phase == Phase.CONNECTED ? (current.mode.isPlaybackScoped() ? (current.mode == ProtectionMode.HTTP_HTTPS ? R.string.vpn_connected_web : R.string.vpn_connected_http) : R.string.vpn_connected_app)
                : current.phase == Phase.STANDBY ? R.string.vpn_connection_unavailable
                : current.phase == Phase.CONNECTING ? R.string.vpn_connecting
                : current.phase == Phase.WAITING ? R.string.vpn_waiting_handshake : R.string.vpn_connection_failed;
        return builder.setSmallIcon(R.drawable.vpn_shield).setContentTitle(getString(R.string.vpn_profiles_title))
                .setContentText(getString(message)).setContentIntent(open).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, getString(R.string.vpn_turn_off), stop).build()).build();
    }
    private void updateNotification() { getSystemService(NotificationManager.class).notify(NOTIFICATION, notification()); }
    private void event(String value) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) android.util.Log.i("NuvioVpn", value);
    }
    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        requestedProfile = null;
        requestedMode = ProtectionMode.OFF;
        generation.incrementAndGet();
        if (monitor != null) monitor.cancel(false);
        worker.execute(this::closeEngine);
        current = new Snapshot(Phase.IDLE, 0, 0);
        super.onDestroy();
    }
    @Override public void onRevoke() {
        generation.incrementAndGet();
        VpnHttpClient.invalidate();
        worker.execute(this::end);
        super.onRevoke();
    }
}
