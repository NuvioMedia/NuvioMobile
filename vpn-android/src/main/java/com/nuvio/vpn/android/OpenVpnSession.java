package com.nuvio.vpn.android;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

/** A single official OpenVPN 3 client. The core receives either an app-only
 * Android TUN fd or the packet boundary of the playback proxy's private stack. */
final class OpenVpnSession implements TunnelEngine {
    static { System.loadLibrary("nuvio-openvpn"); }
    private static final Pattern CERT = Pattern.compile("(?im)^\\s*<(?:cert|pkcs12)>\\s*$");
    private static final Pattern AES_CBC = Pattern.compile("(?im)^\\s*(?:--)?cipher\\s+AES-(?:128|192|256)-CBC(?:\\s*(?:[;#].*)?)$");
    private final VpnConnectionService service;
    private final HttpStreamTunnel proxy;
    private long handle;

    private OpenVpnSession(VpnConnectionService service, HttpStreamTunnel proxy) {
        this.service = service;
        this.proxy = proxy;
    }

    static int evaluate(char[] text) throws IOException {
        String profile = new String(text);
        Arrays.fill(text, '\0');
        byte[] bytes = profile.getBytes(StandardCharsets.UTF_8);
        try {
            int result = evaluateNative(bytes, !CERT.matcher(profile).find(), AES_CBC.matcher(profile).find());
            if (result == 0) throw new IOException("Unsupported or invalid OpenVPN profile.");
            return result;
        } finally { Arrays.fill(bytes, (byte)0); }
    }
    static void requireReady(char[] text) throws IOException {
        if (evaluate(text) != 1) throw new IOException("Set the OpenVPN service credentials in VPN profiles.");
    }
    static OpenVpnSession selected(VpnConnectionService service, String id, HttpStreamTunnel proxy) throws Exception {
        try (ProfileEnvelope.Opened opened = new AndroidProfileStore(service).open(id)) {
            if (opened.getProtocol() != ProfileImport.Protocol.OPENVPN) throw new IOException("Profile changed.");
            char[] text = opened.copyContents();
            String profile = new String(text);
            Arrays.fill(text, '\0');
            byte[] bytes = profile.getBytes(StandardCharsets.UTF_8);
            try {
                OpenVpnSession session = new OpenVpnSession(service, proxy);
                session.handle = session.startNative(bytes, !CERT.matcher(profile).find(), AES_CBC.matcher(profile).find());
                if (session.handle == 0) throw new IOException("OpenVPN could not start. Check the profile and service credentials.");
                return session;
            } finally { Arrays.fill(bytes,(byte)0); }
        }
    }
    // Called by the core before connecting its transport socket. In stream modes
    // there is no OS VPN route to protect the underlying socket from.
    private boolean protectSocket(int fd) { return proxy != null || service.protect(fd); }
    private int establish(String[] addresses, String[] dns, int mtu) {
        try {
            if (addresses.length == 0 || dns.length == 0) return -1;
            if (proxy != null) return proxy.attachOpenVpn(addresses, dns, mtu);
            VpnService.Builder builder = service.new Builder().setSession("OpenVPN").setMtu(mtu);
            builder.addAllowedApplication(service.getPackageName());
            boolean ipv4 = false, ipv6 = false;
            for (String cidr : addresses) {
                int slash = cidr.lastIndexOf('/');
                String ip = cidr.substring(0,slash);
                builder.addAddress(ip,Integer.parseInt(cidr.substring(slash+1)));
                if (ip.contains(":")) ipv6 = true; else ipv4 = true;
            }
            for (String server : dns) {
                if (server.contains(":") ? !ipv6 : !ipv4) return -1;
                builder.addDnsServer(server);
            }
            if (ipv4) builder.addRoute("0.0.0.0",0);
            if (ipv6) builder.addRoute("::",0);
            ParcelFileDescriptor tun = builder.establish();
            return tun == null ? -1 : tun.detachFd();
        } catch (Exception failure) { return -1; }
    }
    @Override public boolean isUp() {
        return handle != 0 && statsNative(handle)[2] >= 0;
    }
    @Override public boolean authRejected() { return handle != 0 && statsNative(handle)[2] == -2; }
    @Override public TunnelTraffic statistics() {
        long[] values = statsNative(handle);
        return new TunnelTraffic(values[0], values[1], values[2] > 0, values[2]);
    }
    @Override public boolean probe() { return AppVpnProbe.verify(service); }
    @Override public String egress() { return AppVpnProbe.egress(service); }
    @Override public void close() {
        long old = handle;
        handle = 0;
        if (old != 0) stopNative(old);
    }
    private static native int evaluateNative(byte[] profile, boolean noCert, boolean aesCbc);
    private native long startNative(byte[] profile, boolean noCert, boolean aesCbc);
    private static native long[] statsNative(long handle);
    private static native void stopNative(long handle);
}
