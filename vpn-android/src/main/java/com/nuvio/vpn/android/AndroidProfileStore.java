package com.nuvio.vpn.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Encrypted device storage; portable account backup is mediated by VpnBackup. */
final class AndroidProfileStore {
    private static final String KEY_ALIAS = "nuvio.vpn.profiles.v1";
    private static final int MAX_PROFILES = 32;
    static final Object LOCK = new Object();
    private final File directory;

    AndroidProfileStore(Context context) {
        directory = new File(context.getNoBackupFilesDir(), "vpn-profiles");
    }

    void save(String name, ProfileImport.Candidate candidate) throws IOException, GeneralSecurityException {
        save(UUID.randomUUID().toString(), name, candidate);
    }

    void save(String id, String name, ProfileImport.Candidate candidate) throws IOException, GeneralSecurityException {
        synchronized (LOCK) {
            ensureDirectory();
            if (!file(id).exists() && profileFiles().length >= MAX_PROFILES) throw new IOException("Profile limit reached.");
            byte[] encrypted = ProfileEnvelope.seal(id, name, candidate, key(true));
            try { writeEncrypted(id, encrypted); }
            finally { Arrays.fill(encrypted, (byte) 0); }
            VpnPlaybackProtection.backupChanged();
        }
    }

    static final class Restored {
        final String id, name;
        final ProfileImport.Candidate candidate;
        Restored(String id, String name, ProfileImport.Candidate candidate) { this.id = id; this.name = name; this.candidate = candidate; }
    }

    /** Apply the agreed backup snapshot exactly, so deletions are not resurrected on the next sync. */
    void replace(List<Restored> profiles, boolean onlyIfEmpty, AndroidVpnSettings settings, ProfileSelections selections) throws IOException, GeneralSecurityException {
        synchronized (LOCK) {
            ensureDirectory();
            if (onlyIfEmpty && profileFiles().length != 0) return;
            if (profiles.size() > MAX_PROFILES) throw new IOException("Profile limit reached.");
            java.util.Map<String, byte[]> previous = new java.util.LinkedHashMap<>();
            java.util.Map<String, byte[]> prepared = new java.util.LinkedHashMap<>();
            try {
                for (File profile : profileFiles()) {
                    String id = profile.getName().substring(0, 36);
                    previous.put(id, readBounded(new AtomicFile(profile)));
                }
                for (Restored profile : profiles) {
                    if (prepared.containsKey(profile.id)) throw new IOException("Duplicate restored profile.");
                    prepared.put(profile.id, ProfileEnvelope.seal(profile.id, profile.name, profile.candidate, key(true)));
                }
                try {
                    for (java.util.Map.Entry<String, byte[]> profile : prepared.entrySet()) writeEncrypted(profile.getKey(), profile.getValue());
                    for (String id : previous.keySet()) if (!prepared.containsKey(id)) delete(id);
                    settings.replace(selections);
                } catch (IOException | RuntimeException failed) {
                    // Playback is paused throughout this transaction. Roll back an ordinary IO failure;
                    // after process death the next restore can safely finish the exact snapshot.
                    try {
                        for (java.util.Map.Entry<String, byte[]> profile : previous.entrySet()) writeEncrypted(profile.getKey(), profile.getValue());
                        for (String id : prepared.keySet()) if (!previous.containsKey(id)) delete(id);
                    } catch (IOException | RuntimeException rollbackFailed) { /* Invalid profiles remain blocked by playback preparation. */ }
                    throw failed;
                }
                VpnPlaybackProtection.backupChanged();
            } finally {
                for (byte[] value : previous.values()) Arrays.fill(value, (byte) 0);
                for (byte[] value : prepared.values()) Arrays.fill(value, (byte) 0);
            }
        }
    }

    byte[] revision(String id) throws Exception {
        synchronized (LOCK) {
            byte[] sealed = readBounded(new AtomicFile(file(id)));
            try { return MessageDigest.getInstance("SHA-256").digest(sealed); }
            finally { Arrays.fill(sealed, (byte)0); }
        }
    }

    void updateCredentials(String id, byte[] expected, String username, String password) throws Exception {
        synchronized (LOCK) {
            if (!MessageDigest.isEqual(expected, revision(id))) throw new IOException("Profile changed.");
            try (ProfileEnvelope.Opened opened = open(id);
                 ProfileImport.Candidate candidate = OpenVpnCredentials.replace(opened, username, password)) {
                OpenVpnSession.requireReady(candidate.copyContents());
                save(id, opened.getLabel(), candidate);
            }
        }
    }

    private void writeEncrypted(String id, byte[] encrypted) throws IOException {
        AtomicFile target = new AtomicFile(file(id));
        FileOutputStream output = null;
        try {
            output = target.startWrite(); output.write(encrypted); target.finishWrite(output); output = null;
            byte[] stored = readBounded(target);
            try { if (!MessageDigest.isEqual(stored, encrypted)) throw new IOException("Profile write did not complete."); }
            finally { Arrays.fill(stored, (byte) 0); }
        } catch (IOException | RuntimeException failed) {
            if (output != null) target.failWrite(output);
            throw failed;
        }
    }

    List<Entry> list() throws IOException {
        synchronized (LOCK) {
            ensureDirectory();
            ArrayList<Entry> result = new ArrayList<>();
            for (File file : profileFiles()) {
                String id = file.getName().substring(0, 36);
                byte[] encrypted = null;
                try {
                    encrypted = readBounded(new AtomicFile(file));
                    try (ProfileEnvelope.Opened profile = ProfileEnvelope.open(id, encrypted, key(false))) {
                        result.add(new Entry(id, profile.getLabel(), profile.getProtocol(), true,
                                profile.getProtocol() == ProfileImport.Protocol.OPENVPN
                                ? OpenVpnCredentials.inspect(profile.copyContents()) : OpenVpnCredentials.Login.NONE));
                    }
                } catch (IOException | GeneralSecurityException e) {
                    // Preserve damaged/key-invalidated files; let the user explicitly remove them.
                    result.add(new Entry(id, "", null, false, OpenVpnCredentials.Login.NONE));
                } finally { if (encrypted != null) Arrays.fill(encrypted, (byte) 0); }
            }
            result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return result;
        }
    }

    /** Caller owns the decrypted buffer and must close it; never cache or log the returned profile. */
    ProfileEnvelope.Opened open(String id) throws IOException, GeneralSecurityException {
        synchronized (LOCK) {
            byte[] encrypted = readBounded(new AtomicFile(file(id)));
            try { return ProfileEnvelope.open(id, encrypted, key(false)); }
            finally { Arrays.fill(encrypted, (byte) 0); }
        }
    }

    void delete(String id) throws IOException {
        synchronized (LOCK) {
            File file = file(id);
            new AtomicFile(file).delete();
            if (file.exists() || new File(file.getPath() + ".bak").exists()) {
                throw new IOException("Could not remove VPN profile.");
            }
            VpnPlaybackProtection.backupChanged();
        }
    }

    private File file(String id) {
        ProfileEnvelope.validateId(id);
        return new File(directory, id + ".vpn");
    }

    private File[] profileFiles() throws IOException {
        File[] files = directory.listFiles((dir, name) -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.vpn(\\.bak)?"));
        if (files == null) throw new IOException("Could not list VPN profiles.");
        LinkedHashSet<File> bases = new LinkedHashSet<>();
        for (File file : files) bases.add(file(file.getName().substring(0, 36)));
        return bases.toArray(new File[0]);
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not open VPN storage.");
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key != null) return key;
        if (!create) throw new GeneralSecurityException("VPN profile key is unavailable.");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static byte[] readBounded(AtomicFile file) throws IOException {
        try (InputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() > ProfileEnvelope.MAX_ENVELOPE_BYTES - count) throw new IOException("Profile is too large.");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } finally { Arrays.fill(buffer, (byte) 0); }
        }
    }

    static final class Entry {
        final String id;
        final String name;
        final ProfileImport.Protocol protocol;
        final boolean readable;
        final OpenVpnCredentials.Login login;
        Entry(String id, String name, ProfileImport.Protocol protocol, boolean readable, OpenVpnCredentials.Login login) {
            this.login = login;
            this.id = id; this.name = name; this.protocol = protocol; this.readable = readable;
        }
    }
}
