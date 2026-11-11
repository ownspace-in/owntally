// Command idleconns opens many idle connections to a relay, for measuring memory use.
package main

import (
	"context"
	"crypto/ed25519"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"time"

	"github.com/ownspace-in/owntally/relay/ws-relay/tools/signin"
)

func main() {
	url := flag.String("url", "ws://127.0.0.1:8080/ws", "relay websocket url")
	n := flag.Int("n", 100, "connections")
	hold := flag.Duration("hold", 20*time.Second, "how long to hold them open")
	flag.Parse()

	ctx := context.Background()
	for i := 0; i < *n; i++ {
		room := hex.EncodeToString(make([]byte, 16))
		seed := make([]byte, 32)
		seed[0], seed[1] = byte(i>>8), byte(i)
		room = fmt.Sprintf("%032x", i/20)                                    // 20 clients per room
		c, err := signin.Dial(ctx, *url, room, ed25519.NewKeyFromSeed(seed)) // a real key for each: the relay checks (M12)
		if err != nil {
			log.Fatalf("conn %d: %v", i, err)
		}
		go func() {
			for {
				if _, _, err := c.Read(ctx); err != nil {
					return
				}
			}
		}()
	}
	fmt.Printf("holding %d connections\n", *n)
	time.Sleep(*hold)
}
