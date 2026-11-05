package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

const testToken = "a-temporary-test-token-not-a-vpn-key"

func testProxy(t *testing.T, dial func(context.Context, string, string) (net.Conn, error)) (*httpTunnel, *url.URL) {
	t.Helper()
	tunnel := &httpTunnel{token: testToken, hijacked: make(map[net.Conn]bool),
		transport: &http.Transport{Proxy: nil, DialContext: dial, DisableCompression: true}}
	server := httptest.NewServer(tunnel)
	tunnel.server, tunnel.listener = server.Config, server.Listener
	t.Cleanup(func() { tunnel.close(); server.Close() })
	proxyURL, _ := url.Parse(server.URL)
	return tunnel, proxyURL
}

func proxyClient(t *testing.T, proxyURL *url.URL, authenticated bool) *http.Client {
	t.Helper()
	proxy := *proxyURL
	if authenticated {
		proxy.User = url.UserPassword("nuvio", testToken)
	}
	transport := &http.Transport{Proxy: http.ProxyURL(&proxy)}
	t.Cleanup(transport.CloseIdleConnections)
	return &http.Client{Transport: transport, Timeout: 3 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

func TestProxyAuthenticationStopsNetworkAccess(t *testing.T) {
	var connections atomic.Int32
	_, proxy := testProxy(t, func(context.Context, string, string) (net.Conn, error) {
		connections.Add(1)
		return nil, errors.New("unexpected network access")
	})
	response, err := proxyClient(t, proxy, false).Get("http://stream.invalid/video")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusProxyAuthRequired || connections.Load() != 0 {
		t.Fatal("unauthenticated requests reached the network")
	}
}

func TestHTTPUsesTunnelAndPreservesMediaRequestAndResponse(t *testing.T) {
	seen := make(chan *http.Request, 1)
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen <- r.Clone(r.Context())
		w.Header().Set("Content-Range", "bytes 20-23/100")
		w.Header().Add("Set-Cookie", "session=retained; HttpOnly")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = io.WriteString(w, "data")
	}))
	defer origin.Close()
	var connections atomic.Int32
	_, proxy := testProxy(t, func(ctx context.Context, network, target string) (net.Conn, error) {
		if target != "stream.invalid:80" {
			t.Errorf("unexpected tunnel destination: %s", target)
		}
		connections.Add(1)
		return (&net.Dialer{}).DialContext(ctx, network, origin.Listener.Addr().String())
	})
	request, _ := http.NewRequest(http.MethodGet, "http://stream.invalid/video?signature=a%2Bb&part=1&part=2", nil)
	request.Header.Set("Range", "bytes=20-23")
	request.Header.Set("Authorization", "Bearer synthetic-media-test")
	request.Header.Set("Cookie", "source=preserved")
	request.Header.Set("X-Forwarded-For", "192.0.2.9")
	response, err := proxyClient(t, proxy, true).Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(response.Body)
	got := <-seen
	if connections.Load() != 1 || got.Host != "stream.invalid" || got.URL.RawQuery != request.URL.RawQuery ||
		got.Header.Get("Range") != "bytes=20-23" || got.Header.Get("Authorization") != request.Header.Get("Authorization") ||
		got.Header.Get("Cookie") != "source=preserved" || got.Header.Get("X-Forwarded-For") != "192.0.2.9" ||
		got.Header.Get("Proxy-Authorization") != "" || response.StatusCode != http.StatusPartialContent ||
		response.Header.Get("Content-Range") != "bytes 20-23/100" || len(response.Cookies()) != 1 || string(body) != "data" {
		t.Fatal("proxy changed the media request, leaked its credential, or changed the ranged response")
	}
}

func TestFailedTunnelDoesNotReturnDirectContent(t *testing.T) {
	var attempts atomic.Int32
	_, proxy := testProxy(t, func(context.Context, string, string) (net.Conn, error) {
		attempts.Add(1)
		return nil, errors.New("tunnel unavailable")
	})
	response, err := proxyClient(t, proxy, true).Get("http://stream.invalid/video")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadGateway || attempts.Load() != 1 {
		t.Fatal("failed protected request was not blocked")
	}
}

func TestHTTPSConnectIsDirectAndDisconnectClosesIt(t *testing.T) {
	origin, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer origin.Close()
	go func() {
		connection, err := origin.Accept()
		if err == nil {
			defer connection.Close()
			_, _ = io.Copy(connection, connection)
		}
	}()
	var tunneled atomic.Int32
	tunnel, proxy := testProxy(t, func(context.Context, string, string) (net.Conn, error) {
		tunneled.Add(1)
		return nil, errors.New("HTTPS must stay direct")
	})
	client, err := net.Dial("tcp", proxy.Host)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	auth := base64.StdEncoding.EncodeToString([]byte("nuvio:" + testToken))
	_, err = io.WriteString(client, "CONNECT "+origin.Addr().String()+" HTTP/1.1\r\nHost: "+origin.Addr().String()+"\r\nProxy-Authorization: Basic "+auth+"\r\n\r\n")
	if err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(client)
	response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil || response.StatusCode != http.StatusOK {
		t.Fatalf("CONNECT failed: %v", err)
	}
	payload := "opaque TLS bytes remain unchanged"
	_, _ = io.WriteString(client, payload)
	got := make([]byte, len(payload))
	if _, err = io.ReadFull(reader, got); err != nil || string(got) != payload || tunneled.Load() != 0 {
		t.Fatal("HTTPS was changed or entered the HTTP tunnel")
	}
	tunnel.close()
	if _, err = reader.ReadByte(); err == nil {
		t.Fatal("disconnect left a proxy stream open")
	}
}

func TestRejectsMissingOrUnsafeNetstackAddresses(t *testing.T) {
	for _, values := range [][]string{nil, {"localhost"}, {"127.0.0.1"}, {"::"}, {"ff02::1"}, {strings.Repeat("x", 100)}} {
		if _, err := parseAddresses(values); err == nil {
			t.Fatalf("accepted unsafe address set: %v", values)
		}
	}
}

func TestHTTPTraversesRealWireGuardHandshakeAndNetstack(t *testing.T) {
	serverPrivate, clientPrivate := make([]byte, 32), make([]byte, 32)
	if _, err := rand.Read(serverPrivate); err != nil {
		t.Fatal("test key generation")
	}
	if _, err := rand.Read(clientPrivate); err != nil {
		t.Fatal("test key generation")
	}
	serverPublic, err := curve25519.X25519(serverPrivate, curve25519.Basepoint)
	if err != nil {
		t.Fatal("test public key")
	}
	clientPublic, err := curve25519.X25519(clientPrivate, curve25519.Basepoint)
	if err != nil {
		t.Fatal("test public key")
	}
	tun, network, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.252.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal("test netstack")
	}
	quiet := func(string, ...any) {}
	peer := device.NewDevice(tun, conn.NewDefaultBind(), &device.Logger{Verbosef: quiet, Errorf: quiet})
	t.Cleanup(peer.Close)
	serverConfig := "private_key=" + hex.EncodeToString(serverPrivate) + "\nlisten_port=0\npublic_key=" +
		hex.EncodeToString(clientPublic) + "\nallowed_ip=10.252.0.2/32\n"
	if peer.IpcSet(serverConfig) != nil || peer.Up() != nil {
		t.Fatal("test WireGuard peer initialization")
	}
	state, err := peer.IpcGet()
	if err != nil {
		t.Fatal("test WireGuard peer status")
	}
	port := ""
	for _, line := range strings.Split(state, "\n") {
		if strings.HasPrefix(line, "listen_port=") {
			port = strings.TrimPrefix(line, "listen_port=")
		}
	}
	if port == "" || port == "0" {
		t.Fatal("test peer UDP port")
	}
	listener, err := network.ListenTCPAddrPort(netip.MustParseAddrPort("10.252.0.1:8080"))
	if err != nil {
		t.Fatal("test peer HTTP listener")
	}
	payload := bytes.Repeat([]byte("data"), 16*1024)
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.ServeContent(w, r, "video.mkv", time.Time{}, bytes.NewReader(payload))
	})}
	t.Cleanup(func() { _ = server.Close() })
	go func() { _ = server.Serve(listener) }()
	dns, err := network.ListenUDPAddrPort(netip.MustParseAddrPort("10.252.0.1:53"))
	if err != nil {
		t.Fatal("test DNS listener")
	}
	t.Cleanup(func() { _ = dns.Close() })
	var dnsQueries atomic.Int32
	go func() {
		buffer := make([]byte, 4096)
		for {
			n, address, err := dns.ReadFrom(buffer)
			if err != nil {
				return
			}
			var question dnsmessage.Message
			if question.Unpack(buffer[:n]) != nil || len(question.Questions) != 1 {
				continue
			}
			dnsQueries.Add(1)
			response := dnsmessage.Message{Header: dnsmessage.Header{ID: question.ID, Response: true, RecursionAvailable: true}, Questions: question.Questions}
			if question.Questions[0].Type == dnsmessage.TypeA {
				response.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: question.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 30}, Body: &dnsmessage.AResource{A: [4]byte{10, 252, 0, 1}}}}
			}
			packet, err := response.Pack()
			if err == nil {
				_, _ = dns.WriteTo(packet, address)
			}
		}
	}()
	input := tunnelInput{UAPI: "private_key=" + hex.EncodeToString(clientPrivate) + "\npublic_key=" +
		hex.EncodeToString(serverPublic) + "\nallowed_ip=0.0.0.0/0\nendpoint=127.0.0.1:" + port + "\n",
		Addresses: []string{"10.252.0.2"}, DNS: []string{"10.252.0.1"}, MTU: 1280, Token: testToken, TunnelHTTPS: true}
	encoded, _ := json.Marshal(input)
	tunnel, err := newHTTPTunnel(string(encoded))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(tunnel.close)
	proxy, _ := url.Parse("http://" + tunnel.listener.Addr().String())
	clientHTTP := proxyClient(t, proxy, true)
	request, _ := http.NewRequest(http.MethodGet, "http://playback.test:8080/video", nil)
	request.Header.Set("Range", "bytes=0-3")
	response, err := clientHTTP.Do(request)
	if err != nil {
		t.Fatal("protected HTTP did not reach the test peer")
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(response.Body)
	rx, tx, handshake := tunnel.stats()
	if response.StatusCode != http.StatusPartialContent || string(body) != "data" || rx == 0 || tx == 0 || handshake == 0 || dnsQueries.Load() == 0 {
		t.Fatal("request did not complete through a verified WireGuard session")
	}
	// Tail/cue and middle/cluster reads must stay byte-exact across a pooled connection.
	for _, bounds := range [][2]int{{65500, 65535}, {128, 1023}} {
		rangeRequest, _ := http.NewRequest(http.MethodGet, "http://playback.test:8080/video", nil)
		rangeRequest.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", bounds[0], bounds[1]))
		part, err := clientHTTP.Do(rangeRequest)
		if err != nil {
			t.Fatal("protected range request failed")
		}
		got, err := io.ReadAll(part.Body)
		_ = part.Body.Close()
		if err != nil || part.StatusCode != http.StatusPartialContent || !bytes.Equal(got, payload[bounds[0]:bounds[1]+1]) {
			t.Fatal("protected range bytes changed")
		}
	}
	// CONNECT must dial the same private peer through WireGuard in HTTP + HTTPS mode.
	// The opaque payload stands in for TLS; the proxy must never inspect or rewrite it.
	client, err := net.Dial("tcp", proxy.Host)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	auth := base64.StdEncoding.EncodeToString([]byte("nuvio:" + testToken))
	_, _ = io.WriteString(client, "CONNECT playback.test:8080 HTTP/1.1\r\nHost: playback.test:8080\r\nProxy-Authorization: Basic "+auth+"\r\n\r\n")
	reader := bufio.NewReader(client)
	connected, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil || connected.StatusCode != 200 {
		t.Fatal("protected CONNECT did not reach peer")
	}
	_, _ = io.WriteString(client, "GET /video HTTP/1.1\r\nHost: playback.test\r\nRange: bytes=41-96\r\nConnection: close\r\n\r\n")
	stream, err := http.ReadResponse(reader, &http.Request{Method: http.MethodGet})
	if err != nil {
		t.Fatal("opaque CONNECT stream failed")
	}
	defer stream.Body.Close()
	got, _ := io.ReadAll(stream.Body)
	if stream.StatusCode != http.StatusPartialContent || !bytes.Equal(got, payload[41:97]) {
		t.Fatal("protected CONNECT changed the payload")
	}
}
