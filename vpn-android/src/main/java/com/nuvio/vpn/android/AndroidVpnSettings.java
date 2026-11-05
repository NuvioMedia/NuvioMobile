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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/** Scope and selected UUID share one atomic, backup-excluded snapshot; no labels or secrets. */
final class AndroidVpnSettings {
    static final Object LOCK = new Object();
    private final AtomicFile file;
    private final AtomicFile legacy;
    private final Integer editingUser;

    AndroidVpnSettings(Context context) { this(context, null); }

    AndroidVpnSettings(Context context, Integer editingUser) {
        this.editingUser = editingUser;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "vpn-settings-v2"));
        legacy = new AtomicFile(new File(context.getNoBackupFilesDir(), "vpn-scope-v1"));
    }

    VpnPreferences read() throws IOException {
        synchronized (LOCK) { return snapshot().forUser(VpnPlaybackProtection.activeUserProfile()); }
    }

    ProfileSelections snapshot() throws IOException {
        synchronized (LOCK) {
            if (editingUser != null && editingUser != VpnPlaybackProtection.activeUserProfile())
                throw new IOException("The active Nuvio profile changed. Reopen VPN settings.");
            AtomicFile source = exists(file) ? file : legacy;
            if (!exists(source)) return new ProfileSelections(VpnPreferences.defaults());
            byte[] data = readBytes(source);
            if (data.length == 0 || data[0] != '{') return new ProfileSelections(VpnPreferences.decode(data));
            try { return ProfileSelections.parse(new JSONObject(new String(data, StandardCharsets.UTF_8))); }
            catch (JSONException e) { throw new IOException("Invalid VPN settings."); }
        }
    }

    private static byte[] readBytes(AtomicFile source) throws IOException {
        try (InputStream input = source.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (bytes.size() + n > 8192) throw new IOException("Invalid VPN settings.");
                bytes.write(buffer, 0, n);
            }
            return bytes.toByteArray();
        }
    }

    void saveMode(ProtectionMode mode) throws IOException {
        synchronized (LOCK) { save(read().withMode(mode)); }
    }

    void selectProfile(String id) throws IOException {
        synchronized (LOCK) { save(read().withSelectedProfile(id)); }
    }

    void save(VpnPreferences value) throws IOException {
        synchronized (LOCK) {
            ProfileSelections state = snapshot();
            int user = VpnPlaybackProtection.activeUserProfile();
            if (state.overrides.containsKey(user)) state.overrides.put(user, value);
            else state.shared = value;
            replace(state);
        }
    }

    void setShared(boolean shared) throws IOException {
        synchronized (LOCK) {
            int user = VpnPlaybackProtection.activeUserProfile();
            if (user <= 0) throw new IOException("Select a Nuvio profile first.");
            ProfileSelections state = snapshot();
            if (shared) state.overrides.remove(user);
            else state.overrides.putIfAbsent(user, state.shared);
            replace(state);
        }
    }

    boolean removeUser(int user) throws IOException {
        synchronized (LOCK) {
            ProfileSelections state = snapshot();
            if (state.overrides.remove(user) != null) {
                replace(state);
                return true;
            }
            return false;
        }
    }

    void forgetVpn(String id) throws IOException {
        synchronized (LOCK) {
            ProfileSelections state = snapshot();
            state.forgetVpn(id);
            replace(state);
        }
    }

    void replace(ProfileSelections state) throws IOException {
        synchronized (LOCK) {
            byte[] bytes;
            try { bytes = state.overrides.isEmpty() ? state.shared.encode()
                    : state.writeTo(new JSONObject()).toString().getBytes(StandardCharsets.UTF_8); }
            catch (JSONException e) { throw new IOException("Invalid VPN settings."); }
            FileOutputStream output = file.startWrite();
            try {
                output.write(bytes);
                file.finishWrite(output);
            } catch (IOException | RuntimeException e) {
                file.failWrite(output);
                throw e;
            }
            if (!Arrays.equals(readBytes(file), bytes)) throw new IOException("VPN settings write did not complete.");
        }
    }

    private static boolean exists(AtomicFile value) {
        File base = value.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists();
    }
}
