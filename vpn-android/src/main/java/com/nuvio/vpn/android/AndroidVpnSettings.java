package com.nuvio.vpn.android;

import android.content.Context;
import android.util.AtomicFile;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Scope and selected UUID share one atomic, backup-excluded snapshot; no labels or secrets. */
final class AndroidVpnSettings {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    private final AtomicFile legacy;

    AndroidVpnSettings(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "vpn-settings-v2"));
        legacy = new AtomicFile(new File(context.getNoBackupFilesDir(), "vpn-scope-v1"));
    }

    VpnPreferences read() throws IOException {
        synchronized (LOCK) {
            AtomicFile source = exists(file) ? file : legacy;
            if (!exists(source)) return VpnPreferences.defaults();
            try (InputStream input = source.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                int next;
                while ((next = input.read()) != -1) {
                    if (bytes.size() == VpnPreferences.MAX_BYTES) throw new IOException("Invalid VPN settings.");
                    bytes.write(next);
                }
                return VpnPreferences.decode(bytes.toByteArray());
            }
        }
    }

    void saveMode(ProtectionMode mode) throws IOException {
        synchronized (LOCK) { save(read().withMode(mode)); }
    }

    void selectProfile(String id) throws IOException {
        synchronized (LOCK) { save(read().withSelectedProfile(id)); }
    }

    void save(VpnPreferences value) throws IOException {
        synchronized (LOCK) { saveLocked(value); }
    }

    private void saveLocked(VpnPreferences value) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(value.encode());
            file.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            file.failWrite(output);
            throw e;
        }
        // A completed transaction must not be rolled back after finishWrite closed the stream.
        if (!file.getBaseFile().isFile() || !read().equals(value)) throw new IOException("VPN settings write did not complete.");
    }

    private static boolean exists(AtomicFile value) {
        File base = value.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists();
    }
}
