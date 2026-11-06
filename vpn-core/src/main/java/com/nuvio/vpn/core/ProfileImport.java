package com.nuvio.vpn.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Bounded import/preflight only. The selected VPN engine must validate before saving/connecting. */
public final class ProfileImport {
    public static final int MAX_BYTES = 1024 * 1024;
    public enum Protocol { OPENVPN, WIREGUARD }

    private ProfileImport() {}

    /** The caller owns and closes input (for example, a Storage Access Framework stream). */
    public static Candidate read(InputStream input, Protocol protocol) throws IOException {
        if (input == null || protocol == null) throw new IllegalArgumentException("Input and protocol are required.");
        byte[] buffer = new byte[4096];
        WipingBuffer bytes = new WipingBuffer();
        byte[] raw = null;
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new IOException("Could not read VPN configuration.");
                if (bytes.size() > MAX_BYTES - count) throw new IOException("VPN configuration exceeds 1 MiB.");
                bytes.write(buffer, 0, count);
            }
            raw = bytes.toByteArray();
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException e) {
                throw new IOException("VPN configuration must be UTF-8 text.");
            }
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.trim().isEmpty() || text.indexOf('\0') >= 0) {
                throw new IOException("VPN configuration is empty or contains binary data.");
            }
            preflight(text, protocol);
            return new Candidate(protocol, text.toCharArray());
        } finally {
            Arrays.fill(buffer, (byte) 0);
            bytes.wipe();
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    private static void preflight(String text, Protocol protocol) throws IOException {
        boolean interfaceSeen = false;
        boolean peerSeen = false;
        boolean clientSeen = false;
        boolean remoteSeen = false;
        String inlineBlock = null;
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (protocol == Protocol.WIREGUARD) {
                if (line.equalsIgnoreCase("[Interface]")) interfaceSeen = true;
                if (line.equalsIgnoreCase("[Peer]")) peerSeen = true;
                String key = line.split("=", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (key.equals("preup") || key.equals("postup") || key.equals("predown")
                        || key.equals("postdown") || key.equals("saveconfig") || key.equals("table")) {
                    throw new IOException("Desktop wg-quick hooks/options are not supported on Android.");
                }
                continue;
            }
            // Ignore certificate/key contents; protocol engines validate block syntax and values.
            if (inlineBlock != null) {
                if (line.equalsIgnoreCase("</" + inlineBlock + ">")) inlineBlock = null;
                continue;
            }
            if (line.startsWith("<") && line.endsWith(">")) {
                String tag = line.substring(1, line.length() - 1).toLowerCase(Locale.ROOT);
                if (!tag.matches("ca|cert|key|tls-auth|tls-crypt|tls-crypt-v2|auth-user-pass|pkcs12|extra-certs|dh")) {
                    throw new IOException("Unsupported OpenVPN block <" + safeName(tag) + ">.");
                }
                inlineBlock = tag;
                continue;
            }
            String[] fields = line.split("\\s+", 2);
            String option = fields[0].toLowerCase(Locale.ROOT);
            if (option.startsWith("--")) option = option.substring(2);
            String value = fields.length == 2 ? fields[1].trim() : "";
            if (option.equals("client")) clientSeen = true;
            if (option.equals("remote") && !value.isEmpty()) remoteSeen = true;
            // Imported files may not execute code, include files, or expose management sockets.
            if (option.matches("up|down|route-up|route-pre-down|ipchange|plugin|script-security|config|cd|chroot|daemon|log|log-append|writepid|status|auth-user-pass-verify|client-connect|client-disconnect|learn-address|tls-verify|iproute|tls-crypt-v2-verify|engine|providers")
                    || option.startsWith("management")
                    || option.startsWith("pkcs11")) {
                throw new IOException("OpenVPN option \"" + safeName(option) + "\" is not supported (scripts, files and management options can't be used).");
            }
            if (option.matches("ca|cert|key|tls-auth|tls-crypt|tls-crypt-v2|pkcs12|extra-certs|dh|crl-verify|askpass|auth-user-pass")
                    && !value.isEmpty() && !value.equals("[inline]")) {
                throw new IOException("Option \"" + safeName(option) + "\" points to a separate file. Put it inline in the profile instead.");
            }
        }
        if (protocol == Protocol.WIREGUARD && !(interfaceSeen && peerSeen)) {
            throw new IOException("Expected a WireGuard configuration with Interface and Peer sections.");
        }
        if (protocol == Protocol.OPENVPN && (!(clientSeen && remoteSeen) || inlineBlock != null)) {
            throw new IOException("Expected a self-contained OpenVPN client configuration with a remote server.");
        }
    }

    /** Option names are not secret, but keep what is shown short and plain. */
    private static String safeName(String name) {
        String clean = name.replaceAll("[^a-z0-9_-]", "?");
        return clean.length() > 30 ? clean.substring(0, 30) : clean;
    }

    /** Contains private keys. Never log, serialize, or treat this preflight result as validated. */
    public static final class Candidate implements AutoCloseable {
        private final Protocol protocol;
        private char[] contents;

        private Candidate(Protocol protocol, char[] contents) {
            this.protocol = protocol;
            this.contents = contents;
        }

        public Protocol getProtocol() { return protocol; }

        /** Caller must wipe this copy after native-engine validation/use. */
        public synchronized char[] copyContents() {
            if (contents == null) throw new IllegalStateException("VPN import has been closed.");
            return contents.clone();
        }

        @Override public synchronized void close() {
            if (contents != null) {
                Arrays.fill(contents, '\0');
                contents = null;
            }
        }

        @Override public String toString() { return "VPN import (" + protocol + ", contents redacted)"; }
    }

    private static final class WipingBuffer extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
