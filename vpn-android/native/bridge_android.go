//go:build android

package main

/*
#include <stdlib.h>
int nuvioPreparePlayback(const char *address, long long request);
*/
import "C"

import (
	"context"
	"sync"
	"sync/atomic"
	"unsafe"
)

var nativeMu sync.Mutex
var nativeNext int64
var nativeTunnels = map[int64]*httpTunnel{}

var requestContexts sync.Map
var nextRequest atomic.Int64

func preparePlayback(ctx context.Context, address string) bool {
	id := nextRequest.Add(1)
	requestContexts.Store(id, ctx)
	defer requestContexts.Delete(id)
	text := C.CString(address)
	defer C.free(unsafe.Pointer(text))
	return C.nuvioPreparePlayback(text, C.longlong(id)) != 0
}

//export nuvioRequestCancelled
func nuvioRequestCancelled(id C.longlong) C.int {
	value, ok := requestContexts.Load(int64(id))
	if !ok || value.(context.Context).Err() != nil {
		return 1
	}
	return 0
}

func nativeTunnel(id int64) *httpTunnel {
	nativeMu.Lock()
	defer nativeMu.Unlock()
	return nativeTunnels[id]
}

//export nuvioStart
func nuvioStart(encoded *C.char) C.longlong {
	nativeMu.Lock()
	defer nativeMu.Unlock()
	if len(nativeTunnels) != 0 {
		return 0
	}
	tunnel, err := newHTTPTunnel(C.GoString(encoded))
	if err != nil {
		return 0
	}
	nativeNext++
	nativeTunnels[nativeNext] = tunnel
	return C.longlong(nativeNext)
}

//export nuvioStop
func nuvioStop(id C.longlong) {
	nativeMu.Lock()
	tunnel := nativeTunnels[int64(id)]
	delete(nativeTunnels, int64(id))
	nativeMu.Unlock()
	if tunnel != nil {
		tunnel.close()
	}
}

//export nuvioPort
func nuvioPort(id C.longlong) C.int {
	tunnel := nativeTunnel(int64(id))
	if tunnel == nil {
		return 0
	}
	return C.int(tunnel.port())
}

//export nuvioConfigure
func nuvioConfigure(id C.longlong, encoded *C.char) C.int {
	tunnel := nativeTunnel(int64(id))
	if tunnel == nil {
		return 0
	}
	input, err := decodeInput(C.GoString(encoded))
	if err != nil || tunnel.configure(input) != nil {
		return 0
	}
	return 1
}

//export nuvioStats
func nuvioStats(id C.longlong, output *C.longlong) {
	values := (*[3]C.longlong)(unsafe.Pointer(output))
	*values = [3]C.longlong{}
	if tunnel := nativeTunnel(int64(id)); tunnel != nil {
		rx, tx, handshake := tunnel.stats()
		*values = [3]C.longlong{C.longlong(rx), C.longlong(tx), C.longlong(handshake)}
	}
}

//export nuvioProbe
func nuvioProbe(id C.longlong) C.int {
	tunnel := nativeTunnel(int64(id))
	if tunnel != nil && tunnel.probe() {
		return 1
	}
	return 0
}

//export nuvioEgress
func nuvioEgress(id C.longlong) *C.char {
	if tunnel := nativeTunnel(int64(id)); tunnel != nil {
		if address := tunnel.egress(); address != "" {
			return C.CString(address)
		}
	}
	return nil
}

//export nuvioAttachOpenVPN
func nuvioAttachOpenVPN(id C.longlong, encoded *C.char) C.int {
	if tunnel := nativeTunnel(int64(id)); tunnel != nil {
		fd, err := tunnel.attachOpenVPN(C.GoString(encoded))
		if err == nil {
			return C.int(fd)
		}
	}
	return -1
}
