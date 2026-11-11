package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

// Relay sign-in (M12, finding N3; docs/CRYPTO.md section 7a). The relay takes `id` from the address as a claim and admits the connection to the
// room only after the client proves it holds that key:
//
//	relay -> client   [0x01][nonce, 32 random bytes]
//	client -> relay   [0x02][Ed25519 signature, 64 bytes] over authMessage
//	relay -> client   [0x03] (ready), or the connection is closed with 4401 (not the holder) or 4408 (no answer in time)
//
// Until [0x03] the client is in no room: nothing is forwarded to it or from it.
const (
	msgChallenge = 0x01
	msgAnswer    = 0x02
	msgReady     = 0x03

	statusNotHolder   = websocket.StatusCode(4401)
	statusAuthTimeout = websocket.StatusCode(4408)

	defaultAuthTimeout = 5 * time.Second
	nonceSize          = 32
	answerSize         = 1 + ed25519.SignatureSize
)

var authLabel = []byte("owntally/v1/relay-auth")

// authMessage is what the member signs: the label, the room's length as one byte, the room, the member key and the nonce.
func authMessage(room []byte, id key, nonce []byte) []byte {
	m := make([]byte, 0, len(authLabel)+1+len(room)+len(id)+len(nonce))
	m = append(m, authLabel...)
	m = append(m, byte(len(room)))
	m = append(m, room...)
	m = append(m, id[:]...)
	return append(m, nonce...)
}

// The seven encodings of the Ed25519 points of small order (the list libsodium uses, the same as the app's WeakKeys). A signature by such a key can
// verify for messages it never saw, so they belong to nobody and are refused before anything is checked. The top bit of the last byte is ignored.
var weakKeys = func() [][]byte {
	var list [][]byte
	for _, h := range []string{
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
		"c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
		"ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
		"edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
		"eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	} {
		b, err := hex.DecodeString(h)
		if err != nil || len(b) != 32 {
			panic("bad weak key list")
		}
		list = append(list, b)
	}
	return list
}()

func isWeakKey(id key) bool {
	masked := id
	masked[31] &= 0x7f
	for _, w := range weakKeys {
		if string(w) == string(masked[:]) {
			return true
		}
	}
	return false
}

// verifyAuth reports whether sig is a good signature by id over this room and nonce. It never panics on bad input.
func verifyAuth(room []byte, id key, nonce, sig []byte) bool {
	if len(room) < 16 || len(room) > 32 || len(nonce) != nonceSize || len(sig) != ed25519.SignatureSize {
		return false
	}
	if isWeakKey(id) {
		return false
	}
	return ed25519.Verify(ed25519.PublicKey(id[:]), authMessage(room, id, nonce), sig)
}

type authStats struct {
	failed atomic.Uint64
}

// signIn runs the exchange. It returns true only when the client has proved it holds id; otherwise the connection has been closed (or is gone).
func (s *server) signIn(conn *websocket.Conn, room []byte, id key) bool {
	nonce := make([]byte, nonceSize)
	if _, err := rand.Read(nonce); err != nil {
		_ = conn.Close(websocket.StatusInternalError, "no randomness")
		return false
	}
	// The time limit closes the connection itself so that the client is told why (a cancelled read would just drop it).
	var timedOut atomic.Bool
	timer := time.AfterFunc(s.authTimeout, func() {
		timedOut.Store(true)
		_ = conn.Close(statusAuthTimeout, "no answer in time")
	})
	defer timer.Stop()

	ctx := context.Background()
	if err := conn.Write(ctx, websocket.MessageBinary, append([]byte{msgChallenge}, nonce...)); err != nil {
		return false
	}
	typ, data, err := conn.Read(ctx)
	if err != nil {
		return false // gone, or closed by the timer above
	}
	if timedOut.Load() {
		return false
	}
	if typ != websocket.MessageBinary || len(data) != answerSize || data[0] != msgAnswer || !verifyAuth(room, id, nonce, data[1:]) {
		s.auth.failed.Add(1)
		_ = conn.Close(statusNotHolder, "not the holder of this key")
		return false
	}
	if !timer.Stop() && timedOut.Load() {
		return false
	}
	return conn.Write(ctx, websocket.MessageBinary, []byte{msgReady}) == nil
}
