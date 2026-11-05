package com.nuvio.vpn.android;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** URLConnection compatibility for the existing stream probes/recorders in HTTP-only mode. */
final class PlaybackHttpURLConnection extends HttpURLConnection {
    private final OkHttpClient client;
    private volatile Call call;
    private volatile Response response;
    private volatile boolean disconnected;
    private ByteArrayOutputStream output;

    PlaybackHttpURLConnection(URL address, OkHttpClient client) { super(address); this.client = client; }

    @Override public void connect() throws IOException {
        if (connected) return;
        if (disconnected) throw new IOException("Playback request was cancelled.");
        Request.Builder request = new Request.Builder().url(url);
        for (Map.Entry<String, List<String>> header : getRequestProperties().entrySet())
            for (String value : header.getValue()) request.addHeader(header.getKey(), value);
        String method = getRequestMethod();
        boolean bodyRequired = output != null || method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        RequestBody body = bodyRequired ? RequestBody.create((MediaType) null, output == null ? new byte[0] : output.toByteArray()) : null;
        request.method(method, body);
        Call pending = client.newBuilder().connectTimeout(getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(getReadTimeout(), TimeUnit.MILLISECONDS)
                .followRedirects(getInstanceFollowRedirects()).followSslRedirects(getInstanceFollowRedirects())
                .build().newCall(request.build());
        call = pending;
        if (disconnected) pending.cancel();
        Response received = pending.execute();
        response = received;
        if (disconnected) { received.close(); throw new IOException("Playback request was cancelled."); }
        responseCode = received.code();
        responseMessage = received.message();
        url = received.request().url().url();
        connected = true;
    }
    @Override public int getResponseCode() throws IOException { connect(); return responseCode; }
    @Override public String getResponseMessage() throws IOException { connect(); return responseMessage; }
    @Override public InputStream getInputStream() throws IOException {
        connect();
        if (responseCode >= 400) throw new FileNotFoundException("HTTP status " + responseCode);
        return response.body() == null ? new java.io.ByteArrayInputStream(new byte[0]) : response.body().byteStream();
    }
    @Override public InputStream getErrorStream() {
        try { connect(); return responseCode >= 400 && response.body() != null ? response.body().byteStream() : null; }
        catch (IOException failure) { return null; }
    }
    @Override public OutputStream getOutputStream() throws IOException {
        if (connected) throw new ProtocolException("Request is already connected.");
        if (getRequestMethod().equals("GET")) setRequestMethod("POST");
        setDoOutput(true);
        if (output == null) output = new ByteArrayOutputStream();
        return output;
    }
    @Override public String getHeaderField(String name) {
        try { connect(); return name == null ? status() : response.header(name); }
        catch (IOException failure) { return null; }
    }
    @Override public Map<String, List<String>> getHeaderFields() {
        try {
            connect();
            Map<String, List<String>> headers = new LinkedHashMap<>(response.headers().toMultimap());
            headers.put(null, Collections.singletonList(status()));
            return Collections.unmodifiableMap(headers);
        } catch (IOException failure) { return Collections.emptyMap(); }
    }
    @Override public String getHeaderField(int index) {
        try { connect(); return index == 0 ? status() : index > 0 && index <= response.headers().size() ? response.headers().value(index - 1) : null; }
        catch (IOException failure) { return null; }
    }
    @Override public String getHeaderFieldKey(int index) {
        try { connect(); return index > 0 && index <= response.headers().size() ? response.headers().name(index - 1) : null; }
        catch (IOException failure) { return null; }
    }
    private String status() { return "HTTP/1.1 " + responseCode + " " + responseMessage; }
    okhttp3.Handshake handshake() { return response == null ? null : response.handshake(); }
    @Override public boolean usingProxy() { return true; }
    @Override public void disconnect() {
        disconnected = true;
        Call pending = call;
        if (pending != null) pending.cancel();
        Response received = response;
        if (received != null) received.close();
    }
}
