package com.nuvio.vpn.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Portable account-backup payload. Android Keystore ciphertext is never sent to another device. */
public final class VpnBackup {
    public static final String KEY = "vpn_profiles_v1";
    private static final Object LOCK = new Object();
    private VpnBackup() { }
    public static String exportSetup() throws Exception { return exportSetup(VpnPlaybackProtection.application()); }
    public static void restoreSetup(String json) throws Exception { restoreSetup(VpnPlaybackProtection.application(), json); }
    /** Adopt an opaque snapshot forwarded by an older client before native storage existed. */
    public static String prepareExport(String legacySnapshot) throws Exception {
        return prepareExport(VpnPlaybackProtection.application(), legacySnapshot);
    }
    public static String prepareExport(Context context, String legacySnapshot) throws Exception {
        synchronized (LOCK) {
            String current = exportSetup(context);
            if (legacySnapshot != null && new JSONObject(current).getJSONArray("profiles").length() == 0) {
                restoreChecked(context, legacySnapshot, true);
                current = exportSetup(context);
            }
            return current;
        }
    }

    public static String exportSetup(Context context) throws Exception {
        synchronized (AndroidProfileStore.LOCK) {
            AndroidProfileStore store = new AndroidProfileStore(context);
            JSONArray profiles = new JSONArray();
            ArrayList<AndroidProfileStore.Entry> entries = new ArrayList<>(store.list());
            entries.sort((a, b) -> a.id.compareTo(b.id));
            for (AndroidProfileStore.Entry entry : entries) {
                // Fail the backup instead of silently replacing an unreadable profile remotely.
                try (ProfileEnvelope.Opened opened = store.open(entry.id)) {
                    char[] content = opened.copyContents();
                    try { profiles.put(new JSONObject().put("id", entry.id).put("name", opened.getLabel())
                            .put("protocol", opened.getProtocol().name()).put("configuration", new String(content))); }
                    finally { Arrays.fill(content, '\0'); }
                }
            }
            HashSet<String> ids = new HashSet<>();
            for (AndroidProfileStore.Entry entry : entries) ids.add(entry.id);
            ProfileSelections settings = new AndroidVpnSettings(context).snapshot();
            settings.checkProfiles(ids);
            return settings.writeTo(new JSONObject().put("version", 1).put("profiles", profiles)).toString();
        }
    }

    public static void restoreSetup(Context context, String encoded) throws Exception {
        restoreChecked(context, encoded, false);
    }
    private static void restoreChecked(Context context, String encoded, boolean onlyIfEmpty) throws Exception {
        try { restoreValidated(context, encoded, onlyIfEmpty); }
        catch (Exception invalid) { throw new IOException("VPN backup could not be restored. Check its version, profiles and selected scope."); }
    }
    private static void restoreValidated(Context context, String encoded, boolean onlyIfEmpty) throws Exception {
        synchronized (LOCK) {
            if (encoded == null || encoded.length() > 8 * 1024 * 1024) throw new IOException("Invalid VPN backup size.");
            JSONObject payload = new JSONObject(encoded);
            if (payload.getInt("version") != 1) throw new IOException("Unsupported VPN backup version.");
            JSONArray profiles = payload.getJSONArray("profiles");
            if (profiles.length() > 32) throw new IOException("VPN profile limit reached.");
            ProfileSelections selections = ProfileSelections.parse(payload);
            HashSet<String> ids = new HashSet<>();
            ArrayList<ProfileImport.Candidate> candidates = new ArrayList<>();
            try {
                for (int i = 0; i < profiles.length(); i++) {
                    JSONObject p = profiles.getJSONObject(i);
                    String id = p.getString("id");
                    ProfileEnvelope.validateId(id);
                    if (!ids.add(id)) throw new IOException("Duplicate VPN profile.");
                    String label = p.getString("name");
                    if (label.trim().isEmpty() || label.getBytes(StandardCharsets.UTF_8).length > 256) throw new IOException("Invalid VPN profile name.");
                    byte[] raw = p.getString("configuration").getBytes(StandardCharsets.UTF_8);
                    try {
                        ProfileImport.Candidate candidate = ProfileImport.read(new ByteArrayInputStream(raw), ProfileImport.Protocol.valueOf(p.getString("protocol")));
                        candidates.add(candidate);
                        if (candidate.getProtocol() == ProfileImport.Protocol.WIREGUARD) WireGuardProfiles.parse(candidate);
                    }
                    finally { Arrays.fill(raw, (byte) 0); }
                }
                selections.checkProfiles(ids);
                String current = null;
                try { current = exportSetup(context); }
                catch (Exception unreadable) { /* A validated backup can repair unreadable local state. */ }
                if (payload.toString().equals(current)) return;
                AndroidProfileStore store = new AndroidProfileStore(context);
                AndroidVpnSettings settings = new AndroidVpnSettings(context);
                ArrayList<AndroidProfileStore.Restored> restored = new ArrayList<>();
                for (int i = 0; i < profiles.length(); i++) {
                    JSONObject p = profiles.getJSONObject(i);
                    restored.add(new AndroidProfileStore.Restored(p.getString("id"), p.getString("name"), candidates.get(i)));
                }
                // Pause requests and invalidate even a same-ID connection whose keys changed.
                // Keep saved protection intent enabled throughout; a failed restore must not
                // temporarily turn protected playback into a direct connection.
                try {
                    VpnPlaybackProtection.beginProfileRestore(context);
                    store.replace(restored, onlyIfEmpty, settings, selections);
                } finally { VpnPlaybackProtection.endProfileRestore(context); }
            } finally { for (ProfileImport.Candidate candidate : candidates) candidate.close(); }
        }
    }

    static void updateCredentials(Context context, String id, byte[] expected, String username, String password) throws Exception {
        synchronized (LOCK) {
            try {
                VpnPlaybackProtection.beginProfileRestore(context);
                new AndroidProfileStore(context).updateCredentials(id, expected, username, password);
            } finally { VpnPlaybackProtection.endProfileRestore(context); }
        }
    }

    /** Protect only the VPN entry in local sync comparison caches, never the portable remote payload. */
    public static String sealCache(String json) throws Exception {
        JSONObject data = new JSONObject(json);
        if (!data.has(KEY)) return json;
        byte[] plain = data.getJSONObject(KEY).toString().getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, cacheKey());
            cipher.updateAAD(KEY.getBytes(StandardCharsets.US_ASCII));
            data.put(KEY, new JSONObject().put("localEncrypted", Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP))
                    .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)));
            return data.toString();
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    public static String openCache(String json) throws Exception {
        JSONObject data = new JSONObject(json);
        JSONObject entry = data.optJSONObject(KEY);
        if (entry == null || !entry.has("localEncrypted")) return json;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, cacheKey(), new GCMParameterSpec(128, Base64.decode(entry.getString("iv"), Base64.NO_WRAP)));
        cipher.updateAAD(KEY.getBytes(StandardCharsets.US_ASCII));
        byte[] plain = cipher.doFinal(Base64.decode(entry.getString("localEncrypted"), Base64.NO_WRAP));
        try { data.put(KEY, new JSONObject(new String(plain, StandardCharsets.UTF_8))); return data.toString(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private static synchronized SecretKey cacheKey() throws Exception {
        String alias = "nuvio.vpn.backup-cache.v1";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey key = (SecretKey) store.getKey(alias, null);
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
