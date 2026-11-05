package com.nuvio.vpn.android;

interface TunnelEngine extends AutoCloseable {
    boolean isUp();
    default boolean authRejected() { return false; }
    TunnelTraffic statistics() throws Exception;
    boolean probe();
    String egress();
    @Override void close() throws Exception;
}

final class TunnelTraffic {
    final long received, sent, handshakeMillis;
    final boolean authenticated;
    final long session;
    TunnelTraffic(long received, long sent, long handshakeMillis) {
        this.received = received;
        this.sent = sent;
        this.handshakeMillis = handshakeMillis;
        authenticated = handshakeMillis > 0 && System.currentTimeMillis() - handshakeMillis <= 180000;
        session = 0;
    }
    TunnelTraffic(long received, long sent, boolean authenticated, long session) {
        this.received = received;
        this.sent = sent;
        this.handshakeMillis = 0;
        this.authenticated = authenticated;
        this.session = session;
    }
}
