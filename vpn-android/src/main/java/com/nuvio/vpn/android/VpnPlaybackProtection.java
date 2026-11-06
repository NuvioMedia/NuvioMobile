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
    /** Lets the host app collect short, non-secret VPN progress lines (for its start-up report). */
    public static volatile java.util.function.Consumer<String> diagnostics;
    static void report(String line) {
        java.util.function.Consumer<String> sink = diagnostics;
        if (sink == null) return;
        try { sink.accept("VPN: " + line); } catch (RuntimeException ignored) { }
    }
    /** Keeps the real reason a preparation step failed (shown to the person, and written to the report). */
    private static IOException prepareFailure(Throwable failure, String generic) {
        String message = failure instanceof IOException ? failure.getMessage() : null;
        report("prepare failed: " + failure.getClass().getSimpleName() + (message == null ? "" : " - " + message));
        return new IOException(message != null && !message.isEmpty() ? message : generic);
    }
    /** The generic message plus, when the connection noted one, the actual reason. */
    static String withReason(String base) {
        String reason = VpnConnectionService.failureReason();
        return reason == null || reason.isEmpty() ? base : base + " " + reason;
    }
    private static volatile int activeUserProfile;
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
        VpnConnectionService.disconnect(app);
        settingsChanged();
        VpnHttpClient.invalidate();
        fail("VPN profiles are being restored. Retry playback.");
    }
    static void endProfileRestore(Context app) throws Exception {
        // Finish on the playback actor so a new request cannot race the final service generation.
        worker.submit(() -> {
            profilesRestoring = false;
            VpnConnectionService.settingsChanged(app);
        }).get(5, TimeUnit.SECONDS);
    }

    static void initialize(Context context) {
        application = context.getApplicationContext();
        worker.execute(() -> { try { settings(); } catch (IOException ignored) { } });
    }

    static int activeUserProfile() { return activeUserProfile; }

    /** Hosts publish the Nuvio profile before its players and settings start using it. */
    public static synchronized void setActiveUserProfile(int id) {
        synchronized (AndroidVpnSettings.LOCK) {
            if (id <= 0 || activeUserProfile == id) return;
            activeUserProfile = id;
            cachedSettings = null;
        }
        Context app = application;
        if (app != null) {
            VpnConnectionService.userProfileChanged(app);
        }
    }

    public static void removeUserProfile(int id) throws IOException {
        Context app = application;
        if (app != null && new AndroidVpnSettings(app).removeUser(id)) {
            if (id == activeUserProfile) VpnConnectionService.settingsChanged(app);
            else backupChanged();
        }
    }

    static synchronized VpnPreferences settings() throws IOException {
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
        publishSelectionChange();
    }
    static void publishSelectionChange() {
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
            // The reason worked out when the request failed, instead of one message for every cause.
            Throwable cause = failure.getCause();
            String message = cause == null ? null : cause.getMessage();
            throw new IOException(message != null && !message.isEmpty()
                    ? message : "VPN protection could not be verified. Playback was not started.");
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
                VpnPreferences settings = settings();
                if (!settings.getMode().isPlaybackScoped()) { result.complete(null); return; }
                proxyReady(new ProxyRequest(app, settings.getSelectedProfileId(), settings.getMode(), result));
            } catch (Exception failure) { result.completeExceptionally(prepareFailure(failure, "HTTP playback protection could not be prepared.")); }
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
                worker.schedule(() -> VpnConnectionService.releaseProxy(app, proxy.token), 2, TimeUnit.SECONDS);
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
                HttpStreamTunnel proxy = VpnConnectionService.readyProxy(request.profile);
                if (proxy != null) {
                    if (request.result.isDone()) VpnConnectionService.releaseProxy(request.app, proxy.token);
                    else {
                        nativeLeases.put(proxy.token, nativeLeases.getOrDefault(proxy.token, 0) + 1);
                        NativeProxy lease = new NativeProxy(request.app, proxy);
                        if (!request.result.complete(lease)) lease.close();
                    }
                    return;
                }
            }
            if (SystemClock.elapsedRealtime() - request.since > 30000) throw new IOException("HTTP playback protection timed out.");
            if (!VpnConnectionService.isRunning()) {
                if (request.result.isDone()) return;
                VpnConnectionService.standby(request.app, request.profile, request.mode);
            } else if (VpnConnectionService.snapshot().phase == VpnConnectionService.Phase.ERROR) {
                VpnConnectionService.disconnect(request.app);
            }
            worker.schedule(() -> proxyReady(request), 250, TimeUnit.MILLISECONDS);
        } catch (Exception failure) { request.result.completeExceptionally(prepareFailure(failure, "HTTP playback protection could not be prepared.")); }
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
                VpnPreferences settings = settings();
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
                if (VpnConnectionService.verifiedFor(profile, settings.getMode())) { result.complete(null); return; }
                if (pending != null && (!pending.profile.equals(profile) || pending.mode != settings.getMode())) {
                    Pending obsolete = pending;
                    pending = null;
                    obsolete.result.completeExceptionally(new IOException("VPN settings changed before playback started."));
                }
                if (pending == null) {
                    // Validate the selected protocol and routes before requesting consent or starting a service.
                    try (com.nuvio.vpn.core.ProfileEnvelope.Opened opened = new AndroidProfileStore(app).open(settings.getSelectedProfileId())) {
                        if (opened.getProtocol() == com.nuvio.vpn.core.ProfileImport.Protocol.OPENVPN) OpenVpnSession.requireReady(opened.copyContents());
                        else WireGuardProfiles.prepareSelected(app, settings.getMode());
                    }
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
                result.completeExceptionally(prepareFailure(failure, "VPN protection could not be prepared. Check your selected profile and scope."));
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
            if (VpnConnectionService.isRunning()) {
                VpnConnectionService.Phase phase = VpnConnectionService.snapshot().phase;
                HttpStreamTunnel proxy = VpnConnectionService.readyProxy(request.profile);
                if (request.mode.isPlaybackScoped() && proxy != null && !proxy.isConfigured()) {
                    request.started = true;
                    VpnConnectionService.demandHttp(request.app, request.profile);
                    return;
                }
                if (VpnConnectionService.ownsProfile(request.profile, request.mode)
                        && phase != VpnConnectionService.Phase.ERROR
                        && phase != VpnConnectionService.Phase.STANDBY
                        && phase != VpnConnectionService.Phase.IDLE) {
                    request.started = true;
                    return;
                }
                VpnConnectionService.disconnect(request.app);
                worker.schedule(() -> start(request), 250, TimeUnit.MILLISECONDS);
                return;
            }
            request.started = true;
            VpnConnectionService.connect(request.app, request.profile, request.mode);
        } catch (Exception failure) { fail("VPN connection could not start. Check VPN settings."); }
    }

    private static void check(Pending request) {
        if (pending != request) return;
        try {
            VpnPreferences settings = new AndroidVpnSettings(request.app).read();
            if (settings.getMode() != request.mode || !request.profile.equals(settings.getSelectedProfileId())) {
                fail("VPN settings changed before playback started."); return;
            }
            if (VpnConnectionService.verifiedFor(request.profile, request.mode)) {
                pending = null;
                request.result.complete(null);
                return;
            }
            if (request.started && VpnConnectionService.snapshot().phase == VpnConnectionService.Phase.ERROR) {
                fail(VpnConnectionService.snapshot().authRejected ? request.app.getString(R.string.vpn_auth_rejected)
                        : withReason("VPN could not be verified. Playback was not started.")); return;
            }
            if (SystemClock.elapsedRealtime() - request.since > 60000) {
                fail(withReason("VPN connection timed out. Playback was not started.")); return;
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
        report("playback blocked: " + message);
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
