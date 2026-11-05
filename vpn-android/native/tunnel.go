package main

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// This backend has no Android TUN interface or OS routes. Only authenticated local
// playback proxy requests enter its WireGuard netstack; other app traffic stays direct.
type tunnelInput struct {
	UAPI        string   `json:"uapi"`
	Addresses   []string `json:"addresses"`
	DNS         []string `json:"dns"`
	MTU         int      `json:"mtu"`
	Token       string   `json:"token"`
	TunnelHTTPS bool     `json:"tunnelHttps"`
}

type httpTunnel struct {
	device      *device.Device
	transport   *http.Transport
	direct      *http.Transport
	server      *http.Server
	listener    net.Listener
	token       string
	tunnelHTTPS bool
	mu          sync.Mutex
	closed      bool
	hijacked    map[net.Conn]bool
	once        sync.Once
}

func decodeInput(encoded string) (tunnelInput, error) {
	var input tunnelInput
	if len(encoded) > 1<<20 {
		return input, errors.New("configuration limit")
	}
	decoder := json.NewDecoder(strings.NewReader(encoded))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&input); err != nil {
		return input, errors.New("configuration format")
	}
	if len(input.Token) < 32 || len(input.Token) > 64 || input.MTU < 1280 || input.MTU > 9000 {
		return input, errors.New("configuration values")
	}
	return input, nil
}

// A proxy without a configuration can carry direct HTTPS, but cannot send remote HTTP.
// The Android request barrier installs the selected configuration on first HTTP demand.
func newHTTPTunnel(encoded string) (*httpTunnel, error) {
	input, err := decodeInput(encoded)
	if err != nil {
		return nil, err
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, errors.New("proxy initialization")
	}
	tunnel := &httpTunnel{listener: listener, token: input.Token, tunnelHTTPS: input.TunnelHTTPS, hijacked: make(map[net.Conn]bool),
		direct: &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: 15 * time.Second}).DialContext,
			DisableCompression: true, ResponseHeaderTimeout: 20 * time.Second}}
	if input.UAPI != "" {
		if err = tunnel.configure(input); err != nil {
			listener.Close()
			return nil, err
		}
	}
	tunnel.server = &http.Server{Handler: tunnel, ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout: 60 * time.Second, MaxHeaderBytes: 64 << 10, ErrorLog: log.New(io.Discard, "", 0)}
	go func() { _ = tunnel.server.Serve(listener) }()
	return tunnel, nil
}

func (t *httpTunnel) configure(input tunnelInput) error {
	if input.Token != t.token {
		return errors.New("session mismatch")
	}
	addresses, err := parseAddresses(input.Addresses)
	if err != nil {
		return err
	}
	dns, err := parseAddresses(input.DNS)
	if err != nil {
		return err
	}
	tun, network, err := netstack.CreateNetTUN(addresses, dns, input.MTU)
	if err != nil {
		return errors.New("netstack initialization")
	}
	quiet := func(string, ...any) {}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), &device.Logger{Verbosef: quiet, Errorf: quiet})
	if err = dev.IpcSet(input.UAPI); err == nil {
		err = dev.Up()
	}
	if err != nil {
		dev.Close()
		return errors.New("wireguard initialization")
	}
	transport := &http.Transport{Proxy: nil, DialContext: network.DialContext, DisableCompression: true,
		TLSHandshakeTimeout: 5 * time.Second, ResponseHeaderTimeout: 20 * time.Second,
		MaxIdleConns: 64, MaxIdleConnsPerHost: 32, IdleConnTimeout: 60 * time.Second}
	t.mu.Lock()
	if t.closed || t.device != nil {
		t.mu.Unlock()
		transport.CloseIdleConnections()
		dev.Close()
		return errors.New("session unavailable")
	}
	t.device, t.transport = dev, transport
	t.mu.Unlock()
	return nil
}

func parseAddresses(values []string) ([]netip.Addr, error) {
	if len(values) == 0 || len(values) > 8 {
		return nil, errors.New("address count")
	}
	addresses := make([]netip.Addr, 0, len(values))
	for _, value := range values {
		address, err := netip.ParseAddr(value)
		if err != nil || address.IsUnspecified() || address.IsMulticast() || address.IsLoopback() || address.Zone() != "" {
			return nil, errors.New("address value")
		}
		addresses = append(addresses, address)
	}
	return addresses, nil
}

func (t *httpTunnel) port() int { return t.listener.Addr().(*net.TCPAddr).Port }

func (t *httpTunnel) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	wanted := "Basic " + base64.StdEncoding.EncodeToString([]byte("nuvio:"+t.token))
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("Proxy-Authorization")), []byte(wanted)) != 1 {
		w.Header().Set("Proxy-Authenticate", `Basic realm="Nuvio playback"`)
		http.Error(w, "Proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	if r.Method == http.MethodConnect {
		if !preparePlayback(r.Context(), "https://"+r.Host) || t.isClosed() {
			http.Error(w, "Playback unavailable", 503)
			return
		}
		t.connectDirect(w, r)
		return
	}
	if r.URL.Scheme != "http" || r.URL.Host == "" || r.URL.User != nil {
		http.Error(w, "Unsupported proxy request", http.StatusBadRequest)
		return
	}
	if !preparePlayback(r.Context(), r.URL.String()) || t.isClosed() {
		http.Error(w, "Protected stream unavailable", 503)
		return
	}
	t.mu.Lock()
	transport := t.transport
	if isLoopback(r.URL.Hostname()) {
		transport = t.direct
	}
	t.mu.Unlock()
	if transport == nil {
		http.Error(w, "Protected stream unavailable", 503)
		return
	}
	proxy := &httputil.ReverseProxy{
		Rewrite: func(request *httputil.ProxyRequest) {
			request.Out.Header.Del("Proxy-Authorization")
			// Signed stream queries and caller-supplied forwarding headers stay intact.
			request.Out.URL.RawQuery = request.In.URL.RawQuery
			for _, name := range []string{"Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto"} {
				if values, ok := request.In.Header[name]; ok {
					request.Out.Header[name] = append([]string(nil), values...)
				}
			}
		},
		Transport: transport, FlushInterval: -1,
		ErrorLog: log.New(io.Discard, "", 0),
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, _ error) {
			http.Error(w, "Protected stream unavailable", http.StatusBadGateway)
		},
	}
	proxy.ServeHTTP(w, r)
}

// Native players may send HTTPS CONNECT to their configured proxy. Preserve
// their TLS handshake/certificate behavior and keep HTTPS outside HTTP-only VPN.
func (t *httpTunnel) connectDirect(w http.ResponseWriter, r *http.Request) {
	if _, _, err := net.SplitHostPort(r.Host); err != nil {
		http.Error(w, "Invalid CONNECT target", http.StatusBadRequest)
		return
	}
	host, _, _ := net.SplitHostPort(r.Host)
	dial := (&net.Dialer{Timeout: 15 * time.Second}).DialContext
	t.mu.Lock()
	if t.tunnelHTTPS && !isLoopback(host) {
		if t.transport == nil {
			t.mu.Unlock()
			http.Error(w, "Protected stream unavailable", 503)
			return
		}
		dial = t.transport.DialContext
	}
	t.mu.Unlock()
	upstream, err := dial(r.Context(), "tcp", r.Host)
	if err != nil {
		http.Error(w, "Stream unavailable", http.StatusBadGateway)
		return
	}
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		http.Error(w, "CONNECT unavailable", 500)
		return
	}
	client, buffered, err := hijacker.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		client.Close()
		upstream.Close()
		return
	}
	t.hijacked[client], t.hijacked[upstream] = true, true
	t.mu.Unlock()
	defer func() {
		client.Close()
		upstream.Close()
		t.mu.Lock()
		delete(t.hijacked, client)
		delete(t.hijacked, upstream)
		t.mu.Unlock()
	}()
	if _, err = buffered.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	if err = buffered.Flush(); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(client, upstream); done <- struct{}{} }()
	go func() { _, _ = io.Copy(upstream, buffered); done <- struct{}{} }()
	<-done
	client.Close()
	upstream.Close()
	<-done
}

func (t *httpTunnel) stats() (rx, tx, handshake int64) {
	t.mu.Lock()
	dev := t.device
	t.mu.Unlock()
	if dev == nil {
		return
	}
	config, err := dev.IpcGet()
	if err != nil {
		return
	}
	var seconds, nanos int64
	for _, line := range strings.Split(config, "\n") {
		key, value, found := strings.Cut(line, "=")
		if !found {
			continue
		}
		number, err := strconv.ParseInt(value, 10, 64)
		if err != nil || number < 0 {
			continue
		}
		switch key {
		case "rx_bytes":
			rx += number
		case "tx_bytes":
			tx += number
		case "last_handshake_time_sec":
			seconds = number
		case "last_handshake_time_nsec":
			nanos = number
		}
	}
	return rx, tx, seconds*1000 + nanos/1000000
}

func (t *httpTunnel) probe() bool {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://www.gstatic.com/generate_204", nil)
	t.mu.Lock()
	transport := t.transport
	t.mu.Unlock()
	if transport == nil {
		return false
	}
	response, err := transport.RoundTrip(request)
	if err != nil {
		return false
	}
	response.Body.Close()
	rx, _, handshake := t.stats()
	return response.StatusCode == http.StatusNoContent && rx > 0 && handshake > 0 && time.Now().UnixMilli()-handshake <= 180000
}

func (t *httpTunnel) egress() string {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.ipify.org", nil)
	t.mu.Lock()
	transport := t.transport
	t.mu.Unlock()
	if transport == nil {
		return ""
	}
	response, err := transport.RoundTrip(request)
	if err != nil {
		return ""
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return ""
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 65))
	if err != nil || len(body) > 64 {
		return ""
	}
	address, err := netip.ParseAddr(strings.TrimSpace(string(body)))
	if err != nil {
		return ""
	}
	return address.String()
}

func (t *httpTunnel) close() {
	t.once.Do(func() {
		t.mu.Lock()
		t.closed = true
		for connection := range t.hijacked {
			connection.Close()
		}
		t.mu.Unlock()
		t.server.Close()
		if t.transport != nil {
			t.transport.CloseIdleConnections()
		}
		if t.direct != nil {
			t.direct.CloseIdleConnections()
		}
		if t.device != nil {
			t.device.Close()
		}
	})
}

func main() {}

func (t *httpTunnel) isClosed() bool { t.mu.Lock(); defer t.mu.Unlock(); return t.closed }
func isLoopback(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	address, err := netip.ParseAddr(host)
	return err == nil && address.IsLoopback()
}
