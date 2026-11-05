package com.nuvio.vpn.android;

interface WireGuardEngine extends AutoCloseable {
    boolean isUp();
    WireGuardTraffic statistics() throws Exception;
    boolean probe();
    String egress();
    @Override void close() throws Exception;
}

final class WireGuardTraffic {
    final long received, sent, handshakeMillis;
    WireGuardTraffic(long received, long sent, long handshakeMillis) {
        this.received = received;
        this.sent = sent;
        this.handshakeMillis = handshakeMillis;
    }
}
