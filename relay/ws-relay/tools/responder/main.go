// Command responder plays "phone B" for the M0 spike: it joins a room and answers the probe messages
// that the spike app (or app/sync Probe) sends. It lets one phone be tested without a second phone.
package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"flag"
	"fmt"
	"log"

	"github.com/coder/websocket"
)

func main() {
	url := flag.String("url", "ws://127.0.0.1:8080/ws", "relay websocket url")
	roomName := flag.String("room", "spike-1", "room name, as typed in the app")
	flag.Parse()

	sum := sha256.Sum256([]byte(*roomName))
	room := hex.EncodeToString(sum[:16])
	self := bytes.Repeat([]byte{0xB2}, 32)

	ctx := context.Background()
	conn, _, err := websocket.Dial(ctx, fmt.Sprintf("%s?room=%s&id=%s", *url, room, hex.EncodeToString(self)), nil)
	if err != nil {
		log.Fatal(err)
	}
	conn.SetReadLimit(1 << 20)
	log.Printf("responder joined room %q as B", *roomName)

	for {
		_, frame, err := conn.Read(ctx)
		if err != nil {
			log.Fatal(err)
		}
		if len(frame) < 32+9 || frame[32] != 0 {
			log.Printf("other message, %d bytes", len(frame))
			continue
		}
		sender, msg := frame[:32], frame[32:]
		seq := binary.BigEndian.Uint32(msg[1:5])
		size := binary.BigEndian.Uint32(msg[5:9])
		body := msg[9:]
		ok := uint32(len(body)) == size
		for i := range body {
			if !ok {
				break
			}
			if body[i] != byte(int32(seq)*31+int32(i)*7) {
				ok = false
			}
		}
		log.Printf("got #%d, %d bytes, ok=%v", seq, size, ok)

		ack := make([]byte, 0, 1+32+6)
		ack = append(ack, 1)
		ack = append(ack, sender...)
		ack = append(ack, 1)
		ack = binary.BigEndian.AppendUint32(ack, seq)
		if ok {
			ack = append(ack, 1)
		} else {
			ack = append(ack, 0)
		}
		if err := conn.Write(ctx, websocket.MessageBinary, ack); err != nil {
			log.Fatal(err)
		}
	}
}
