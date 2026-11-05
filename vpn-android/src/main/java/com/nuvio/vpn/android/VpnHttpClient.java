package com.nuvio.vpn.android;

import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.StreamRoutePolicy;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Per-playback adapters. The baseline client's TLS, headers, timeouts and interceptors are retained. */
public final class VpnHttpClient implements Call.Factory {
    private static final List<ClientRef> liveClients = new ArrayList<>();
    private static final java.util.concurrent.atomic.AtomicLong poolRevision = new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<Call> activeCall = new ThreadLocal<>();
    private static final List<Proxy> BLOCKED = Collections.singletonList(
            new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
    private final OkHttpClient baseline;
    private OkHttpClient current;
    private String currentKey;

    public VpnHttpClient(OkHttpClient baseline) { this.baseline = baseline; }

    // Small shared cache keeps range/subtitle/probe callers from creating a pool per request.
    private static final java.util.LinkedHashMap<OkHttpClient, VpnHttpClient> factories =
            new java.util.LinkedHashMap<>(16, 0.75f, true);
    public static synchronized VpnHttpClient wrap(OkHttpClient baseline) {
        VpnHttpClient factory = factories.get(baseline);
        if (factory != null) return factory;
        factory = new VpnHttpClient(baseline);
        factories.put(baseline, factory);
        if (factories.size() > 32) {
            java.util.Iterator<VpnHttpClient> oldest = factories.values().iterator();
            VpnHttpClient retired = oldest.next(); oldest.remove();
            synchronized (retired) { if (retired.current != null) retired.current.connectionPool().evictAll(); }
        }
        return factory;
    }

    @Override public Call newCall(Request request) {
        try { return client(VpnPlaybackProtection.settingsForCall()).newCall(request); }
        catch (IOException unavailable) {
            OkHttpClient.Builder blocked = baseline.newBuilder();
            blocked.interceptors().add(0, chain -> { throw new IOException("VPN settings are unavailable. Playback was not started."); });
            return blocked.build().newCall(request);
        }
    }

    private synchronized OkHttpClient client(VpnPreferences settings) {
        ProtectionMode mode = settings.getMode();
        String profile = mode == ProtectionMode.OFF ? null : settings.getSelectedProfileId();
        String key = mode.name() + ":" + profile + ":" + VpnConnectionService.epoch() + ":" + poolRevision.get();
        if (current != null && key.equals(currentKey)) return current;
        if (current != null) current.connectionPool().evictAll();
        Dispatcher dispatcher = new Dispatcher(baseline.dispatcher().executorService());
        dispatcher.setMaxRequests(baseline.dispatcher().getMaxRequests());
        dispatcher.setMaxRequestsPerHost(baseline.dispatcher().getMaxRequestsPerHost());
        OkHttpClient.Builder builder = baseline.newBuilder().dispatcher(dispatcher).connectionPool(new ConnectionPool());
        builder.interceptors().add(0, chain -> {
            VpnPlaybackProtection.beforeRequest(chain.request().url().toString(), () -> chain.call().isCanceled());
            VpnPreferences latest = VpnPlaybackProtection.settings();
            if (latest.getMode() != mode || (mode != ProtectionMode.OFF && !Objects.equals(profile, latest.getSelectedProfileId())))
                throw new IOException("VPN settings changed. Retry playback.");
            Call previous = activeCall.get();
            activeCall.set(chain.call());
            try { return chain.proceed(chain.request()); }
            finally { if (previous == null) activeCall.remove(); else activeCall.set(previous); }
        });
        if (mode.isPlaybackScoped()) {
            builder.proxy(null).proxySelector(new HttpOnlySelector(baseline, profile, mode));
            builder.proxyAuthenticator((route, response) -> {
                HttpStreamTunnel proxy = VpnConnectionService.httpProxy(profile);
                if (route != null && proxy != null && route.proxy().address() instanceof InetSocketAddress) {
                    InetSocketAddress address = (InetSocketAddress) route.proxy().address();
                    if (address.getPort() == proxy.port && address.getAddress() != null && address.getAddress().isLoopbackAddress()) {
                        if (response.request().header("Proxy-Authorization") != null) return null;
                        return response.request().newBuilder().header("Proxy-Authorization", okhttp3.Credentials.basic("nuvio", proxy.token)).build();
                    }
                }
                return baseline.proxyAuthenticator().authenticate(route, response);
            });
        }
        current = builder.build();
        currentKey = key;
        synchronized (liveClients) {
            liveClients.removeIf(reference -> reference.get() == null);
            liveClients.add(new ClientRef(current, mode));
        }
        return current;
    }

    private static final class ClientRef extends WeakReference<OkHttpClient> {
        final ProtectionMode mode;
        ClientRef(OkHttpClient client, ProtectionMode mode) { super(client); this.mode = mode; }
    }
    static void invalidate() {
        boolean off;
        try { off = VpnPlaybackProtection.settings().getMode() == ProtectionMode.OFF; }
        catch (IOException failure) { off = false; }
        poolRevision.incrementAndGet();
        List<OkHttpClient> active = new ArrayList<>();
        synchronized (liveClients) {
            liveClients.removeIf(reference -> reference.get() == null);
            for (ClientRef reference : liveClients) {
                OkHttpClient client = reference.get();
                if (client != null && !(off && reference.mode == ProtectionMode.OFF)) active.add(client);
            }
        }
        for (OkHttpClient client : active) { client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }
    }

    /** Use the original URLConnection except where HTTP-only routing needs an explicit proxy. */
    public static HttpURLConnection openConnection(URL address) throws IOException {
        return openConnection(address, Connections.baseline);
    }
    public static HttpURLConnection openConnection(URL address, OkHttpClient baseline) throws IOException {
        VpnPlaybackProtection.beforeRequest(address.toString());
        VpnPreferences settings = VpnPlaybackProtection.settings();
        if (!settings.getMode().isPlaybackScoped())
            return (HttpURLConnection) address.openConnection();
        if ("https".equalsIgnoreCase(address.getProtocol()))
            return new PlaybackHttpsURLConnection(address, wrap(baseline).client(settings));
        return new PlaybackHttpURLConnection(address, wrap(baseline).client(settings));
    }
    private static final class Connections {
        static final OkHttpClient baseline = new OkHttpClient();
    }

    private static final class HttpOnlySelector extends ProxySelector {
        private final OkHttpClient baseline;
        private final String profile;
        private final ProtectionMode mode;
        HttpOnlySelector(OkHttpClient baseline, String profile, ProtectionMode mode) { this.baseline = baseline; this.profile = profile; this.mode = mode; }
        @Override public List<Proxy> select(URI uri) {
            StreamRoutePolicy.Route route = StreamRoutePolicy.decide(uri, mode, true);
            if (route == StreamRoutePolicy.Route.BLOCK) return BLOCKED;
            if (route == StreamRoutePolicy.Route.DIRECT) {
                if (StreamRoutePolicy.decide(uri, ProtectionMode.APP_WIDE, true) == StreamRoutePolicy.Route.DIRECT)
                    return Collections.singletonList(Proxy.NO_PROXY);
                if (baseline.proxy() != null) return Collections.singletonList(baseline.proxy());
                ProxySelector selector = baseline.proxySelector();
                return selector == null ? Collections.singletonList(Proxy.NO_PROXY) : selector.select(uri);
            }
            try {
                Call call = activeCall.get();
                VpnPlaybackProtection.beforeRequest(uri.toString(), () -> call != null && call.isCanceled());
                VpnPreferences latest = VpnPlaybackProtection.settings();
                HttpStreamTunnel proxy = VpnConnectionService.httpProxy(profile);
                if (latest.getMode() == mode && Objects.equals(profile, latest.getSelectedProfileId()) && proxy != null)
                    return Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxy.port)));
            } catch (IOException failure) { /* A required tunnel must never select a direct fallback. */ }
            return BLOCKED;
        }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            if (StreamRoutePolicy.decide(uri, mode, true) == StreamRoutePolicy.Route.DIRECT && baseline.proxySelector() != null)
                baseline.proxySelector().connectFailed(uri, address, failure);
        }
    }
}
