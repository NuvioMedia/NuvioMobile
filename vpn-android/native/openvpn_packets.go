//go:build android || linux

package main

import (
	"encoding/json"
	"errors"
	"net"
	"os"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// The OpenVPN core treats one end of a datagram socketpair as its TUN fd.
// The other end exchanges IP packets with the same private IP stack used for
// playback. No Android VPN interface, system routes, or direct dialer are involved.
type packetLink struct {
	device   tun.Device
	socket   net.Conn
	readDone chan struct{}
}

func (p *packetLink) Close() error {
	p.socket.Close()
	<-p.readDone
	return p.device.Close()
}

func (t *httpTunnel) attachOpenVPN(encoded string) (int, error) {
	var config struct {
		Addresses, DNS []string
		MTU            int
	}
	if err := json.Unmarshal([]byte(encoded), &config); err != nil || config.MTU < 576 || config.MTU > 9000 {
		return -1, errors.New("invalid packet link")
	}
	ips, err := parseAddresses(config.Addresses)
	if err != nil {
		return -1, err
	}
	dns, err := parseAddresses(config.DNS)
	if err != nil {
		return -1, err
	}
	pair, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	file := os.NewFile(uintptr(pair[0]), "openvpn-netstack")
	socket, err := net.FileConn(file)
	file.Close()
	if err != nil {
		unix.Close(pair[1])
		return -1, err
	}
	dev, network, err := netstack.CreateNetTUN(ips, dns, config.MTU)
	if err != nil {
		socket.Close()
		unix.Close(pair[1])
		return -1, err
	}
	link := &packetLink{device: dev, socket: socket, readDone: make(chan struct{})}
	t.mu.Lock()
	if t.closed || t.device != nil {
		t.mu.Unlock()
		socket.Close()
		dev.Close()
		unix.Close(pair[1])
		return -1, errors.New("session changed")
	}
	previous, oldTransport := t.packetLink, t.transport
	t.packetLink, t.transport = link, privateTransport(network.DialContext)
	t.mu.Unlock()
	if oldTransport != nil {
		oldTransport.CloseIdleConnections()
	}
	if previous != nil {
		previous.Close()
	}
	go link.readVPN()
	go link.writeVPN()
	return pair[1], nil
}

func (p *packetLink) readVPN() {
	defer close(p.readDone)
	buf := make([]byte, 65535)
	for {
		n, err := p.socket.Read(buf)
		if err != nil || n == 0 {
			return
		}
		if _, err = p.device.Write([][]byte{buf[:n]}, 0); err != nil {
			return
		}
	}
}
func (p *packetLink) writeVPN() {
	buffers := [][]byte{make([]byte, 65535)}
	sizes := make([]int, 1)
	for {
		n, err := p.device.Read(buffers, sizes, 0)
		if err != nil || n == 0 {
			return
		}
		// Keep draining netstack notifications while Close shuts down the socket and NIC.
		_, _ = p.socket.Write(buffers[0][:sizes[0]])
	}
}
