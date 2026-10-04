package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"errors"
	"os"
	"sync"
	"time"
	"unsafe"

	"github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	C2 "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/clashapi"
	"github.com/sagernet/sing-box/experimental/deprecated"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

type instance struct {
	box    *box.Box
	ctx    context.Context
	cancel context.CancelFunc
}

var (
	mutex   sync.Mutex
	current *instance
)

func baseContext() context.Context {
	return include.Context(service.ContextWith(context.Background(), deprecated.NewStderrManager(log.StdLogger())))
}

func parse(ctx context.Context, content string) (option.Options, error) {
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(content))
	if err != nil {
		return options, err
	}
	if options.Experimental == nil {
		options.Experimental = &option.ExperimentalOptions{}
	}
	if options.Experimental.ClashAPI == nil {
		options.Experimental.ClashAPI = &option.ClashAPIOptions{}
	}
	return options, nil
}

func create(content string, prepare func(option.Options) error) (*instance, error) {
	ctx, cancel := context.WithCancel(baseContext())
	options, err := parse(ctx, content)
	if err != nil {
		cancel()
		return nil, err
	}
	if prepare != nil {
		if err = prepare(options); err != nil {
			cancel()
			return nil, err
		}
	}
	created, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		cancel()
		return nil, err
	}
	return &instance{box: created, ctx: ctx, cancel: cancel}, nil
}

func (i *instance) close() error {
	i.cancel()
	done := make(chan error, 1)
	go func() { done <- i.box.Close() }()
	select {
	case err := <-done:
		return err
	case <-time.After(C2.FatalStopTimeout):
		return errors.New("sing-box did not close in time")
	}
}

func removeStaleAdapters(options option.Options) error {
	for _, inbound := range options.Inbounds {
		tun, isTun := inbound.Options.(*option.TunInboundOptions)
		if !isTun || tun.InterfaceName == "" {
			continue
		}
		if _, err := removeStaleAdapter(tun.InterfaceName); err != nil {
			return E.Cause(err, "remove stale adapter ", tun.InterfaceName)
		}
	}
	return nil
}

func result(err error) *C.char {
	if err == nil {
		return nil
	}
	return C.CString(err.Error())
}

//export frkn_free
func frkn_free(value *C.char) {
	C.free(unsafe.Pointer(value))
}

//export frkn_version
func frkn_version() *C.char {
	return C.CString(C2.Version)
}

//export frkn_check
func frkn_check(config *C.char) *C.char {
	checked, err := create(C.GoString(config), nil)
	if err != nil {
		return result(err)
	}
	checked.cancel()
	return result(checked.box.Close())
}

//export frkn_start
func frkn_start(config *C.char, workDir *C.char) *C.char {
	mutex.Lock()
	defer mutex.Unlock()
	if current != nil {
		return result(errors.New("sing-box is already running"))
	}
	if err := os.Chdir(C.GoString(workDir)); err != nil {
		return result(err)
	}
	started, err := create(C.GoString(config), removeStaleAdapters)
	if err != nil {
		return result(err)
	}
	if err = started.box.Start(); err != nil {
		_ = started.close()
		return result(err)
	}
	current = started
	return nil
}

//export frkn_stop
func frkn_stop() *C.char {
	mutex.Lock()
	defer mutex.Unlock()
	if current == nil {
		return nil
	}
	stopping := current
	current = nil
	return result(stopping.close())
}

func running() *instance {
	mutex.Lock()
	defer mutex.Unlock()
	return current
}

//export frkn_select
func frkn_select(groupTag *C.char, outboundTag *C.char) C.int32_t {
	active := running()
	if active == nil {
		return 0
	}
	outbound, loaded := active.box.Outbound().Outbound(C.GoString(groupTag))
	if !loaded {
		return 0
	}
	selector, isSelector := outbound.(*group.Selector)
	if !isSelector || !selector.SelectOutbound(C.GoString(outboundTag)) {
		return 0
	}
	return 1
}

//export frkn_delay
func frkn_delay(outboundTag *C.char, link *C.char, timeoutMs C.int32_t) C.int32_t {
	active := running()
	if active == nil {
		return -1
	}
	outbound, loaded := active.box.Outbound().Outbound(C.GoString(outboundTag))
	if !loaded {
		return -1
	}
	ctx, cancel := context.WithTimeout(active.ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	delay, err := urltest.URLTest(ctx, C.GoString(link), outbound)
	if err != nil || delay == 0 {
		return -1
	}
	return C.int32_t(delay)
}

//export frkn_traffic
func frkn_traffic(up *C.int64_t, down *C.int64_t) C.int32_t {
	active := running()
	if active == nil {
		return 0
	}
	server, isClash := service.FromContext[adapter.ClashServer](active.ctx).(*clashapi.Server)
	if !isClash {
		return 0
	}
	totalUp, totalDown := server.TrafficManager().Total()
	*up = C.int64_t(totalUp)
	*down = C.int64_t(totalDown)
	return 1
}

func main() {}
