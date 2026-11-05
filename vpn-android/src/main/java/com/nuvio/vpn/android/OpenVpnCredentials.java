package com.nuvio.vpn.android;

import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Edits only OpenVPN's inline auth-user-pass option. The resulting configuration
 * goes through the same import validation and encrypted vault as a new profile. */
final class OpenVpnCredentials {
    private OpenVpnCredentials() {}
    enum Login { NONE, REQUIRED, SAVED }

    static Login inspect(char[] text) {
        try {
            boolean required = false;
            boolean authBlock = false;
            int fields = 0;
            for (String line : new String(text).split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("<auth-user-pass>")) { authBlock = true; fields = 0; required = true; }
                else if (trimmed.equalsIgnoreCase("</auth-user-pass>")) {
                    if (authBlock && fields >= 2) return Login.SAVED;
                    authBlock = false;
                } else if (authBlock && !trimmed.isEmpty()) fields++;
                else if (trimmed.toLowerCase(Locale.ROOT).matches("(?:--)?auth-user-pass(?:\\s.*)?")) required = true;
            }
            return required ? Login.REQUIRED : Login.NONE;
        } finally { Arrays.fill(text,'\0'); }
    }
    static ProfileImport.Candidate replace(ProfileEnvelope.Opened opened, String username, String password) throws IOException {
        if (opened.getProtocol() != ProfileImport.Protocol.OPENVPN || !valid(username) || !valid(password))
            throw new IOException("Invalid OpenVPN service credentials.");
        char[] chars = opened.copyContents();
        byte[] data = null;
        try {
            StringBuilder updated = new StringBuilder();
            String block = null;
            boolean found = false;
            for (String line : new String(chars).split("\\r?\\n")) {
                String option = line.trim().toLowerCase(Locale.ROOT);
                if (block != null) {
                    if (!block.equals("auth-user-pass")) updated.append(line).append('\n');
                    if (option.equals("</" + block + ">")) block = null;
                } else if (option.startsWith("<") && option.endsWith(">")) {
                    block = option.substring(1,option.length()-1);
                    if (block.equals("auth-user-pass")) found = true;
                    else updated.append(line).append('\n');
                } else if (option.matches("(?:--)?auth-user-pass(?:\\s.*)?")) {
                    found = true;
                } else updated.append(line).append('\n');
            }
            if (!found) throw new IOException("This profile does not request a username and password.");
            updated.append("<auth-user-pass>\n").append(username).append('\n').append(password).append("\n</auth-user-pass>\n");
            data = updated.toString().getBytes(StandardCharsets.UTF_8);
            return ProfileImport.read(new ByteArrayInputStream(data), ProfileImport.Protocol.OPENVPN);
        } finally {
            Arrays.fill(chars,'\0');
            if(data != null) Arrays.fill(data,(byte)0);
        }
    }
    private static boolean valid(String value) {
        return value != null && !value.isEmpty() && value.length() <= 1024 && !value.trim().startsWith("</")
                && value.indexOf('\n') == -1 && value.indexOf('\r') == -1 && value.indexOf('\0') == -1;
    }
}
