// Command smoke checks a running relay: two clients join a fresh room, one broadcasts, the other must receive it.
package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/coder/websocket"
	"github.com/ownspace-in/owntally/relay/ws-relay/tools/signin"
)

func dial(ctx context.Context, base, room string, priv ed25519.PrivateKey) (*websocket.Conn, error) {
	return signin.Dial(ctx, base, room, priv)
}

func main() {
	url := flag.String("url", "wss://relay.ownspace.in/ws", "relay websocket url")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	room := make([]byte, 16)
	_, _ = rand.Read(room)
	// Each client is a real key pair: the relay admits a connection only to the holder of the key it names (M12).
	pubA, privA, _ := ed25519.GenerateKey(rand.Reader)
	_, privB, _ := ed25519.GenerateKey(rand.Reader)
	idA := []byte(pubA)

	start := time.Now()
	a, err := dial(ctx, *url, hex.EncodeToString(room), privA)
	if err != nil {
		fail("client A could not connect: %v", err)
	}
	defer a.CloseNow()
	b, err := dial(ctx, *url, hex.EncodeToString(room), privB)
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
