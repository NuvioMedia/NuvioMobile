package com.nuvio.vpn.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned, authenticated profile storage. Android supplies a non-exportable Keystore key. */
public final class ProfileEnvelope {
    public static final int MAX_ENVELOPE_BYTES = ProfileImport.MAX_BYTES + 4096;
    private static final byte[] HEADER = {'N', 'V', 'P', 1};
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_LABEL_BYTES = 256;

    private ProfileEnvelope() {}

    public static byte[] seal(String id, String label, ProfileImport.Candidate candidate, SecretKey key)
            throws GeneralSecurityException, IOException {
        validateId(id);
        if (label == null || label.trim().isEmpty()) throw new IOException("A profile name is required.");
        byte[] labelBytes = label.trim().getBytes(StandardCharsets.UTF_8);
        if (labelBytes.length > MAX_LABEL_BYTES) throw new IOException("Profile name is too long.");
        char[] contents = candidate.copyContents();
        byte[] config = null;
        byte[] plaintext = null;
        WipingBuffer payload = new WipingBuffer();
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(contents));
            config = new byte[encoded.remaining()];
            encoded.get(config);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
            if (config.length == 0 || config.length > ProfileImport.MAX_BYTES) throw new IOException("Invalid profile size.");
            DataOutputStream data = new DataOutputStream(payload);
            data.writeByte(candidate.getProtocol() == ProfileImport.Protocol.OPENVPN ? 1 : 2);
            data.writeInt(labelBytes.length);
            data.write(labelBytes);
            data.writeInt(config.length);
            data.write(config);
            data.flush();
            plaintext = payload.toByteArray();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Let the Keystore/provider generate the nonce: never request an IV for encryption.
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) throw new GeneralSecurityException("Unsupported encryption provider.");
            authenticate(cipher, id);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] result = new byte[HEADER.length + IV_BYTES + encrypted.length];
            System.arraycopy(HEADER, 0, result, 0, HEADER.length);
            System.arraycopy(iv, 0, result, HEADER.length, IV_BYTES);
            System.arraycopy(encrypted, 0, result, HEADER.length + IV_BYTES, encrypted.length);
            return result;
        } finally {
            Arrays.fill(contents, '\0');
            Arrays.fill(labelBytes, (byte) 0);
            if (config != null) Arrays.fill(config, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            payload.wipe();
        }
    }

    public static Opened open(String id, byte[] envelope, SecretKey key)
            throws GeneralSecurityException, IOException {
        validateId(id);
        if (envelope == null || envelope.length < HEADER.length + IV_BYTES + TAG_BITS / 8
                || envelope.length > MAX_ENVELOPE_BYTES) throw new IOException("Invalid encrypted profile size.");
        for (int i = 0; i < HEADER.length; i++) {
            if (envelope[i] != HEADER[i]) throw new IOException("Unsupported encrypted profile format.");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, envelope, HEADER.length, IV_BYTES));
        authenticate(cipher, id);
        byte[] plaintext = cipher.doFinal(envelope, HEADER.length + IV_BYTES,
                envelope.length - HEADER.length - IV_BYTES);
        byte[] config = null;
        byte[] label = null;
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext));
            int type = input.readUnsignedByte();
            if (type != 1 && type != 2) throw new IOException("Unsupported profile protocol.");
            int labelLength = input.readInt();
            if (labelLength < 1 || labelLength > MAX_LABEL_BYTES) throw new IOException("Invalid profile name size.");
            label = new byte[labelLength];
            input.readFully(label);
            int configLength = input.readInt();
            if (configLength < 1 || configLength > ProfileImport.MAX_BYTES || input.available() != configLength) {
                throw new IOException("Invalid profile content size.");
            }
            config = new byte[configLength];
            input.readFully(config);
            char[] contents = decode(config);
            try {
                String name = new String(decode(label));
                return new Opened(name, type == 1 ? ProfileImport.Protocol.OPENVPN : ProfileImport.Protocol.WIREGUARD, contents);
            } catch (IOException | RuntimeException e) {
                Arrays.fill(contents, '\0');
                throw e;
            }
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (config != null) Arrays.fill(config, (byte) 0);
            if (label != null) Arrays.fill(label, (byte) 0);
        }
    }

    private static char[] decode(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        char[] result = new char[decoded.remaining()];
        decoded.get(result);
        if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        return result;
    }

    private static void authenticate(Cipher cipher, String id) {
        cipher.updateAAD(HEADER);
        cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));
    }

    public static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid profile identifier.");
        }
    }

    public static final class Opened implements AutoCloseable {
        private final String label;
        private final ProfileImport.Protocol protocol;
        private char[] contents;

        private Opened(String label, ProfileImport.Protocol protocol, char[] contents) {
            this.label = label;
            this.protocol = protocol;
            this.contents = contents;
        }

        public String getLabel() { return label; }
        public ProfileImport.Protocol getProtocol() { return protocol; }
        public synchronized char[] copyContents() {
            if (contents == null) throw new IllegalStateException("VPN profile has been closed.");
            return contents.clone();
        }
        @Override public synchronized void close() {
            if (contents != null) { Arrays.fill(contents, '\0'); contents = null; }
        }
        @Override public String toString() { return "VPN profile (" + protocol + ", contents redacted)"; }
    }

    private static final class WipingBuffer extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
