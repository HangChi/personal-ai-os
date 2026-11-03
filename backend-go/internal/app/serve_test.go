package app

import (
	"context"
	"net"
	"net/http"
	"testing"
	"time"
)

type advertisedListener struct {
	net.Listener
	address net.Addr
}

func (l advertisedListener) Addr() net.Addr { return l.address }

func TestFoundationServeRejectsMixedOrPublicListeners(t *testing.T) {
	for _, test := range []struct {
		name                                     string
		monitoring, management, public, samePort bool
	}{
		{"missing management", true, false, false, false},
		{"unexpected management", false, true, false, false},
		{"public address", false, false, true, false},
		{"shared port", true, true, false, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			business, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			var management net.Listener
			if test.management {
				management, err = net.Listen("tcp", "127.0.0.1:0")
				if err != nil {
					business.Close()
					t.Fatal(err)
				}
			}
			f := &Foundation{}
			if test.monitoring {
				f.management = http.NotFoundHandler()
			}
			if test.public {
				business = advertisedListener{business, &net.TCPAddr{IP: net.IPv4zero, Port: 12345}}
			}
			if test.samePort {
				management = advertisedListener{management, business.Addr()}
			}
			if err := f.Serve(context.Background(), business, management, time.Second); err == nil {
				t.Fatal("不安全监听器未拒绝")
			}
			if _, err := business.Accept(); err == nil {
				t.Fatal("拒绝后未关闭已接管监听器")
			}
			if management != nil {
				if _, err := management.Accept(); err == nil {
					t.Fatal("拒绝后未关闭管理监听器")
				}
			}
		})
	}
}
