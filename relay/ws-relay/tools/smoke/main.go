// Command smoke checks a running relay: two clients join a fresh room, one broadcasts, the other must receive it.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/coder/websocket"
)

func dial(ctx context.Context, base, room string, id []byte) (*websocket.Conn, error) {
	c, _, err := websocket.Dial(ctx, fmt.Sprintf("%s?room=%s&id=%s", base, room, hex.EncodeToString(id)), nil)
	return c, err
}

func main() {
	url := flag.String("url", "wss://relay.ownspace.in/ws", "relay websocket url")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	room := make([]byte, 16)
	idA, idB := make([]byte, 32), make([]byte, 32)
	for _, b := range [][]byte{room, idA, idB} {
		_, _ = rand.Read(b)
	}

	start := time.Now()
	a, err := dial(ctx, *url, hex.EncodeToString(room), idA)
	if err != nil {
		fail("client A could not connect: %v", err)
	}
	defer a.CloseNow()
	b, err := dial(ctx, *url, hex.EncodeToString(room), idB)
	if err != nil {
		fail("client B could not connect: %v", err)
	}
	defer b.CloseNow()
	connected := time.Since(start)

	time.Sleep(200 * time.Millisecond)
	sent := time.Now()
	if err := a.Write(ctx, websocket.MessageBinary, append([]byte{0}, []byte("smoke")...)); err != nil {
		fail("send failed: %v", err)
	}
	_, data, err := b.Read(ctx)
	if err != nil {
		fail("B did not receive the message: %v", err)
	}
	if len(data) != 32+5 || string(data[32:]) != "smoke" || hex.EncodeToString(data[:32]) != hex.EncodeToString(idA) {
		fail("B received something unexpected: %x", data)
	}
	fmt.Printf("OK  connect=%v  roundtrip=%v\n", connected.Round(time.Millisecond), time.Since(sent).Round(time.Millisecond))
}

func fail(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "FAIL "+format+"\n", args...)
	os.Exit(1)
}
