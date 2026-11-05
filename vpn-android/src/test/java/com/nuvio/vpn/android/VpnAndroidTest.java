package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Build;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Simulated Android checks: not real Keystore, consent, tunnel, or leak validation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public final class VpnAndroidTest {
    private static final String ID = "a4a6dfda-f490-4728-a7dd-1318e7d385a0";
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        for (String name : new String[] {"vpn-scope-v1", "vpn-settings-v2"}) {
            for (String suffix : new String[] {"", ".bak", ".new"}) {
                File file = new File(context.getNoBackupFilesDir(), name + suffix);
                if (file.exists()) assertTrue(file.delete());
            }
        }
    }

    @Test public void realWireGuardParserAndHostScope() throws Exception {
        WireGuardProfileChecks.main(new String[0]);
    }

    @Test public void migratesScopeAndRetainsSelectionAcrossReopen() throws Exception {
        write("vpn-scope-v1", "HTTP_ONLY");
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        assertEquals(ProtectionMode.HTTP_ONLY, settings.read().getMode());
        settings.selectProfile(ID);
        AndroidVpnSettings reopened = new AndroidVpnSettings(context);
        assertEquals(new VpnPreferences(ProtectionMode.HTTP_ONLY, ID), reopened.read());
    }

    @Test public void scopeUpdatesRetainSelectionAndClearingResetsOff() throws Exception {
        requireAtomicReplacement();
        write("vpn-scope-v1", "HTTP_ONLY");
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        settings.selectProfile(ID);
        AndroidVpnSettings reopened = new AndroidVpnSettings(context);
        reopened.saveMode(ProtectionMode.APP_WIDE);
        assertEquals(new VpnPreferences(ProtectionMode.APP_WIDE, ID), settings.read());
        reopened.selectProfile(null);
        assertEquals(VpnPreferences.defaults(), settings.read());
        // A retained legacy scope must not override the newer snapshot after clearing selection.
        assertEquals(VpnPreferences.defaults(), new AndroidVpnSettings(context).read());
    }

    @Test public void corruptNewSettingsNeverFallBackToLegacyScope() throws Exception {
        write("vpn-scope-v1", "APP_WIDE");
        write("vpn-settings-v2", "invalid-private-value");
        try {
            new AndroidVpnSettings(context).read();
            fail("Corrupt settings accepted");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("invalid-private-value"));
        }
    }

    @Test public void recoversLegacyAtomicBackup() throws Exception {
        write("vpn-scope-v1.bak", "APP_WIDE");
        assertEquals(new VpnPreferences(ProtectionMode.APP_WIDE, null),
                new AndroidVpnSettings(context).read());
    }

    @Test public void selectedPreparationRequiresExplicitAppScopeAndProfile() throws Exception {
        try {
            WireGuardProfiles.prepareSelected(context, ProtectionMode.APP_WIDE);
            fail("Missing selection accepted");
        } catch (IOException expected) {
            assertEquals("Select a VPN profile and protection scope first.", expected.getMessage());
        }
        write("vpn-settings-v2", new VpnPreferences(ProtectionMode.HTTP_ONLY, ID).encode());
        try {
            WireGuardProfiles.prepareSelected(context, ProtectionMode.APP_WIDE);
            fail("HTTP-only mode accepted as app-wide");
        } catch (IOException expected) {
            assertEquals("Select a VPN profile and protection scope first.", expected.getMessage());
        }
    }

    private void write(String name, String contents) throws IOException {
        write(name, contents.getBytes(StandardCharsets.US_ASCII));
    }

    private void write(String name, byte[] contents) throws IOException {
        try (FileOutputStream output = new FileOutputStream(new File(context.getNoBackupFilesDir(), name))) {
            output.write(contents);
        }
    }

    private static void requireAtomicReplacement() {
        // Modern framework AtomicFile uses File.renameTo to replace an existing file.
        // Robolectric executes that call on the host; Windows lacks Android's POSIX replacement
        // semantics. Keep these cases active on Linux/macOS and report them as skipped on Windows.
        Assume.assumeFalse("Modern AtomicFile replacement requires a POSIX test host",
                Build.VERSION.SDK_INT >= 29 && System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT).contains("windows"));
    }
}
