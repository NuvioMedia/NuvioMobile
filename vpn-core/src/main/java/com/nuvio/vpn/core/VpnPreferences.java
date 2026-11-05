package com.nuvio.vpn.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Device-local intent only; neither a selected profile nor a scope proves a connection. */
public final class VpnPreferences {
    public static final int MAX_BYTES = 80;
    private static final String HEADER = "NUVIO-VPN-2\n";
    private final ProtectionMode mode;
    private final String selectedProfileId;

    public VpnPreferences(ProtectionMode mode, String selectedProfileId) {
        if (mode == null) throw new IllegalArgumentException("VPN scope is required.");
        if (selectedProfileId != null) ProfileEnvelope.validateId(selectedProfileId);
        this.mode = mode;
        this.selectedProfileId = selectedProfileId;
    }

    public static VpnPreferences defaults() { return new VpnPreferences(ProtectionMode.OFF, null); }
    public ProtectionMode getMode() { return mode; }
    public String getSelectedProfileId() { return selectedProfileId; }
    public VpnPreferences withMode(ProtectionMode value) { return new VpnPreferences(value, selectedProfileId); }

    /** Clearing a missing/deleted selection also turns off saved protection intent; never pick a fallback. */
    public VpnPreferences withSelectedProfile(String id) {
        return new VpnPreferences(id == null ? ProtectionMode.OFF : mode, id);
    }

    public byte[] encode() {
        return (HEADER + mode.name() + "\n" + (selectedProfileId == null ? "" : selectedProfileId))
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** Accepts the original scope-only file as well as the versioned settings representation. */
    public static VpnPreferences decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        for (byte value : bytes) if (value < 0 || value == 0) throw invalid();
        String text = new String(bytes, StandardCharsets.US_ASCII);
        try {
            if (!text.startsWith(HEADER)) return new VpnPreferences(ProtectionMode.valueOf(text), null);
            String[] fields = text.substring(HEADER.length()).split("\n", -1);
            if (fields.length != 2) throw invalid();
            return new VpnPreferences(ProtectionMode.valueOf(fields[0]), fields[1].isEmpty() ? null : fields[1]);
        } catch (IllegalArgumentException e) { throw invalid(); }
    }

    private static IOException invalid() { return new IOException("Invalid VPN settings."); }

    @Override public boolean equals(Object other) {
        if (!(other instanceof VpnPreferences)) return false;
        VpnPreferences value = (VpnPreferences) other;
        return mode == value.mode && (selectedProfileId == null ? value.selectedProfileId == null
                : selectedProfileId.equals(value.selectedProfileId));
    }

    @Override public int hashCode() { return 31 * mode.hashCode() + (selectedProfileId == null ? 0 : selectedProfileId.hashCode()); }
}
