// Command idleconns opens many idle connections to a relay, for measuring memory use.
package main

import (
	"context"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"time"

	"github.com/coder/websocket"
)

func main() {
	url := flag.String("url", "ws://127.0.0.1:8080/ws", "relay websocket url")
	n := flag.Int("n", 100, "connections")
	hold := flag.Duration("hold", 20*time.Second, "how long to hold them open")
	flag.Parse()

	ctx := context.Background()
	for i := 0; i < *n; i++ {
		room := hex.EncodeToString(make([]byte, 16))
		id := make([]byte, 32)
		id[0], id[1] = byte(i>>8), byte(i)
		room = fmt.Sprintf("%032x", i/20) // 20 clients per room
		c, _, err := websocket.Dial(ctx, fmt.Sprintf("%s?room=%s&id=%s", *url, room, hex.EncodeToString(id)), nil)
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
