// Package signin connects to the relay and signs in (M12, docs/CRYPTO.md section 7a), for the tools in this folder. It writes the exchange out on its own,
// separately from the relay's code, so the tools also check that the relay and a second implementation agree.
package signin

import (
	"context"
	"crypto/ed25519"
	"encoding/hex"
	"fmt"

	"github.com/coder/websocket"
)

// Dial opens the WebSocket for the key of priv in the room and makes the sign-in exchange. The returned connection is in the room.
func Dial(ctx context.Context, base, roomHex string, priv ed25519.PrivateKey) (*websocket.Conn, error) {
	pub := priv.Public().(ed25519.PublicKey)
	c, _, err := websocket.Dial(ctx, fmt.Sprintf("%s?room=%s&id=%s", base, roomHex, hex.EncodeToString(pub)), nil)
	if err != nil {
		return nil, err
	}
	_, challenge, err := c.Read(ctx)
	if err != nil {
		return nil, fmt.Errorf("no challenge: %w", err)
	}
	if len(challenge) != 33 || challenge[0] != 0x01 {
		_ = c.CloseNow()
		return nil, fmt.Errorf("not a challenge: %x", challenge)
	}
	room, err := hex.DecodeString(roomHex)
	if err != nil {
		_ = c.CloseNow()
		return nil, err
	}
	msg := append([]byte("owntally/v1/relay-auth"), byte(len(room)))
	msg = append(msg, room...)
	msg = append(msg, pub...)
	msg = append(msg, challenge[1:]...)
	if err := c.Write(ctx, websocket.MessageBinary, append([]byte{0x02}, ed25519.Sign(priv, msg)...)); err != nil {
		return nil, err
	}
	_, ready, err := c.Read(ctx)
	if err != nil {
		return nil, fmt.Errorf("not admitted: %w", err)
	}
	if len(ready) != 1 || ready[0] != 0x03 {
		_ = c.CloseNow()
		return nil, fmt.Errorf("not ready: %x", ready)
	}
	return c, nil
}
