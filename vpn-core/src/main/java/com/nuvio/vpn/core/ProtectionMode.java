package com.nuvio.vpn.core;

/** Saved intent; selecting a mode alone never proves a tunnel is connected. */
public enum ProtectionMode {
    OFF,
    HTTP_ONLY,
    HTTP_HTTPS,
    APP_WIDE;

    public boolean isPlaybackScoped() { return this == HTTP_ONLY || this == HTTP_HTTPS; }
}
