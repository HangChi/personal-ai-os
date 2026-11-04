package app

import (
	"net"
	"testing"
)

type addressListener struct {
	net.Listener
	address net.Addr
}

func (l addressListener) Addr() net.Addr { return l.address }

func TestP8ContainerListenerBoundary(t *testing.T) {
	for _, tc := range []struct {
		ip                 string
		container, allowed bool
	}{
		{"127.0.0.1", false, true}, {"::1", false, true}, {"0.0.0.0", false, false}, {"::", false, false},
		{"0.0.0.0", true, true}, {"::", true, true}, {"192.0.2.1", true, false},
	} {
		listener := addressListener{address: &net.TCPAddr{IP: net.ParseIP(tc.ip), Port: 8081}}
		if port, ok := permittedListener(listener, tc.container); ok != tc.allowed || port != 8081 {
			t.Fatal(tc, port, ok)
		}
	}
	if _, ok := permittedListener(addressListener{address: &net.UnixAddr{Name: "fixture", Net: "unix"}}, true); ok {
		t.Fatal("non TCP accepted")
	}
}
