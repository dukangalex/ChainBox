package chain

import (
	"context"
	"fmt"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/metadata"
)

func init() {
	// 向 sing-box 原生 Adapter 注册 chain outbound 类型
	adapter.RegisterOutbound("chain", NewOutbound)
}

type Option struct {
	option.OutboundTLSOptions
	Outbounds  []string `json:"outbounds"`
	FailClosed bool     `json:"fail_closed"`
}

type Outbound struct {
	tag          string
	outboundTags []string
	router       adapter.OutboundRouter
	logger       log.ContextLogger
	failClosed   bool
}

func NewOutbound(router adapter.OutboundRouter, logger log.ContextLogger, tag string, options option.Outbound) (adapter.Outbound, error) {
	var opts Option
	if err := options.Options.Decode(&opts); err != nil {
		return nil, fmt.Errorf("chain [%s]: invalid options: %w", tag, err)
	}

	if len(opts.Outbounds) < 2 {
		return nil, fmt.Errorf("chain [%s]: at least 2 outbounds required", tag)
	}

	return &Outbound{
		tag:          tag,
		outboundTags: opts.Outbounds,
		router:       router,
		logger:       logger,
		failClosed:   true,
	}, nil
}

func (c *Outbound) Tag() string { return c.tag }
func (c *Outbound) Type() string { return "chain" }

func (c *Outbound) DialContext(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
	var currentDialer dialer.Dialer

	for i, targetTag := range c.outboundTags {
		targetOutbound, ok := c.router.Outbound(targetTag)
		if !ok {
			// Fail-Closed 原则：链中节点缺失立即报错打断
			return nil, fmt.Errorf("chain [%s] error: node '%s' at index %d missing", c.tag, targetTag, i)
		}

		if currentDialer != nil {
			ctx = dialer.ContextWithDialer(ctx, currentDialer)
		}
		currentDialer = targetOutbound
	}

	conn, err := currentDialer.DialContext(ctx, network, destination)
	if err != nil {
		return nil, fmt.Errorf("chain [%s] handshake failed: %w", c.tag, err)
	}
	return conn, nil
}

func (c *Outbound) ListenPacket(ctx context.Context, destination metadata.Socksaddr) (net.PacketConn, error) {
	lastTag := c.outboundTags[len(c.outboundTags)-1]
	lastOutbound, ok := c.router.Outbound(lastTag)
	if !ok {
		return nil, fmt.Errorf("chain [%s]: last outbound missing", c.tag)
	}
	return lastOutbound.ListenPacket(ctx, destination)
}

func (c *Outbound) Start() error { return nil }
func (c *Outbound) Close() error { return nil }
