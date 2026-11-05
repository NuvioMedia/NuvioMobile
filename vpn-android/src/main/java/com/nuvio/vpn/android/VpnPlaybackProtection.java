package com.nuvio.vpn.android;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import com.nuvio.vpn.core.StreamRoutePolicy;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Playback must await this gate before it opens a remote source. Never logs stream URLs. */
public final class VpnPlaybackProtection {
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Pending pending;
    private static volatile Context application;
    private static volatile VpnPreferences cachedSettings;
    private static volatile boolean profilesRestoring;
    private static final java.util.concurrent.atomic.AtomicLong settingsRevision = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> settingsListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> backupListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.Map<String, Integer> nativeLeases = new java.util.HashMap<>();

    private VpnPlaybackProtection() { }
    static Context application() throws IOException {
        if (application == null) throw new IOException("VPN storage is unavailable.");
        return application;
    }
    static void beginProfileRestore(Context app) {
        profilesRestoring = true;
        WireGuardConnectionService.disconnect(app);
        settingsChanged();
        VpnHttpClient.invalidate();
        fail("VPN profiles are being restored. Retry playback.");
    }
    static void endProfileRestore(Context app) throws Exception {
        // Finish on the playback actor so a new request cannot race the final service generation.
        worker.submit(() -> {
            profilesRestoring = false;
            WireGuardConnectionService.settingsChanged(app);
        }).get(5, TimeUnit.SECONDS);
    }

    static void initialize(Context context) {
        application = context.getApplicationContext();
        worker.execute(() -> { try { settings(); } catch (IOException ignored) { } });
    }

    static VpnPreferences settings() throws IOException {
        if (application == null) throw new IOException("VPN settings are unavailable.");
        VpnPreferences value = new AndroidVpnSettings(application).read();
        cachedSettings = value;
        return value;
    }
    static VpnPreferences settingsForCall() throws IOException {
        if (Looper.myLooper() != Looper.getMainLooper()) return settings();
        VpnPreferences value = cachedSettings;
        if (value == null) throw new IOException("VPN settings are not ready.");
        return value;
    }
    public static long settingsRevision() { return settingsRevision.get(); }
    public static AutoCloseable observeSettings(Runnable listener) {
        settingsListeners.add(listener);
        return () -> settingsListeners.remove(listener);
    }
    public static AutoCloseable observeBackupChanges(Runnable listener) {
        backupListeners.add(listener);
        return () -> backupListeners.remove(listener);
    }
    static void backupChanged() {
        for (Runnable listener : backupListeners) main.post(() -> { if (backupListeners.contains(listener)) listener.run(); });
    }
    static void settingsChanged() {
        backupChanged();
        settingsRevision.incrementAndGet();
        for (Runnable listener : settingsListeners) main.post(() -> { if (settingsListeners.contains(listener)) listener.run(); });
    }

    /** Network-worker boundary, including preload and range requests. */
    public static void beforeRequest(String source) throws IOException {
        beforeRequest(source, () -> Thread.currentThread().isInterrupted());
    }

    static void beforeRequest(String source, java.util.function.BooleanSupplier cancelled) throws IOException {
        Context app = application;
        if (app == null) throw new IOException("VPN settings are unavailable. Playback was not started.");
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Playback network requests require a worker thread.");
        CompletableFuture<Void> request = prepare(app, source);
        long deadline = SystemClock.elapsedRealtime() + 65000;
        try {
            while (true) {
                if (cancelled.getAsBoolean()) { request.cancel(false); throw new IOException("Playback request was cancelled."); }
                if (SystemClock.elapsedRealtime() >= deadline) throw new java.util.concurrent.TimeoutException();
                try { request.get(250, TimeUnit.MILLISECONDS); return; }
                catch (java.util.concurrent.TimeoutException waiting) { }
            }
        }
        catch (InterruptedException interrupted) {
            request.cancel(false);
            Thread.currentThread().interrupt();
            throw new IOException("Playback request was cancelled.");
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("VPN protection could not be verified. Playback was not started.");
        } catch (java.util.concurrent.TimeoutException failure) {
            request.cancel(false);
            throw new IOException("VPN connection timed out. Playback was not started.");
        }
    }

    static boolean hasRequest(String token) {
        Pending request = pending;
        return request != null && request.token.equals(token);
    }

    /** Native HTTP/HTTPS players use a local proxy; its WireGuard side stays idle until HTTP arrives. */
    public static CompletableFuture<NativeProxy> proxyForNativePlayer(Context context) {
        CompletableFuture<NativeProxy> result = new CompletableFuture<>();
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            if (result.isDone()) return;
            try {
                if (profilesRestoring) throw new IOException("VPN profiles are being restored.");
                VpnPreferences settings = new AndroidVpnSettings(app).read();
                cachedSettings = settings;
                if (!settings.getMode().isPlaybackScoped()) { result.complete(null); return; }
                proxyReady(new ProxyRequest(app, settings.getSelectedProfileId(), settings.getMode(), result));
            } catch (Exception failure) { result.completeExceptionally(new IOException("HTTP playback protection could not be prepared.")); }
        });
        return result;
    }

    public static final class NativeProxy implements AutoCloseable {
        private final Context app;
        private final HttpStreamTunnel proxy;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private NativeProxy(Context app, HttpStreamTunnel proxy) { this.app = app; this.proxy = proxy; }
        public String address() { return "http://nuvio:" + proxy.token + "@127.0.0.1:" + proxy.port; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            worker.execute(() -> {
                synchronized (nativeLeases) {
                    int remaining = nativeLeases.getOrDefault(proxy.token, 1) - 1;
                    if (remaining <= 0) nativeLeases.remove(proxy.token); else nativeLeases.put(proxy.token, remaining);
                }
                // A short handoff grace period avoids tearing down a proxy between channel changes.
                worker.schedule(() -> WireGuardConnectionService.releaseProxy(app, proxy.token), 2, TimeUnit.SECONDS);
            });
        }
    }

    static boolean retireUnusedProxy(HttpStreamTunnel proxy) {
        synchronized (nativeLeases) {
            if (nativeLeases.getOrDefault(proxy.token, 0) != 0
                    || (pending != null && pending.mode.isPlaybackScoped())) return false;
            proxy.retiring = true;
            return true;
        }
    }

    private static void proxyReady(ProxyRequest request) {
        try {
            if (profilesRestoring) throw new IOException("VPN profiles are being restored.");
            VpnPreferences settings = new AndroidVpnSettings(request.app).read();
            if (settings.getMode() != request.mode || !java.util.Objects.equals(request.profile, settings.getSelectedProfileId()))
                throw new IOException("VPN settings changed before playback started.");
            synchronized (nativeLeases) {
                HttpStreamTunnel proxy = WireGuardConnectionService.readyProxy(request.profile);
                if (proxy != null) {
                    if (request.result.isDone()) WireGuardConnectionService.releaseProxy(request.app, proxy.token);
                    else {
                        nativeLeases.put(proxy.token, nativeLeases.getOrDefault(proxy.token, 0) + 1);
                        NativeProxy lease = new NativeProxy(request.app, proxy);
                        if (!request.result.complete(lease)) lease.close();
                    }
                    return;
                }
            }
            if (SystemClock.elapsedRealtime() - request.since > 30000) throw new IOException("HTTP playback protection timed out.");
            if (!WireGuardConnectionService.isRunning()) {
                if (request.result.isDone()) return;
                WireGuardConnectionService.standby(request.app, request.profile, request.mode);
            } else if (WireGuardConnectionService.snapshot().phase == WireGuardConnectionService.Phase.ERROR) {
                WireGuardConnectionService.disconnect(request.app);
            }
            worker.schedule(() -> proxyReady(request), 250, TimeUnit.MILLISECONDS);
        } catch (Exception failure) { request.result.completeExceptionally(new IOException("HTTP playback protection could not be prepared.")); }
    }

    private static final class ProxyRequest {
        final Context app;
        final String profile;
        final ProtectionMode mode;
        final CompletableFuture<NativeProxy> result;
        final long since = SystemClock.elapsedRealtime();
        ProxyRequest(Context app, String profile, ProtectionMode mode, CompletableFuture<NativeProxy> result) {
            this.app = app; this.profile = profile; this.mode = mode; this.result = result;
        }
    }

    public static CompletableFuture<Void> prepare(Context context, String source) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            try {
                if (profilesRestoring) throw new IOException("VPN profiles are being restored.");
                VpnPreferences settings = new AndroidVpnSettings(app).read();
                cachedSettings = settings;
                if (settings.getMode() == ProtectionMode.OFF) { result.complete(null); return; }
                URI uri = source == null ? null : new URI(source);
                String scheme = uri == null ? null : uri.getScheme();
                boolean remote = scheme != null && !"file".equalsIgnoreCase(scheme)
                        && !"content".equalsIgnoreCase(scheme) && !"asset".equalsIgnoreCase(scheme);
                if (!remote
                        || (settings.getMode() == ProtectionMode.HTTP_ONLY && !"http".equalsIgnoreCase(scheme))) {
                    result.complete(null);
                    return;
                }
                if (settings.getMode().isPlaybackScoped()) {
                    StreamRoutePolicy.Route route = StreamRoutePolicy.decide(uri, settings.getMode(), true);
                    if (route == StreamRoutePolicy.Route.DIRECT) { result.complete(null); return; }
                    if (route == StreamRoutePolicy.Route.BLOCK) throw new IOException("Unsupported protected stream address.");
                }
                String profile = settings.getSelectedProfileId();
                if (profile == null) throw new IOException("Select a VPN profile in Settings before playing.");
                if (WireGuardConnectionService.verifiedFor(profile, settings.getMode())) { result.complete(null); return; }
                if (pending != null && (!pending.profile.equals(profile) || pending.mode != settings.getMode())) {
                    Pending obsolete = pending;
                    pending = null;
                    obsolete.result.completeExceptionally(new IOException("VPN settings changed before playback started."));
                }
                if (pending == null) {
                    // Validate the selected protocol and routes before requesting consent or starting a service.
                    WireGuardProfiles.prepareSelected(app, settings.getMode());
                    Pending request = new Pending(app, profile, settings.getMode());
                    synchronized (nativeLeases) { pending = request; }
                    if ((app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                        android.util.Log.i("NuvioVpn", "stream_requested_vpn; scope=" + request.mode.name());
                    if (request.mode.isPlaybackScoped() || android.net.VpnService.prepare(app) == null) start(request);
                    else main.post(() -> {
                        try {
                            app.startActivity(new Intent(app, VpnPlaybackConsentActivity.class)
                                    .putExtra("request", request.token)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (RuntimeException failure) { fail("Open VPN settings to grant Android VPN permission."); }
                    });
                    worker.schedule(() -> check(request), 250, TimeUnit.MILLISECONDS);
                }
                pending.result.whenComplete((value, error) -> {
                    if (error == null) result.complete(null); else result.completeExceptionally(error);
                });
            } catch (Exception | LinkageError failure) {
                result.completeExceptionally(new IOException("VPN protection could not be prepared. Check your selected profile and scope."));
            }
        });
        return result;
    }

    private static void start(Pending request) {
        try {
            if (pending != request || request.started) return;
            VpnPreferences settings = new AndroidVpnSettings(request.app).read();
            if (settings.getMode() != request.mode || !request.profile.equals(settings.getSelectedProfileId()))
                throw new IOException("VPN settings changed.");
            if (WireGuardConnectionService.isRunning()) {
                WireGuardConnectionService.Phase phase = WireGuardConnectionService.snapshot().phase;
                HttpStreamTunnel proxy = WireGuardConnectionService.readyProxy(request.profile);
                if (request.mode.isPlaybackScoped() && proxy != null && !proxy.isConfigured()) {
                    request.started = true;
                    WireGuardConnectionService.demandHttp(request.app, request.profile);
                    return;
                }
                if (WireGuardConnectionService.ownsProfile(request.profile, request.mode)
                        && phase != WireGuardConnectionService.Phase.ERROR
                        && phase != WireGuardConnectionService.Phase.STANDBY
                        && phase != WireGuardConnectionService.Phase.IDLE) {
                    request.started = true;
                    return;
                }
                WireGuardConnectionService.disconnect(request.app);
                worker.schedule(() -> start(request), 250, TimeUnit.MILLISECONDS);
                return;
            }
            request.started = true;
            WireGuardConnectionService.connect(request.app, request.profile, request.mode);
        } catch (Exception failure) { fail("VPN connection could not start. Check VPN settings."); }
    }

    private static void check(Pending request) {
        if (pending != request) return;
        try {
            VpnPreferences settings = new AndroidVpnSettings(request.app).read();
            if (settings.getMode() != request.mode || !request.profile.equals(settings.getSelectedProfileId())) {
                fail("VPN settings changed before playback started."); return;
            }
            if (WireGuardConnectionService.verifiedFor(request.profile, request.mode)) {
                pending = null;
                request.result.complete(null);
                return;
            }
            if (request.started && WireGuardConnectionService.snapshot().phase == WireGuardConnectionService.Phase.ERROR) {
                fail("VPN could not be verified. Playback was not started."); return;
            }
            if (SystemClock.elapsedRealtime() - request.since > 60000) {
                fail("VPN connection timed out. Playback was not started."); return;
            }
            worker.schedule(() -> check(request), 250, TimeUnit.MILLISECONDS);
        } catch (Exception failure) { fail("VPN settings could not be read. Playback was not started."); }
    }

    static void consentResult(String token, boolean granted) {
        worker.execute(() -> {
            if (pending == null || !pending.token.equals(token)) return;
            if (granted) start(pending); else fail("VPN permission was declined. Playback was not started.");
        });
    }

    private static void fail(String message) {
        // All pending state belongs to this actor, including UI-originated failures.
        worker.execute(() -> {
            Pending request = pending;
            pending = null;
            if (request != null) request.result.completeExceptionally(new IOException(message));
        });
    }

    private static final class Pending {
        final Context app;
        final String token = java.util.UUID.randomUUID().toString();
        final String profile;
        final ProtectionMode mode;
        final long since = SystemClock.elapsedRealtime();
        final CompletableFuture<Void> result = new CompletableFuture<>();
        boolean started;
        Pending(Context app, String profile, ProtectionMode mode) { this.app = app; this.profile = profile; this.mode = mode; }
    }
}
