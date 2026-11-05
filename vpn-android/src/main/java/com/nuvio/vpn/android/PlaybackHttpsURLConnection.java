package com.nuvio.vpn.android;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.OkHttpClient;

/** Retains the caller's HTTPS settings while enforcing playback routing on redirects. */
final class PlaybackHttpsURLConnection extends HttpsURLConnection {
    private final OkHttpClient baseline;
    private PlaybackHttpURLConnection delegate;
    PlaybackHttpsURLConnection(URL url, OkHttpClient baseline) {
        super(url); this.baseline = baseline;
        setSSLSocketFactory(baseline.sslSocketFactory());
        setHostnameVerifier(baseline.hostnameVerifier());
    }
    @SuppressWarnings("deprecation")
    private synchronized PlaybackHttpURLConnection connection() throws IOException {
        if (delegate == null) {
            OkHttpClient.Builder client = baseline.newBuilder().hostnameVerifier(getHostnameVerifier());
            if (getSSLSocketFactory() != baseline.sslSocketFactory())
                client.sslSocketFactory(getSSLSocketFactory());
            delegate = new PlaybackHttpURLConnection(url, client.build());
            delegate.setConnectTimeout(getConnectTimeout());
            delegate.setReadTimeout(getReadTimeout());
            delegate.setInstanceFollowRedirects(getInstanceFollowRedirects());
            delegate.setRequestMethod(getRequestMethod());
            delegate.setDoOutput(getDoOutput());
            for (Map.Entry<String, List<String>> entry : getRequestProperties().entrySet())
                for (String value : entry.getValue()) delegate.addRequestProperty(entry.getKey(), value);
        }
        return delegate;
    }
    @Override public void connect() throws IOException { connection().connect(); connected = true; url = delegate.getURL(); }
    @Override public int getResponseCode() throws IOException { connect(); return delegate.getResponseCode(); }
    @Override public String getResponseMessage() throws IOException { connect(); return delegate.getResponseMessage(); }
    @Override public InputStream getInputStream() throws IOException { connect(); return delegate.getInputStream(); }
    @Override public OutputStream getOutputStream() throws IOException { return connection().getOutputStream(); }
    @Override public InputStream getErrorStream() { try { return connection().getErrorStream(); } catch (IOException e) { return null; } }
    @Override public Map<String, List<String>> getHeaderFields() { try { return connection().getHeaderFields(); } catch (IOException e) { return java.util.Collections.emptyMap(); } }
    @Override public String getHeaderField(String name) { try { return connection().getHeaderField(name); } catch (IOException e) { return null; } }
    @Override public String getHeaderField(int index) { try { return connection().getHeaderField(index); } catch (IOException e) { return null; } }
    @Override public String getHeaderFieldKey(int index) { try { return connection().getHeaderFieldKey(index); } catch (IOException e) { return null; } }
    @Override public void disconnect() { try { connection().disconnect(); } catch (IOException ignored) { } }
    @Override public boolean usingProxy() { return true; }
    private okhttp3.Handshake handshake() { try { connect(); return delegate.handshake(); } catch (IOException e) { throw new IllegalStateException("HTTPS connection is unavailable.", e); } }
    @Override public String getCipherSuite() { return handshake().cipherSuite().javaName(); }
    @Override public Certificate[] getLocalCertificates() { return handshake().localCertificates().toArray(new Certificate[0]); }
    @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException { return handshake().peerCertificates().toArray(new Certificate[0]); }
    @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException { return handshake().peerPrincipal(); }
    @Override public Principal getLocalPrincipal() { return handshake().localPrincipal(); }
}
