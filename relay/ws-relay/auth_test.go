package main

import (
	"context"
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// M12 (docs/CRYPTO.md section 7a): the relay admits a connection for a key only to the holder of that key.

const (
	closeNotHolder = websocket.StatusCode(4401)
	closeTooSlow   = websocket.StatusCode(4408)
)

// expectClosed reads until the relay closes the connection and returns the close code.
func expectClosed(t *testing.T, c *websocket.Conn) websocket.StatusCode {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for {
		if _, _, err := c.Read(ctx); err != nil {
			code := websocket.CloseStatus(err)
			if code == -1 {
				t.Fatalf("the connection ended without a close code: %v", err)
			}
			return code
		}
	}
}

func rawAnswer(t *testing.T, _ any, c *websocket.Conn, answer []byte) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := c.Write(ctx, websocket.MessageBinary, answer); err != nil {
		t.Fatalf("write: %v", err)
	}
}

func TestChallengeIsFreshForEveryConnection(t *testing.T) {
	_, ts := startServer(t, 20)
	seen := map[string]bool{}
	for i := 0; i < 5; i++ {
		c, _, err := dialRaw(ts, roomA, keyHex(byte(i+1)))
		if err != nil {
			t.Fatal(err)
		}
		defer c.CloseNow()
		nonce, err := readChallenge(c)
		if err != nil || len(nonce) != 32 {
			t.Fatalf("challenge: %v %x", err, nonce)
		}
		if seen[string(nonce)] {
			t.Fatal("a nonce was used twice")
		}
		seen[string(nonce)] = true
	}
}

func TestTheHolderOfTheKeyIsAdmittedAndCanTalk(t *testing.T) {
	s, ts := startServer(t, 20)
	a := dial(t, ts, roomA, 1)
	b := dial(t, ts, roomA, 2)
	waitJoined(t, s, 2)
	write(t, a, append([]byte{0}, []byte("hello")...))
	got := read(t, b)
	if string(got[:32]) != string(keyBytes(1)) || string(got[32:]) != "hello" {
		t.Fatalf("got %x", got)
	}
}

func TestAnAnswerSignedByAnotherKeyIsRefused(t *testing.T) {
	s, ts := startServer(t, 20)
	c, _, _ := dialRaw(ts, roomA, keyHex(1))
	defer c.CloseNow()
	nonce, _ := readChallenge(c)
	rawAnswer(t, ts, c, answerFor(roomA, keyBytes(1), testPriv(2), nonce)) // client 2 signs for client 1's key
	if code := expectClosed(t, c); code != closeNotHolder {
		t.Fatalf("close code %d", code)
	}
	if _, n := s.hub.counts(); n != 0 {
		t.Fatalf("%d clients in rooms", n)
	}
}

func TestAnAnswerForAnotherRoomIsRefused(t *testing.T) {
	_, ts := startServer(t, 20)
	c, _, _ := dialRaw(ts, roomA, keyHex(1))
	defer c.CloseNow()
	nonce, _ := readChallenge(c)
	rawAnswer(t, ts, c, answerFor(roomB, keyBytes(1), testPriv(1), nonce)) // right key, signed for room B, connecting to room A
	if code := expectClosed(t, c); code != closeNotHolder {
		t.Fatalf("close code %d", code)
	}
}

func TestARecordedAnswerCannotBeReplayedOnAnotherConnection(t *testing.T) {
	_, ts := startServer(t, 20)
	// The real member signs in; an eavesdropper keeps their answer.
	c1, _, _ := dialRaw(ts, roomA, keyHex(1))
	defer c1.CloseNow()
	nonce1, _ := readChallenge(c1)
	recorded := answerFor(roomA, keyBytes(1), testPriv(1), nonce1)
	rawAnswer(t, ts, c1, recorded)
	// The eavesdropper opens their own connection for the same key and room and plays the recording back.
	c2, _, _ := dialRaw(ts, roomA, keyHex(1))
	defer c2.CloseNow()
	if _, err := readChallenge(c2); err != nil {
		t.Fatal(err)
	}
	rawAnswer(t, ts, c2, recorded)
	if code := expectClosed(t, c2); code != closeNotHolder {
		t.Fatalf("close code %d", code)
	}
}

func TestAKeyOfSmallOrderIsRefused(t *testing.T) {
	_, ts := startServer(t, 20)
	for _, id := range []string{
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	} {
		c, _, err := dialRaw(ts, roomA, id)
		if err != nil {
			t.Fatal(err)
		}
		defer c.CloseNow()
		if _, err := readChallenge(c); err != nil {
			t.Fatal(err)
		}
		idBytes, _ := hex.DecodeString(id)
		rawAnswer(t, ts, c, append([]byte{0x02}, append(append([]byte{}, idBytes...), make([]byte, 32)...)...)) // the signature that verifies for such keys
		if code := expectClosed(t, c); code != closeNotHolder {
			t.Fatalf("%s: close code %d", id, code)
		}
	}
}

func TestMalformedAnswersAreRefused(t *testing.T) {
	_, ts := startServer(t, 20)
	good := func(nonce []byte) []byte { return answerFor(roomA, keyBytes(1), testPriv(1), nonce) }
	cases := map[string]func(nonce []byte) []byte{
		"too short":        func(n []byte) []byte { return good(n)[:40] },
		"too long":         func(n []byte) []byte { return append(good(n), 0) },
		"wrong type byte":  func(n []byte) []byte { b := good(n); b[0] = 0x05; return b },
		"empty":            func(n []byte) []byte { return []byte{} },
		"a data frame":     func(n []byte) []byte { return append([]byte{0}, []byte("hello")...) },
		"a targeted frame": func(n []byte) []byte { return append([]byte{1}, make([]byte, 40)...) },
	}
	for name, make := range cases {
		c, _, _ := dialRaw(ts, roomA, keyHex(1))
		nonce, err := readChallenge(c)
		if err != nil {
			t.Fatal(err)
		}
		rawAnswer(t, ts, c, make(nonce))
		if code := expectClosed(t, c); code != closeNotHolder {
			t.Fatalf("%s: close code %d", name, code)
		}
		_ = c.CloseNow()
	}
}

func TestATextFrameInsteadOfAnAnswerIsRefused(t *testing.T) {
	_, ts := startServer(t, 20)
	c, _, _ := dialRaw(ts, roomA, keyHex(1))
	defer c.CloseNow()
	if _, err := readChallenge(c); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = c.Write(ctx, websocket.MessageText, []byte("hello"))
	if code := expectClosed(t, c); code != closeNotHolder && code != websocket.StatusUnsupportedData {
		t.Fatalf("close code %d", code)
	}
}

func TestNoAnswerInTimeClosesTheConnection(t *testing.T) {
	s, ts := startServer(t, 20)
	s.authTimeout = 300 * time.Millisecond
	c, _, _ := dialRaw(ts, roomA, keyHex(1))
	defer c.CloseNow()
	if _, err := readChallenge(c); err != nil {
		t.Fatal(err)
	}
	if code := expectClosed(t, c); code != closeTooSlow {
		t.Fatalf("close code %d", code)
	}
}

func TestAClientThatHasNotSignedInReceivesNothingAndIsInNoRoom(t *testing.T) {
	s, ts := startServer(t, 20)
	a := dial(t, ts, roomA, 1)
	b := dial(t, ts, roomA, 2)
	waitJoined(t, s, 2)
	// A third client connects and says nothing after the challenge.
	lurker, _, _ := dialRaw(ts, roomA, keyHex(3))
	defer lurker.CloseNow()
	if _, err := readChallenge(lurker); err != nil {
		t.Fatal(err)
	}
	write(t, a, append([]byte{0}, []byte("secret")...))
	read(t, b)
	expectSilence(t, lurker)
	if _, n := s.hub.counts(); n != 2 {
		t.Fatalf("%d clients in rooms, want 2", n)
	}
}

func TestAnImpostorCannotReplaceOrDisturbTheRealMember(t *testing.T) {
	s, ts := startServer(t, 20)
	real := dial(t, ts, roomA, 1)
	friend := dial(t, ts, roomA, 2)
	waitJoined(t, s, 2)
	// Someone who knows the room and client 1's key, but not the private key, connects as client 1.
	imp, _, _ := dialRaw(ts, roomA, keyHex(1))
	defer imp.CloseNow()
	nonce, _ := readChallenge(imp)
	rawAnswer(t, ts, imp, answerFor(roomA, keyBytes(1), testPriv(99), nonce))
	if code := expectClosed(t, imp); code != closeNotHolder {
		t.Fatalf("close code %d", code)
	}
	// The real member is still there and still hears the room.
	write(t, friend, append([]byte{0}, []byte("still here")...))
	got := read(t, real)
	if string(got[32:]) != "still here" {
		t.Fatalf("got %x", got)
	}
	if _, n := s.hub.counts(); n != 2 {
		t.Fatalf("%d clients in rooms, want 2", n)
	}
}

func TestOnlyTheHolderCanReplaceTheirOwnConnection(t *testing.T) {
	s, ts := startServer(t, 20)
	old := dial(t, ts, roomA, 1)
	waitJoined(t, s, 1)
	fresh := dial(t, ts, roomA, 1) // the real holder reconnects (a new phone socket)
	// The relay drops the old connection; whether the client sees the close code or only the end of the stream depends on timing, so both count.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for {
		if _, _, err := old.Read(ctx); err != nil {
			if code := websocket.CloseStatus(err); code != -1 && code != websocket.StatusPolicyViolation {
				t.Fatalf("the old connection closed with %d", code)
			}
			if ctx.Err() != nil {
				t.Fatal("the old connection was not closed")
			}
			break
		}
	}
	other := dial(t, ts, roomA, 2)
	write(t, other, append([]byte{0}, []byte("hi")...))
	read(t, fresh)
}

// The independent implementation's vectors (app/crypto/src/test/tools/crypto_vectors.py), also read by the app's own tests.
func TestVectorsFromTheAppsCryptoModuleAgree(t *testing.T) {
	data, err := os.ReadFile("../../app/crypto/src/test/resources/crypto-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		RelayAuth []struct {
			Name      string `json:"name"`
			ID        string `json:"id"`
			Room      string `json:"room"`
			Nonce     string `json:"nonce"`
			Signature string `json:"signature"`
			Valid     bool   `json:"valid"`
		} `json:"relayAuth"`
	}
	if err := json.Unmarshal(data, &file); err != nil {
		t.Fatal(err)
	}
	if len(file.RelayAuth) < 8 {
		t.Fatalf("only %d vectors", len(file.RelayAuth))
	}
	for _, v := range file.RelayAuth {
		id, _ := hex.DecodeString(v.ID)
		room, _ := hex.DecodeString(v.Room)
		nonce, _ := hex.DecodeString(v.Nonce)
		sig, _ := hex.DecodeString(v.Signature)
		var k key
		copy(k[:], id)
		if got := verifyAuth(room, k, nonce, sig); got != v.Valid {
			t.Errorf("%s: verifyAuth = %v, the independent implementation says %v", v.Name, got, v.Valid)
		}
		// And the message the relay builds is the one the test above wrote out on its own.
		if string(authMessage(room, k, nonce)) != string(authMessageFor(room, id, nonce)) {
			t.Errorf("%s: the relay builds another message", v.Name)
		}
	}
	_ = ed25519.PublicKeySize
}
