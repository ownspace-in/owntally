// Command ws-relay forwards opaque encrypted frames between members of a group.
// It stores nothing and never reads payloads. See README.md.
package main

import (
	"context"
	"encoding/hex"
	"flag"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"golang.org/x/time/rate"
)

const (
	maxFrame              = 64 * 1024
	pingEvery             = 30 * time.Second
	pongWait              = 10 * time.Second
	idleGrace             = 90 * time.Second
	sendQueue             = 64
	msgRate               = 50
	msgBurst              = 100
	defaultConnectsPerMin = 30
)

type server struct {
	hub            *hub
	trustProxy     bool
	mu             sync.Mutex
	perIP          map[string]*rate.Limiter
	idleAfter      time.Duration
	pingEvery      time.Duration
	pongWait       time.Duration
	connectsPerMin int
}

func newServer(maxPerRoom int, trustProxy bool, connectsPerMin int) *server {
	return &server{
		hub:            newHub(maxPerRoom),
		trustProxy:     trustProxy,
		perIP:          make(map[string]*rate.Limiter),
		idleAfter:      idleGrace,
		pingEvery:      pingEvery,
		pongWait:       pongWait,
		connectsPerMin: connectsPerMin,
	}
}

func (s *server) clientIP(r *http.Request) string {
	if s.trustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			return strings.TrimSpace(strings.Split(xff, ",")[0])
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (s *server) allowConnect(ip string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	l, ok := s.perIP[ip]
	if !ok {
		l = rate.NewLimiter(rate.Every(time.Minute/time.Duration(s.connectsPerMin)), s.connectsPerMin)
		s.perIP[ip] = l
	}
	return l.Allow()
}

func (s *server) purgeLimiters() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for ip, l := range s.perIP {
		if l.Tokens() >= float64(s.connectsPerMin)-0.5 {
			delete(s.perIP, ip)
		}
	}
}

func parseHex(s string, minBytes, maxBytes int) ([]byte, bool) {
	b, err := hex.DecodeString(s)
	if err != nil || len(b) < minBytes || len(b) > maxBytes {
		return nil, false
	}
	return b, true
}

func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	roomBytes, ok1 := parseHex(q.Get("room"), 16, 32)
	idBytes, ok2 := parseHex(q.Get("id"), 32, 32)
	if !ok1 || !ok2 {
		http.Error(w, "bad room or id", http.StatusBadRequest)
		return
	}
	room := hex.EncodeToString(roomBytes)
	var id key
	copy(id[:], idBytes)

	if !s.allowConnect(s.clientIP(r)) {
		http.Error(w, "too many connections", http.StatusTooManyRequests)
		return
	}
	if !s.hub.hasSpace(room, id) {
		http.Error(w, "room full", http.StatusServiceUnavailable)
		return
	}

	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	conn.SetReadLimit(maxFrame)

	ctx, cancel := context.WithCancel(context.Background())
	c := &client{id: id, send: make(chan []byte, sendQueue), conn: conn, cancel: cancel}
	replaced, joined := s.hub.join(room, c)
	if !joined {
		c.kick(websocket.StatusTryAgainLater, "room full")
		return
	}
	if replaced != nil {
		replaced.kick(websocket.StatusPolicyViolation, "replaced by a new connection")
	}
	defer func() {
		s.hub.leave(room, c)
		c.kick(websocket.StatusNormalClosure, "")
	}()

	go s.writeLoop(ctx, c)
	go s.pingLoop(ctx, c)

	limiter := rate.NewLimiter(msgRate, msgBurst)
	for {
		typ, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		if typ != websocket.MessageBinary {
			c.kick(websocket.StatusUnsupportedData, "binary frames only")
			return
		}
		if !limiter.Allow() {
			c.kick(websocket.StatusPolicyViolation, "rate limit")
			return
		}
		if !s.hub.forward(room, c, data) {
			c.kick(websocket.StatusProtocolError, "bad frame")
			return
		}
	}
}

func (s *server) writeLoop(ctx context.Context, c *client) {
	for {
		select {
		case <-ctx.Done():
			return
		case msg := <-c.send:
			wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := c.conn.Write(wctx, websocket.MessageBinary, msg)
			cancel()
			if err != nil {
				c.kick(websocket.StatusInternalError, "write failed")
				return
			}
		}
	}
}

// pingLoop closes the connection when the client stops answering pings for idleAfter.
func (s *server) pingLoop(ctx context.Context, c *client) {
	t := time.NewTicker(s.pingEvery)
	defer t.Stop()
	lastOK := time.Now()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			pctx, cancel := context.WithTimeout(ctx, s.pongWait)
			err := c.conn.Ping(pctx)
			cancel()
			if err == nil {
				lastOK = time.Now()
			} else if time.Since(lastOK) >= s.idleAfter {
				c.kick(websocket.StatusGoingAway, "idle")
				return
			}
		}
	}
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok\n"))
	})
	return mux
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8080", "listen address (put a TLS proxy in front)")
	maxPerRoom := flag.Int("max-per-room", 20, "maximum clients per room")
	trustProxy := flag.Bool("trust-proxy", false, "use X-Forwarded-For for rate limiting")
	connects := flag.Int("connects-per-min", defaultConnectsPerMin, "new connections allowed per minute from one address")
	flag.Parse()

	s := newServer(*maxPerRoom, *trustProxy, *connects)
	go func() {
		for range time.Tick(5 * time.Minute) {
			s.purgeLimiters()
		}
	}()
	go func() {
		for range time.Tick(time.Minute) {
			rooms, clients := s.hub.counts()
			log.Printf("stats rooms=%d clients=%d forwarded=%d dropped=%d",
				rooms, clients, s.hub.forwarded.Load(), s.hub.dropped.Load())
		}
	}()

	srv := &http.Server{Addr: *addr, Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second}
	log.Printf("ws-relay listening on %s", *addr)
	log.Fatal(srv.ListenAndServe())
}
