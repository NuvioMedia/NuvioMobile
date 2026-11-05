package com.nuvio.vpn.android;

import com.nuvio.vpn.core.ProfileImport;
import com.wireguard.config.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Shared real-library checks for simulated and device Android runtimes; opens no VPN/network. */
public final class WireGuardProfileChecks {
    private static final String SAMPLE = "[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "Address = 10.0.0.2/32\nDNS = 10.0.0.1\nMTU = 1280\nListenPort = 51820\n"
            + "[Peer]\nPublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\n"
            + "AllowedIPs = 0.0.0.0/0\nEndpoint = 192.0.2.1:51820\n";
    private static int checks;

    public static void main(String[] args) throws Exception {
        Config original = parse(SAMPLE);
        Config normalized = WireGuardProfiles.forAppWide(original, "com.example.nuvio");
        check(normalized.getInterface().getIncludedApplications().equals(Collections.singleton("com.example.nuvio")));
        check(normalized.getInterface().getExcludedApplications().isEmpty());
        check(original.getInterface().getIncludedApplications().isEmpty());
        check(normalized.getInterface().getMtu().equals(original.getInterface().getMtu()));
        check(normalized.getInterface().getListenPort().equals(original.getInterface().getListenPort()));
        check(normalized.getInterface().getAddresses().equals(original.getInterface().getAddresses()));
        check(normalized.getInterface().getDnsServers().equals(original.getInterface().getDnsServers()));
        check(normalized.getPeers().equals(original.getPeers()));
        Config excluded = parse(SAMPLE.replace("MTU = 1280", "ExcludedApplications = com.example.nuvio\nMTU = 1280"));
        check(WireGuardProfiles.forAppWide(excluded, "com.example.nuvio").getInterface().getExcludedApplications().isEmpty());
        Config included = parse(SAMPLE.replace("MTU = 1280", "IncludedApplications = com.example.other\nMTU = 1280"));
        check(WireGuardProfiles.forAppWide(included, "com.example.nuvio").getInterface().getIncludedApplications()
                .equals(Collections.singleton("com.example.nuvio")));
        rejects(SAMPLE.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "private-key-must-not-be-logged"), false);
        rejects(SAMPLE.replace("Address = 10.0.0.2/32\n", ""), false);
        rejects(SAMPLE.replace("DNS = 10.0.0.1\n", ""), true);
        rejects(SAMPLE.replace("0.0.0.0/0", "10.0.0.0/8"), true);
        rejects(SAMPLE.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/32, fd00::2/128"), true);
        Config dual = parse(SAMPLE.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/32, fd00::2/128")
                .replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 0.0.0.0/0, ::/0"));
        check(WireGuardProfiles.forAppWide(dual, "com.example.nuvio").getPeers().size() == 1);
        try { WireGuardProfiles.forAppWide(original, ""); throw new AssertionError("Invalid package accepted."); }
        catch (IOException expected) { checks++; }
        System.out.println("WireGuard profiles: " + checks + " checks passed.");
    }

    private static Config parse(String text) throws Exception {
        try (ProfileImport.Candidate candidate = ProfileImport.read(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            return WireGuardProfiles.parse(candidate);
        }
    }

    private static void rejects(String text, boolean appWide) throws Exception {
        try {
            Config config = parse(text);
            if (appWide) WireGuardProfiles.forAppWide(config, "com.example.nuvio");
            throw new AssertionError("Invalid WireGuard configuration accepted.");
        } catch (IOException expected) {
            check(!expected.getMessage().contains("private-key-must-not-be-logged"));
        }
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("WireGuard profile check failed: " + checks);
    }
}
