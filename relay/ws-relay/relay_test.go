package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const roomA = "00112233445566778899aabbccddeeff"
const roomB = "ffeeddccbbaa99887766554433221100"

func keyHex(n byte) string   { return strings.Repeat(fmt.Sprintf("%02x", n), 32) }
func keyBytes(n byte) []byte { return bytes.Repeat([]byte{n}, 32) }

func startServer(t *testing.T, maxPerRoom int) (*server, *httptest.Server) {
	t.Helper()
	s := newServer(maxPerRoom, false, defaultConnectsPerMin)
	ts := httptest.NewServer(s.routes())
	t.Cleanup(ts.Close)
	return s, ts
}

func dial(t *testing.T, ts *httptest.Server, room string, id byte) *websocket.Conn {
	t.Helper()
	conn, _, err := dialErr(ts, room, id)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	return conn
}

func dialErr(ts *httptest.Server, room string, id byte) (*websocket.Conn, *http.Response, error) {
	url := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws?room=" + room + "&id=" + keyHex(id)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return websocket.Dial(ctx, url, nil)
}

func read(t *testing.T, c *websocket.Conn) []byte {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, data, err := c.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return data
}

func expectSilence(t *testing.T, c *websocket.Conn) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	if _, data, err := c.Read(ctx); err == nil {
		t.Fatalf("expected no message, got %x", data)
	}
}

func write(t *testing.T, c *websocket.Conn, frame []byte) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := c.Write(ctx, websocket.MessageBinary, frame); err != nil {
		t.Fatalf("write: %v", err)
	}
}

// waitJoined makes sure the relay has registered all clients before sending.
func waitJoined(t *testing.T, s *server, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, n := s.hub.counts(); n == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("clients never reached %d", want)
}

func TestBroadcastReachesEveryoneElse(t *testing.T) {
	s, ts := startServer(t, 20)
	a, b, c := dial(t, ts, roomA, 1), dial(t, ts, roomA, 2), dial(t, ts, roomA, 3)
	waitJoined(t, s, 3)

	write(t, a, append([]byte{modeBroadcast}, []byte("hello")...))

	want := append(keyBytes(1), []byte("hello")...)
	if got := read(t, b); !bytes.Equal(got, want) {
		t.Fatalf("b got %x", got)
	}
	if got := read(t, c); !bytes.Equal(got, want) {
		t.Fatalf("c got %x", got)
	}
	expectSilence(t, a)
}

func TestTargetedReachesOnlyTarget(t *testing.T) {
	s, ts := startServer(t, 20)
	a, b, c := dial(t, ts, roomA, 1), dial(t, ts, roomA, 2), dial(t, ts, roomA, 3)
	waitJoined(t, s, 3)

	frame := append([]byte{modeTargeted}, keyBytes(3)...)
	write(t, a, append(frame, []byte("secret")...))

	if got := read(t, c); !bytes.Equal(got, append(keyBytes(1), []byte("secret")...)) {
		t.Fatalf("c got %x", got)
	}
	expectSilence(t, b)
}

func TestTargetedToUnknownKeyIsDropped(t *testing.T) {
	s, ts := startServer(t, 20)
	a, b := dial(t, ts, roomA, 1), dial(t, ts, roomA, 2)
	waitJoined(t, s, 2)

	// unknown target first, then a broadcast: b must receive only the broadcast, and a stays connected
	frame := append([]byte{modeTargeted}, keyBytes(9)...)
	write(t, a, append(frame, 'x'))
	write(t, a, []byte{modeBroadcast, 'y'})
	got := read(t, b)
	if !bytes.Equal(got, append(keyBytes(1), 'y')) {
		t.Fatalf("b got %x", got)
	}
}

func TestRoomsAreIsolated(t *testing.T) {
	s, ts := startServer(t, 20)
	a, b := dial(t, ts, roomA, 1), dial(t, ts, roomB, 2)
	waitJoined(t, s, 2)

	write(t, a, []byte{modeBroadcast, 'x'})
	expectSilence(t, b)
}

func TestFrameOverLimitClosesConnection(t *testing.T) {
	s, ts := startServer(t, 20)
	a := dial(t, ts, roomA, 1)
	waitJoined(t, s, 1)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = a.Write(ctx, websocket.MessageBinary, make([]byte, maxFrame+1))
	if _, _, err := a.Read(ctx); err == nil {
		t.Fatal("expected the relay to close the connection")
	}
	waitJoined(t, s, 0)
}

func TestFrameAtLimitIsForwarded(t *testing.T) {
	s, ts := startServer(t, 20)
	a, b := dial(t, ts, roomA, 1), dial(t, ts, roomA, 2)
	waitJoined(t, s, 2)
	b.SetReadLimit(maxFrame + 64)

	frame := make([]byte, maxFrame)
	frame[0] = modeBroadcast
	write(t, a, frame)
	if got := read(t, b); len(got) != 32+maxFrame-1 {
		t.Fatalf("got %d bytes", len(got))
	}
}

func TestRoomFullIsRejected(t *testing.T) {
	s, ts := startServer(t, 3)
	for i := byte(1); i <= 3; i++ {
		dial(t, ts, roomA, i)
	}
	waitJoined(t, s, 3)

	_, resp, err := dialErr(ts, roomA, 4)
	if err == nil {
		t.Fatal("expected the fourth client to be rejected")
	}
	if resp == nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("want 503, got %v", resp)
	}
}

func TestSameIdReplacesOldConnection(t *testing.T) {
	s, ts := startServer(t, 20)
	old := dial(t, ts, roomA, 1)
	other := dial(t, ts, roomA, 2)
	waitJoined(t, s, 2)

	fresh := dial(t, ts, roomA, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, _, err := old.Read(ctx); err == nil {
		t.Fatal("old connection should have been closed")
	}
	waitJoined(t, s, 2)

	write(t, other, []byte{modeBroadcast, 'z'})
	if got := read(t, fresh); !bytes.HasSuffix(got, []byte("z")) {
		t.Fatalf("fresh got %x", got)
	}
}

func TestBadParametersRejected(t *testing.T) {
	_, ts := startServer(t, 20)
	for _, q := range []string{
		"room=zz&id=" + keyHex(1),
		"room=" + roomA + "&id=abcd",
		"room=" + hex.EncodeToString([]byte("short")) + "&id=" + keyHex(1),
		"",
	} {
		resp, err := http.Get(ts.URL + "/ws?" + q)
		if err != nil {
			t.Fatal(err)
		}
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("%q: want 400, got %d", q, resp.StatusCode)
		}
	}
}

func TestTextFramesAndBadModesClose(t *testing.T) {
	s, ts := startServer(t, 20)
	a := dial(t, ts, roomA, 1)
	waitJoined(t, s, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = a.Write(ctx, websocket.MessageText, []byte("hi"))
	if _, _, err := a.Read(ctx); err == nil {
		t.Fatal("expected close after a text frame")
	}

	b := dial(t, ts, roomA, 2)
	waitJoined(t, s, 1)
	write(t, b, []byte{7, 'x'})
	if _, _, err := b.Read(ctx); err == nil {
		t.Fatal("expected close after an unknown mode")
	}
}

func TestConnectRateLimitPerAddress(t *testing.T) {
	_, ts := startServer(t, 1000)
	limited := false
	for i := 0; i < defaultConnectsPerMin+10; i++ {
		_, resp, err := dialErr(ts, roomA, byte(i%250))
		if err != nil && resp != nil && resp.StatusCode == http.StatusTooManyRequests {
			limited = true
			break
		}
	}
	if !limited {
		t.Fatal("expected a 429 after too many connects")
	}
}

func TestIdleClientIsClosed(t *testing.T) {
	s, ts := startServer(t, 20)
	s.pingEvery = 50 * time.Millisecond
	s.idleAfter = 200 * time.Millisecond
	s.pongWait = 100 * time.Millisecond

	// a client that never reads cannot answer pings
	conn := dial(t, ts, roomA, 1)
	_ = conn
	waitJoined(t, s, 1)
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if _, n := s.hub.counts(); n == 0 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("idle client was not closed")
}

func TestHealthz(t *testing.T) {
	_, ts := startServer(t, 20)
	resp, err := http.Get(ts.URL + "/healthz")
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("healthz: %v %v", err, resp)
	}
}

func TestResponsiveIdleClientStaysConnected(t *testing.T) {
	s, ts := startServer(t, 20)
	s.pingEvery = 50 * time.Millisecond
	s.idleAfter = 200 * time.Millisecond
	s.pongWait = 100 * time.Millisecond

	conn := dial(t, ts, roomA, 1)
	go func() {
		for {
			if _, _, err := conn.Read(context.Background()); err != nil {
				return
			}
		}
	}()
	waitJoined(t, s, 1)
	time.Sleep(700 * time.Millisecond)
	if _, n := s.hub.counts(); n != 1 {
		t.Fatalf("responsive client was closed, clients=%d", n)
	}
}
