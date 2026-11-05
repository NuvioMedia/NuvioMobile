//go:build !android

package main

import "context"

// Host tests exercise the proxy/cryptographic transport independently of Android consent.
func preparePlayback(context.Context, string) bool { return true }
