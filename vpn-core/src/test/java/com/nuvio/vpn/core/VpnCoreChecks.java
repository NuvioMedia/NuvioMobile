package com.nuvio.vpn.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.security.GeneralSecurityException;
import javax.crypto.spec.SecretKeySpec;

/** Dependency-free regression checks, runnable with javac/java or :vpn-core:verifyVpnCore. */
public final class VpnCoreChecks {
    private static int checks;
    private static final String WG = "[Interface]\nPrivateKey = secret-key\nAddress = 10.0.0.2/32\n"
            + "[Peer]\nPublicKey = peer-key\nAllowedIPs = 0.0.0.0/0\nEndpoint = vpn.example:51820\n";
    private static final String OVPN = "client\nremote vpn.example 1194\nauth-user-pass\n"
            + "<ca>\ncertificate-placeholder\n</ca>\n<key>\nprivate-placeholder\n</key>\n";

    public static void main(String[] args) throws Exception {
        routing();
        imports();
        encryptedProfiles();
        preferences();
        System.out.println("VPN core: " + checks + " checks passed.");
    }

    private static void routing() {
        route("http://stream.example/live", true, false, StreamRoutePolicy.Route.BLOCK);
        route("http://stream.example/live", true, true, StreamRoutePolicy.Route.TUNNEL);
        route("http://stream.example:8080/live", true, true, StreamRoutePolicy.Route.TUNNEL);
        route("http://stream.example/live", false, false, StreamRoutePolicy.Route.DIRECT);
        route("https://stream.example/live", true, false, StreamRoutePolicy.Route.DIRECT);
        route("HTTPS://stream.example/live", true, true, StreamRoutePolicy.Route.DIRECT);
        // An HTTPS playlist or redirect must not confer a bypass on a subsequent HTTP request.
        route("https://stream.example/playlist.m3u8", true, true, StreamRoutePolicy.Route.DIRECT);
        route("http://cdn.example/segment.ts", true, false, StreamRoutePolicy.Route.BLOCK);
        route("http://cdn.example/segment.ts", true, true, StreamRoutePolicy.Route.TUNNEL);
        route("http://127.0.0.1:8080/stream", true, false, StreamRoutePolicy.Route.DIRECT);
        route("http://127.23.45.67/stream", true, false, StreamRoutePolicy.Route.DIRECT);
        route("http://[::1]:8080/stream", true, false, StreamRoutePolicy.Route.DIRECT);
        route("http://localhost/stream", true, false, StreamRoutePolicy.Route.DIRECT);
        route("http://127.0.0.1.example/stream", true, false, StreamRoutePolicy.Route.BLOCK);
        route("http://localhost.example/stream", true, false, StreamRoutePolicy.Route.BLOCK);
        route("http://192.168.1.2/stream", true, false, StreamRoutePolicy.Route.BLOCK);
        route("http://stream.example:65536/live", true, true, StreamRoutePolicy.Route.BLOCK);
        route("http://stream.example:0/live", true, true, StreamRoutePolicy.Route.BLOCK);
        route("http://user:password@stream.example/live", true, true, StreamRoutePolicy.Route.BLOCK);
        route("ftp://stream.example/live", true, true, StreamRoutePolicy.Route.BLOCK);
        route("magnet:?xt=urn:btih:123", true, true, StreamRoutePolicy.Route.BLOCK);
        route("//stream.example/live", true, true, StreamRoutePolicy.Route.BLOCK);
        route("/relative/segment.ts", true, true, StreamRoutePolicy.Route.BLOCK);
        check(StreamRoutePolicy.decide(null, true, true) == StreamRoutePolicy.Route.BLOCK);
        URI secure = URI.create("https://stream.example/live");
        check(StreamRoutePolicy.decide(secure, ProtectionMode.APP_WIDE, true) == StreamRoutePolicy.Route.TUNNEL);
        check(StreamRoutePolicy.decide(secure, ProtectionMode.APP_WIDE, false) == StreamRoutePolicy.Route.BLOCK);
        check(StreamRoutePolicy.decide(secure, ProtectionMode.HTTP_ONLY, false) == StreamRoutePolicy.Route.DIRECT);
        check(StreamRoutePolicy.decide(secure, ProtectionMode.OFF, false) == StreamRoutePolicy.Route.DIRECT);
        check(StreamRoutePolicy.decide(URI.create("http://stream.example:8080/live"),
                ProtectionMode.APP_WIDE, true) == StreamRoutePolicy.Route.TUNNEL);
        check(StreamRoutePolicy.decide(URI.create("http://stream.example:8080/live"),
                ProtectionMode.APP_WIDE, false) == StreamRoutePolicy.Route.BLOCK);
        check(StreamRoutePolicy.decide(URI.create("http://127.0.0.1/stream"),
                ProtectionMode.APP_WIDE, false) == StreamRoutePolicy.Route.DIRECT);
        check(StreamRoutePolicy.decide(secure, ProtectionMode.HTTP_HTTPS, true) == StreamRoutePolicy.Route.TUNNEL);
        check(StreamRoutePolicy.decide(secure, ProtectionMode.HTTP_HTTPS, false) == StreamRoutePolicy.Route.BLOCK);
        check(StreamRoutePolicy.decide(URI.create("http://cdn.example/segment.ts"), ProtectionMode.HTTP_HTTPS, false) == StreamRoutePolicy.Route.BLOCK);
        check(StreamRoutePolicy.decide(URI.create("https://127.0.0.1/stream"), ProtectionMode.HTTP_HTTPS, false) == StreamRoutePolicy.Route.DIRECT);
        check(StreamRoutePolicy.decide(secure, null, true) == StreamRoutePolicy.Route.BLOCK);
    }

    private static void imports() throws Exception {
        // ExpressVPN uses the long form of "client": "tls-client" plus "pull".
        String express = OVPN.replace("client\n", "tls-client\npull\n");
        for (String profile : new String[] {express,
                OVPN.replace("client\n", "--pull\n--tls-client\n")}) {
            try (ProfileImport.Candidate candidate = read(profile, ProfileImport.Protocol.OPENVPN)) {
                check(candidate.getProtocol() == ProfileImport.Protocol.OPENVPN);
            }
        }
        rejects(express.replace("tls-client\n", ""), ProfileImport.Protocol.OPENVPN);
        rejects(express.replace("pull\n", ""), ProfileImport.Protocol.OPENVPN);
        rejects(express.replace("remote vpn.example 1194\n", ""), ProfileImport.Protocol.OPENVPN);
        rejects(express + "<key>\nunclosed\n", ProfileImport.Protocol.OPENVPN);
        rejects(express + "up untrusted\n", ProfileImport.Protocol.OPENVPN);
        rejects(express + "ca secrets.txt\n", ProfileImport.Protocol.OPENVPN);
        rejects("remote vpn.example 1194\n<ca>\ntls-client\npull\n</ca>\n",
                ProfileImport.Protocol.OPENVPN);
        try (ProfileImport.Candidate candidate = read(WG, ProfileImport.Protocol.WIREGUARD)) {
            check(candidate.getProtocol() == ProfileImport.Protocol.WIREGUARD);
            check(!candidate.toString().contains("secret-key"));
            char[] copy = candidate.copyContents();
            copy[0] = 'x';
            char[] other = candidate.copyContents();
            check(other[0] == '[');
            Arrays.fill(copy, '\0');
            Arrays.fill(other, '\0');
        }
        try (ProfileImport.Candidate candidate = read("\uFEFF" + WG, ProfileImport.Protocol.WIREGUARD)) {
            char[] copy = candidate.copyContents();
            check(copy[0] == '[');
            Arrays.fill(copy, '\0');
        }
        ProfileImport.Candidate closed = read(OVPN, ProfileImport.Protocol.OPENVPN);
        closed.close();
        closed.close();
        try { closed.copyContents(); throw new AssertionError("Closed import was readable."); }
        catch (IllegalStateException expected) { checks++; }
        rejects("", ProfileImport.Protocol.OPENVPN);
        rejects("client\nremote vpn.example\0", ProfileImport.Protocol.OPENVPN);
        rejects("[Interface]\n", ProfileImport.Protocol.WIREGUARD);
        rejects(WG, ProfileImport.Protocol.OPENVPN);
        rejects(OVPN, ProfileImport.Protocol.WIREGUARD);
        for (String hook : new String[] {"PreUp", "PostUp", "PreDown", "PostDown", "SaveConfig", "Table"}) {
            rejects(WG + hook + " = untrusted\n", ProfileImport.Protocol.WIREGUARD);
        }
        for (String option : new String[] {"up", "down", "plugin", "config", "management",
                "management-client", "script-security", "tls-verify", "pkcs11-providers"}) {
            rejects(OVPN + option + " untrusted\n", ProfileImport.Protocol.OPENVPN);
            rejects(OVPN + "--" + option + " untrusted\n", ProfileImport.Protocol.OPENVPN);
        }
        // Provider files often carry setenv; harmless because scripts never run.
        read(OVPN + "setenv CLIENT_CERT 0\nsetenv-safe UV_NAME example\n", ProfileImport.Protocol.OPENVPN).close();
        checks++;
        for (String option : new String[] {"ca", "cert", "key", "auth-user-pass", "tls-auth", "askpass"}) {
            rejects(OVPN + option + " secrets.txt\n", ProfileImport.Protocol.OPENVPN);
        }
        rejects(OVPN + "<key>\nunclosed\n", ProfileImport.Protocol.OPENVPN);
        rejects(OVPN + "<connection>\nremote another.example\n</connection>", ProfileImport.Protocol.OPENVPN);
        try {
            ProfileImport.read(new ByteArrayInputStream(new byte[] {(byte) 0xc3, (byte) 0x28}),
                    ProfileImport.Protocol.OPENVPN);
            throw new AssertionError("Invalid UTF-8 accepted.");
        } catch (IOException expected) { checks++; }
        InputStream oversized = new InputStream() {
            int remaining = ProfileImport.MAX_BYTES + 1;
            @Override public int read() { return remaining-- > 0 ? 'a' : -1; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = Math.min(remaining, length);
                Arrays.fill(bytes, offset, offset + count, (byte) 'a');
                remaining -= count;
                return count;
            }
        };
        try { ProfileImport.read(oversized, ProfileImport.Protocol.OPENVPN);
            throw new AssertionError("Oversized import accepted.");
        } catch (IOException expected) { checks++; }
        final boolean[] inputClosed = {false};
        InputStream ownedByCaller = new ByteArrayInputStream(WG.getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { inputClosed[0] = true; }
        };
        try (ProfileImport.Candidate candidate = ProfileImport.read(ownedByCaller, ProfileImport.Protocol.WIREGUARD)) {
            check(candidate.getProtocol() == ProfileImport.Protocol.WIREGUARD);
            check(!inputClosed[0]);
        }
        ownedByCaller.close();
    }

    private static ProfileImport.Candidate read(String text, ProfileImport.Protocol protocol) throws IOException {
        return ProfileImport.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), protocol);
    }

    private static void encryptedProfiles() throws Exception {
        String id = "a4a6dfda-f490-4728-a7dd-1318e7d385a0";
        String otherId = "a4a6dfda-f490-4728-a7dd-1318e7d385a1";
        byte[] keyBytes = new byte[32];
        Arrays.fill(keyBytes, (byte) 42);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        SecretKeySpec wrong = new SecretKeySpec(new byte[32], "AES");
        for (ProfileImport.Protocol protocol : ProfileImport.Protocol.values()) {
            String raw = protocol == ProfileImport.Protocol.OPENVPN ? OVPN : WG;
            byte[] envelope;
            try (ProfileImport.Candidate candidate = read(raw, protocol)) {
                envelope = ProfileEnvelope.seal(id, "Sample VPN", candidate, key);
                byte[] second = ProfileEnvelope.seal(id, "Sample VPN", candidate, key);
                check(!Arrays.equals(envelope, second));
            }
            check(!new String(envelope, StandardCharsets.ISO_8859_1).contains("Sample VPN"));
            check(!new String(envelope, StandardCharsets.ISO_8859_1).contains("secret-key"));
            ProfileEnvelope.Opened opened = ProfileEnvelope.open(id, envelope, key);
            check(opened.getProtocol() == protocol);
            check(opened.getLabel().equals("Sample VPN"));
            char[] recovered = opened.copyContents();
            check(Arrays.equals(recovered, raw.toCharArray()));
            Arrays.fill(recovered, '\0');
            check(!opened.toString().contains("secret-key"));
            opened.close();
            try { opened.copyContents(); throw new AssertionError("Closed profile was readable."); }
            catch (IllegalStateException expected) { checks++; }
            rejectsEnvelope(otherId, envelope, key);
            rejectsEnvelope(id, envelope, wrong);
            byte[] corrupted = envelope.clone();
            corrupted[corrupted.length - 1] ^= 1;
            rejectsEnvelope(id, corrupted, key);
            corrupted = envelope.clone();
            corrupted[4] ^= 1;
            rejectsEnvelope(id, corrupted, key);
            corrupted = envelope.clone();
            corrupted[0] ^= 1;
            rejectsEnvelope(id, corrupted, key);
            rejectsEnvelope(id, Arrays.copyOf(envelope, envelope.length - 1), key);
            rejectsEnvelope(id, new byte[0], key);
        }
        try (ProfileImport.Candidate candidate = read(WG, ProfileImport.Protocol.WIREGUARD)) {
            try { ProfileEnvelope.seal(id, " ", candidate, key); throw new AssertionError("Empty label accepted."); }
            catch (IOException expected) { checks++; }
            char[] longName = new char[257];
            Arrays.fill(longName, 'x');
            try { ProfileEnvelope.seal(id, new String(longName), candidate, key); throw new AssertionError("Long label accepted."); }
            catch (IOException expected) { checks++; }
        }
        try { ProfileEnvelope.validateId("../../profile"); throw new AssertionError("Invalid ID accepted."); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void preferences() throws Exception {
        String id = "a4a6dfda-f490-4728-a7dd-1318e7d385a0";
        String other = "a4a6dfda-f490-4728-a7dd-1318e7d385a1";
        VpnPreferences defaults = VpnPreferences.defaults();
        check(defaults.getMode() == ProtectionMode.OFF);
        check(defaults.getSelectedProfileId() == null);
        for (ProtectionMode mode : ProtectionMode.values()) {
            VpnPreferences legacy = VpnPreferences.decode(mode.name().getBytes(StandardCharsets.US_ASCII));
            check(legacy.getMode() == mode);
            check(legacy.getSelectedProfileId() == null);
            for (String selected : new String[] {null, id}) {
                VpnPreferences value = new VpnPreferences(mode, selected);
                check(value.encode().length <= VpnPreferences.MAX_BYTES);
                VpnPreferences recovered = VpnPreferences.decode(value.encode());
                check(value.equals(recovered));
                check(value.hashCode() == recovered.hashCode());
            }
        }
        VpnPreferences selected = defaults.withSelectedProfile(id).withMode(ProtectionMode.APP_WIDE);
        check(selected.getSelectedProfileId().equals(id));
        check(selected.getMode() == ProtectionMode.APP_WIDE);
        check(defaults.equals(VpnPreferences.defaults()));
        check(selected.withMode(ProtectionMode.HTTP_ONLY).getSelectedProfileId().equals(id));
        check(selected.withMode(ProtectionMode.OFF).getSelectedProfileId().equals(id));
        check(selected.withSelectedProfile(other).getMode() == ProtectionMode.APP_WIDE);
        check(selected.withSelectedProfile(other).getSelectedProfileId().equals(other));
        check(selected.withSelectedProfile(null).equals(defaults));
        check(!selected.equals(defaults));
        check(!selected.equals(null));
        check(!selected.equals(id));
        String header = "NUVIO-VPN-2\n";
        for (String invalid : new String[] {"", "off", "OFF\n", "OFF\u0000", " APP_WIDE",
                "NUVIO-VPN-3\nOFF\n", header + "APP_WIDE", header + "OFF\n../secret-key",
                header + "OFF\n" + id.toUpperCase(java.util.Locale.ROOT), header + "OFF\n" + id + "\n",
                header + "UNKNOWN\n", header + "OFF\nsecret-key", header + "OFF\n\uFFFD"}) {
            rejectsPreferences(invalid.getBytes(StandardCharsets.UTF_8));
        }
        rejectsPreferences(null);
        rejectsPreferences(new byte[VpnPreferences.MAX_BYTES + 1]);
        try { new VpnPreferences(null, id); throw new AssertionError("Null mode accepted."); }
        catch (IllegalArgumentException expected) { checks++; }
        try { selected.withSelectedProfile("../../secret-key"); throw new AssertionError("Invalid selection accepted."); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void rejectsPreferences(byte[] bytes) throws Exception {
        try { VpnPreferences.decode(bytes); throw new AssertionError("Invalid preferences accepted."); }
        catch (IOException expected) { check(!expected.getMessage().contains("secret-key")); }
    }

    private static void rejectsEnvelope(String id, byte[] bytes, SecretKeySpec key) throws Exception {
        try (ProfileEnvelope.Opened unexpected = ProfileEnvelope.open(id, bytes, key)) {
            throw new AssertionError("Invalid encrypted profile accepted: " + unexpected.getProtocol());
        } catch (GeneralSecurityException | IOException expected) { checks++; }
    }

    private static void rejects(String text, ProfileImport.Protocol protocol) throws Exception {
        try (ProfileImport.Candidate candidate = read(text, protocol)) {
            throw new AssertionError("Unsupported configuration accepted: " + candidate.getProtocol());
        } catch (IOException expected) {
            check(!expected.getMessage().contains("secret-key"));
        }
    }

    private static void route(String url, boolean enabled, boolean ready, StreamRoutePolicy.Route expected) {
        check(StreamRoutePolicy.decide(URI.create(url), enabled, ready) == expected);
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("VPN core check " + checks + " failed.");
    }
}
