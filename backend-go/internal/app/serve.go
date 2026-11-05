package app

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"sync"
	"time"
)

// Serve接管显式监听器（包括拒绝参数时关闭监听器），不自行绑定公网或发现端口。
// monitoring开启时必须提供不同端口的回环管理监听器，否则必须为nil。
// 每个Foundation只Serve一次；停止两个入口后再关闭Foundation，不能重复启动。
func (f *Foundation) Serve(ctx context.Context, business, management net.Listener, shutdownTimeout time.Duration) error {
	if business != nil {
		defer business.Close()
	}
	if management != nil {
		defer management.Close()
	}
	if ctx == nil || business == nil || shutdownTimeout <= 0 || (f.management != nil) != (management != nil) {
		return errors.New("基础HTTP启动参数或独立管理监听器无效")
	}
	port, ok := permittedListener(business, f.containerNetwork)
	if !ok {
		return errors.New("基础HTTP监听器必须显式绑定回环TCP地址")
	}
	if management != nil {
		managementPort, ok := permittedListener(management, f.containerNetwork)
		if !ok || managementPort == port {
			return errors.New("管理监听器必须绑定独立回环TCP端口")
		}
	}
	f.mu.Lock()
	if f.closing || f.served {
		f.mu.Unlock()
		return errors.New("基础HTTP已经启动或正在关闭")
	}
	f.served = true
	f.mu.Unlock()
	type serving struct {
		server   *http.Server
		listener net.Listener
	}
	makeServer := func(handler http.Handler, listener net.Listener) serving {
		return serving{server: &http.Server{
			Handler: handler, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 1 << 20,
			BaseContext: func(net.Listener) context.Context { return f.ctx },
			// net/http默认日志可能包含原始异常/堆栈；内部边界已记录脱敏错误。
			ErrorLog: log.New(io.Discard, "", 0),
		}, listener: listener}
	}
	servers := []serving{makeServer(f.handler, business)}
	if management != nil {
		servers = append(servers, makeServer(f.management, management))
	}
	results := make(chan error, len(servers))
	for _, entry := range servers {
		go func() { results <- entry.server.Serve(entry.listener) }()
	}
	var failure error
	select {
	case <-ctx.Done():
	case <-f.ctx.Done():
	case err := <-results:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			failure = errors.New("基础HTTP监听失败")
		}
	}
	// End long-lived reminder responses before HTTP waits for active handlers.
	// Keep the pool and other workers alive until ordinary requests have drained.
	if f.p5 != nil && f.p5.stream != nil {
		f.p5.stream.Close()
	}
	shutdown, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	closed := make(chan error, len(servers))
	var wg sync.WaitGroup
	for _, entry := range servers {
		wg.Go(func() {
			err := entry.server.Shutdown(shutdown)
			if err != nil {
				_ = entry.server.Close()
			}
			closed <- err
		})
	}
	wg.Wait()
	close(closed)
	for err := range closed {
		failure = errors.Join(failure, err)
	}
	if err := f.Close(shutdown); err != nil {
		failure = errors.Join(failure, err)
		// 宽限到期后已取消工作；另给租约释放和自有池清理一个有限窗口。
		cleanup, stop := context.WithTimeout(context.Background(), 5*time.Second)
		failure = errors.Join(failure, f.Close(cleanup))
		stop()
	}
	return failure
}

func loopbackListener(listener net.Listener) (int, bool) {
	return permittedListener(listener, false)
}

// 非回环只接受已验证的P8容器组合显式绑定通配地址；不会信任Host或转发头。
func permittedListener(listener net.Listener, container bool) (int, bool) {
	address, ok := listener.Addr().(*net.TCPAddr)
	if !ok || address == nil {
		return 0, false
	}
	return address.Port, address.Port > 0 && (address.IP.IsLoopback() || container && address.IP.IsUnspecified())
}
