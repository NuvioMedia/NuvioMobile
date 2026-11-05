package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs only in the isolated device-check application, never a user's host app. */
@RunWith(AndroidJUnit4.class)
public final class VpnDeviceTest {
    private static final String ID = "a4a6dfda-f490-4728-a7dd-1318e7d385a0";
    private static final String CONFIG = "[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "Address = 10.0.0.2/32\nDNS = 10.0.0.1\n[Peer]\n"
            + "PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\n"
            + "AllowedIPs = 0.0.0.0/0\nEndpoint = 192.0.2.1:51820\n";
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Safety guard: these tests clear only their standalone library test application's files.
        assertEquals("com.nuvio.vpn.android.test", context.getPackageName());
        clean();
        VpnPlaybackProtection.setActiveUserProfile(1);
    }

    @After public void tearDown() { if (context != null) clean(); }

    @Test public void wireGuardStatisticsRecordsResolveAtRuntime() {
        com.wireguard.android.backend.Statistics.PeerStats value =
                new com.wireguard.android.backend.Statistics.PeerStats(123, 456, 789);
        assertEquals(123L, value.rxBytes());
        assertEquals(456L, value.txBytes());
        assertEquals(789L, value.latestHandshakeEpochMillis());
        assertEquals(value, new com.wireguard.android.backend.Statistics.PeerStats(123, 456, 789));
    }

    @Test public void realWireGuardParserAndHostScope() throws Exception {
        WireGuardProfileChecks.main(new String[0]);
    }

    @Test public void playbackGateRespectsScopeAndBlocksUnavailableProtection() throws Exception {
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        VpnPlaybackProtection.beforeRequest("http://192.0.2.1/video");
        VpnPlaybackProtection.prepare(context, "http://192.0.2.1/video").get(3, java.util.concurrent.TimeUnit.SECONDS);
        // Disabled protection must leave existing source validation to the player.
        VpnPlaybackProtection.prepare(context, "source with spaces").get(3, java.util.concurrent.TimeUnit.SECONDS);
        settings.selectProfile(ID);
        settings.saveMode(ProtectionMode.HTTP_ONLY);
        VpnPlaybackProtection.prepare(context, "https://192.0.2.1/video").get(3, java.util.concurrent.TimeUnit.SECONDS);
        try {
            VpnPlaybackProtection.prepare(context, "http://192.0.2.1/video").get(3, java.util.concurrent.TimeUnit.SECONDS);
            fail("HTTP protection must not silently fall back to direct playback.");
        } catch (java.util.concurrent.ExecutionException expected) { }
        settings.saveMode(ProtectionMode.APP_WIDE);
        try {
            VpnPlaybackProtection.prepare(context, "https://192.0.2.1/video").get(3, java.util.concurrent.TimeUnit.SECONDS);
            fail("A missing selected profile must block protected playback.");
        } catch (java.util.concurrent.ExecutionException expected) { }
        assertFalse(VpnConnectionService.isRunning());
        assertNotNull(android.net.VpnService.prepare(context));
    }

    @Test public void appWideConnectionRejectsOffAndHttpOnlyScope() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity screen = instrumentation.startActivitySync(new Intent(context, VpnProfilesActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            for (ProtectionMode mode : new ProtectionMode[] {ProtectionMode.OFF, ProtectionMode.HTTP_ONLY}) {
                AndroidVpnSettings settings = new AndroidVpnSettings(context);
                settings.selectProfile(ID);
                settings.saveMode(mode);
                VpnConnectionService.connect(context, ID);
                long deadline = SystemClock.elapsedRealtime() + 5000;
                while (VpnConnectionService.snapshot().phase != VpnConnectionService.Phase.ERROR
                        && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                assertEquals(VpnConnectionService.Phase.ERROR, VpnConnectionService.snapshot().phase);
                assertNotNull(android.net.VpnService.prepare(context));
                VpnConnectionService.disconnect(context);
                deadline = SystemClock.elapsedRealtime() + 5000;
                while (VpnConnectionService.isRunning() && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(50);
                assertFalse(VpnConnectionService.isRunning());
            }
        } finally {
            VpnConnectionService.disconnect(context);
            instrumentation.runOnMainSync(screen::finish);
        }
    }

    @Test public void nativeProxyCoexistsWithSdkAndKeepsHttpsDirect() throws Exception {
        // Loading both Go libraries in one app process must not break either runtime.
        new com.wireguard.android.backend.GoBackend(context);
        new AndroidVpnSettings(context).saveMode(ProtectionMode.HTTP_ONLY);
        HttpStreamTunnel first = new HttpStreamTunnel(null);
        first.close();
        try (HttpStreamTunnel proxy = new HttpStreamTunnel(null);
             java.net.ServerSocket origin = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            first.close(); // A retired handle cannot close a later session.
            try (java.net.Socket denied = new java.net.Socket("127.0.0.1", proxy.port)) {
                denied.setSoTimeout(3000);
                denied.getOutputStream().write("GET http://example.invalid/ HTTP/1.1\r\nHost: example.invalid\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                assertTrue(new java.io.BufferedReader(new java.io.InputStreamReader(denied.getInputStream(), StandardCharsets.US_ASCII)).readLine().contains("407"));
            }
            Thread echo = new Thread(() -> {
                try (java.net.Socket peer = origin.accept()) {
                    byte[] bytes = new byte[256];
                    int length;
                    while ((length = peer.getInputStream().read(bytes)) != -1) {
                        peer.getOutputStream().write(bytes, 0, length);
                        peer.getOutputStream().flush();
                    }
                } catch (IOException ignored) { }
            });
            echo.start();
            try (java.net.Socket client = new java.net.Socket("127.0.0.1", proxy.port)) {
                client.setSoTimeout(5000);
                String target = "127.0.0.1:" + origin.getLocalPort();
                String auth = android.util.Base64.encodeToString(("nuvio:" + proxy.token).getBytes(StandardCharsets.US_ASCII), android.util.Base64.NO_WRAP);
                client.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\nProxy-Authorization: Basic " + auth + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
                while (header.size() < 4096) {
                    int value = client.getInputStream().read();
                    if (value == -1) break;
                    header.write(value);
                    if (header.toString("US-ASCII").endsWith("\r\n\r\n")) break;
                }
                assertTrue(header.toString("US-ASCII").startsWith("HTTP/1.1 200"));
                byte[] payload = "opaque TLS bytes".getBytes(StandardCharsets.US_ASCII);
                client.getOutputStream().write(payload);
                byte[] received = new byte[payload.length];
                int offset = 0;
                while (offset < received.length) {
                    int count = client.getInputStream().read(received, offset, received.length - offset);
                    if (count == -1) break;
                    offset += count;
                }
                assertArrayEquals(payload, received);
            }
            echo.join(3000);
            assertFalse(echo.isAlive());
            TunnelTraffic traffic = proxy.statistics();
            assertEquals(0, traffic.received);
            assertEquals(0, traffic.sent);
            assertEquals(0, traffic.handshakeMillis);
            assertFalse(proxy.isConfigured());
            assertNotNull(android.net.VpnService.prepare(context));
        }
    }

    @Test public void portableBackupRestoresSelectionAndReencryptsProfiles() throws Exception {
        AndroidProfileStore store = new AndroidProfileStore(context);
        try (ProfileImport.Candidate candidate = ProfileImport.read(new ByteArrayInputStream(CONFIG.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            store.save(ID, "Backup fixture", candidate);
        }
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        settings.save(new VpnPreferences(ProtectionMode.HTTP_HTTPS, ID));
        String portable = VpnBackup.exportSetup(context);
        String envelope = new org.json.JSONObject().put(VpnBackup.KEY,
                new org.json.JSONObject().put("t", "str").put("v", portable)).toString();
        String sealed = VpnBackup.sealCache(envelope);
        assertFalse(sealed.contains("PrivateKey"));
        assertEquals(envelope, VpnBackup.openCache(sealed));
        store.delete(ID);
        settings.save(VpnPreferences.defaults());
        // Simulate a new device: its profile encryption key cannot be the exporting device's key.
        java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        keys.deleteEntry("nuvio.vpn.profiles.v1");
        assertFalse(keys.containsAlias("nuvio.vpn.profiles.v1"));
        try (ProfileImport.Candidate extra = ProfileImport.read(new ByteArrayInputStream(CONFIG.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            store.save("a4a6dfda-f490-4728-a7dd-1318e7d385a1", "Obsolete local profile", extra);
        }
        VpnBackup.restoreSetup(context, portable);
        assertEquals(1, store.list().size());
        assertEquals(new VpnPreferences(ProtectionMode.HTTP_HTTPS, ID), settings.read());
        try (ProfileEnvelope.Opened restored = store.open(ID)) {
            char[] contents = restored.copyContents();
            try { assertArrayEquals(CONFIG.toCharArray(), contents); }
            finally { java.util.Arrays.fill(contents, '\0'); }
        }
        assertEquals(portable, VpnBackup.exportSetup(context));
        org.json.JSONObject invalid = new org.json.JSONObject(portable).put("selected", "11111111-1111-1111-1111-111111111111");
        try { VpnBackup.restoreSetup(context, invalid.toString()); fail("Missing selection accepted"); }
        catch (IOException expected) { }
        assertEquals(portable, VpnBackup.exportSetup(context));
    }

    @Test public void userProfilesInheritOrOverrideThePortableSelection() throws Exception {
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        AndroidProfileStore store = new AndroidProfileStore(context);
        String alternate = "a4a6dfda-f490-4728-a7dd-1318e7d385a1";
        try (ProfileImport.Candidate candidate = ProfileImport.read(
                new ByteArrayInputStream(CONFIG.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            store.save(ID, "Shared", candidate);
            store.save(alternate, "Personal", candidate);
        }
        VpnPreferences shared = new VpnPreferences(ProtectionMode.HTTP_ONLY, ID);
        VpnPreferences own = new VpnPreferences(ProtectionMode.HTTP_HTTPS, alternate);
        settings.save(shared);
        String oldBackup = VpnBackup.exportSetup(context);
        assertFalse(oldBackup.contains("profileOverrides"));
        VpnPlaybackProtection.setActiveUserProfile(2);
        assertEquals(shared, settings.read());
        settings.setShared(false);
        assertEquals(shared, settings.read());
        settings.save(own);
        String backup = VpnBackup.exportSetup(context);
        VpnPlaybackProtection.setActiveUserProfile(3);
        assertEquals(shared, settings.read());
        assertEquals(backup, VpnBackup.exportSetup(context));
        settings.saveMode(ProtectionMode.APP_WIDE);
        VpnPlaybackProtection.setActiveUserProfile(2);
        assertEquals(own, settings.read());
        settings.setShared(true);
        assertEquals(new VpnPreferences(ProtectionMode.APP_WIDE, ID), settings.read());

        VpnBackup.restoreSetup(context, backup);
        assertEquals(own, settings.read());
        assertEquals(backup, VpnBackup.exportSetup(context));
        org.json.JSONObject invalid = new org.json.JSONObject(backup);
        invalid.getJSONObject("profileOverrides").getJSONObject("2")
                .put("selected", "11111111-1111-1111-1111-111111111111");
        try { VpnBackup.restoreSetup(context, invalid.toString()); fail("Missing VPN in override accepted"); }
        catch (IOException expected) { }
        assertEquals(backup, VpnBackup.exportSetup(context));
        settings.forgetVpn(alternate);
        assertEquals(VpnPreferences.defaults(), settings.read());
        VpnPlaybackProtection.removeUserProfile(2);
        assertEquals(shared, settings.read());
        VpnBackup.restoreSetup(context, backup);
        VpnBackup.restoreSetup(context, oldBackup);
        assertEquals(shared, settings.read());
        assertEquals(oldBackup, VpnBackup.exportSetup(context));
    }

    @Test public void restorePausesRequestsWithoutDowngradingSavedProtection() throws Exception {
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        settings.saveMode(ProtectionMode.HTTP_ONLY);
        VpnPlaybackProtection.prepare(context, "https://example.invalid/video").get(3, java.util.concurrent.TimeUnit.SECONDS);
        VpnPlaybackProtection.beginProfileRestore(context);
        try {
            try {
                VpnPlaybackProtection.prepare(context, "https://example.invalid/video").get(3, java.util.concurrent.TimeUnit.SECONDS);
                fail("Playback escaped the profile restore barrier");
            } catch (java.util.concurrent.ExecutionException expected) { }
            try {
                VpnPlaybackProtection.proxyForNativePlayer(context).get(3, java.util.concurrent.TimeUnit.SECONDS);
                fail("Native playback escaped the profile restore barrier");
            } catch (java.util.concurrent.ExecutionException expected) { }
            assertEquals(ProtectionMode.HTTP_ONLY, settings.read().getMode());
        } finally { VpnPlaybackProtection.endProfileRestore(context); }
        VpnPlaybackProtection.prepare(context, "https://example.invalid/video").get(3, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test public void legacyBackupMigratesOnlyWhenNativeProfilesAreAbsent() throws Exception {
        AndroidProfileStore store = new AndroidProfileStore(context);
        try (ProfileImport.Candidate candidate = ProfileImport.read(new ByteArrayInputStream(CONFIG.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            store.save(ID, "Legacy fixture", candidate);
        }
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        settings.save(new VpnPreferences(ProtectionMode.HTTP_ONLY, ID));
        String legacy = VpnBackup.exportSetup(context);
        store.delete(ID);
        settings.save(VpnPreferences.defaults());
        assertEquals(legacy, VpnBackup.prepareExport(legacy));
        assertEquals(1, store.list().size());
        settings.saveMode(ProtectionMode.HTTP_HTTPS);
        String current = VpnBackup.exportSetup(context);
        assertEquals(current, VpnBackup.prepareExport(legacy));
        assertEquals(ProtectionMode.HTTP_HTTPS, settings.read().getMode());
    }

    @Test public void realKeystoreEncryptedStorageReopensAndPreservesCorruption() throws Exception {
        AndroidProfileStore store = new AndroidProfileStore(context);
        try (ProfileImport.Candidate candidate = ProfileImport.read(
                new ByteArrayInputStream(CONFIG.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            store.save("Device fixture", candidate);
        }
        List<AndroidProfileStore.Entry> entries = new AndroidProfileStore(context).list();
        assertEquals(1, entries.size());
        AndroidProfileStore.Entry entry = entries.get(0);
        assertTrue(entry.readable);
        assertEquals("Device fixture", entry.name);
        try (ProfileEnvelope.Opened opened = new AndroidProfileStore(context).open(entry.id)) {
            assertEquals(ProfileImport.Protocol.WIREGUARD, opened.getProtocol());
            assertArrayEquals(CONFIG.toCharArray(), opened.copyContents());
        }
        File encrypted = new File(context.getNoBackupFilesDir(), "vpn-profiles/" + entry.id + ".vpn");
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(encrypted);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            bytes = output.toByteArray();
        }
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("Device fixture"));
        assertFalse(raw.contains("PrivateKey"));
        bytes[bytes.length - 1] ^= 1;
        try (FileOutputStream output = new FileOutputStream(encrypted)) { output.write(bytes); }
        assertFalse(store.list().get(0).readable);
        assertTrue(encrypted.isFile());
        store.delete(entry.id);
        assertTrue(store.list().isEmpty());
    }

    @Test public void realAtomicMigrationReplacementAndSelectionReset() throws Exception {
        write("vpn-scope-v1", "HTTP_ONLY".getBytes(StandardCharsets.US_ASCII));
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        assertEquals(ProtectionMode.HTTP_ONLY, settings.read().getMode());
        settings.selectProfile(ID);
        AndroidVpnSettings reopened = new AndroidVpnSettings(context);
        assertEquals(new VpnPreferences(ProtectionMode.HTTP_ONLY, ID), reopened.read());
        reopened.saveMode(ProtectionMode.APP_WIDE);
        assertEquals(new VpnPreferences(ProtectionMode.APP_WIDE, ID), settings.read());
        reopened.selectProfile(null);
        assertEquals(VpnPreferences.defaults(), new AndroidVpnSettings(context).read());
        write("vpn-settings-v2", "invalid-private-value".getBytes(StandardCharsets.US_ASCII));
        try { settings.read(); fail("Corrupt settings accepted"); }
        catch (IOException expected) { assertFalse(expected.getMessage().contains("invalid-private-value")); }
    }

    /** Optional live-server test. Inputs stay in this test application's private files directory. */
    @Test public void openVpnAuthenticatedPlaybackRange() throws Exception {
        String url = InstrumentationRegistry.getArguments().getString("openvpnRangeUrl");
        org.junit.Assume.assumeTrue("Supply a live OpenVPN test endpoint", url != null);
        ProtectionMode mode = ProtectionMode.valueOf(InstrumentationRegistry.getArguments().getString("openvpnScope","HTTP_ONLY"));
        AndroidProfileStore store = new AndroidProfileStore(context);
        try (java.io.FileInputStream in = new java.io.FileInputStream(new File(context.getFilesDir(),"acceptance.ovpn"));
             ProfileImport.Candidate candidate = ProfileImport.read(in, ProfileImport.Protocol.OPENVPN)) {
            store.save(ID,"Integration profile",candidate);
            if (OpenVpnSession.evaluate(candidate.copyContents()) == 2) {
                byte[] revision = store.revision(ID);
                VpnBackup.updateCredentials(context, ID, revision, "fixture-user", "fixture-password");
                try {
                    store.updateCredentials(ID,revision,"stale","edit");
                    fail("A stale credential edit overwrote the latest version");
                } catch (IOException expected) { }
            }
        }
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        settings.save(new VpnPreferences(mode,ID));
        String backup = VpnBackup.exportSetup(context);
        store.delete(ID);
        settings.save(VpnPreferences.defaults());
        VpnBackup.restoreSetup(context, backup);
        VpnConnectionService.settingsChanged(context);
        try {
            java.util.concurrent.CompletableFuture<Void> gate = VpnPlaybackProtection.prepare(context,url);
            if ("true".equals(InstrumentationRegistry.getArguments().getString("openvpnReject"))) {
                try { gate.get(45,java.util.concurrent.TimeUnit.SECONDS); fail("Invalid peer was accepted"); }
                catch (java.util.concurrent.ExecutionException rejected) { assertTrue(rejected.getCause() instanceof IOException); }
                assertFalse(VpnConnectionService.verifiedFor(ID,mode));
                return;
            }
            gate.get(45,java.util.concurrent.TimeUnit.SECONDS);
            android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
            assertEquals(mode == ProtectionMode.APP_WIDE, cm.getNetworkCapabilities(cm.getActiveNetwork())
                    .hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN));
            java.net.HttpURLConnection connection = VpnHttpClient.openConnection(new java.net.URL(url));
            connection.setRequestProperty("Range","bytes=31-158");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            try {
                assertEquals(206, connection.getResponseCode());
                try (java.io.InputStream body = connection.getInputStream()) {
                    java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
                    for(int n=31;n<=158;n++) {
                        int value=body.read(); assertTrue(value>=0);
                        if (InstrumentationRegistry.getArguments().getString("openvpnReference") == null) assertEquals(n,value);
                        sha.update((byte)value);
                    }
                    assertEquals(-1, body.read());
                    String reference = InstrumentationRegistry.getArguments().getString("openvpnReference");
                    if (reference != null) assertEquals(reference, android.util.Base64.encodeToString(sha.digest(), android.util.Base64.NO_WRAP));
                }
            } finally { connection.disconnect(); }
            assertTrue(VpnConnectionService.verifiedFor(ID,mode));
            assertTrue(VpnConnectionService.snapshot().received > 0);
        } finally {
            settings.save(VpnPreferences.defaults());
            VpnConnectionService.settingsChanged(context);
            VpnConnectionService.disconnect(context);
        }
    }

    private void write(String name, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(new File(context.getNoBackupFilesDir(), name))) {
            output.write(bytes);
        }
    }

    private void clean() {
        for (String name : new String[] {"vpn-scope-v1", "vpn-settings-v2"}) {
            for (String suffix : new String[] {"", ".bak", ".new"}) {
                File file = new File(context.getNoBackupFilesDir(), name + suffix);
                if (file.exists()) assertTrue(file.delete());
            }
        }
        File directory = new File(context.getNoBackupFilesDir(), "vpn-profiles");
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
    }
}
