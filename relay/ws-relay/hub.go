package main

import (
	"context"
	"sync"
	"sync/atomic"

	"github.com/coder/websocket"
)

type key [32]byte

type client struct {
	id     key
	send   chan []byte
	conn   *websocket.Conn
	cancel context.CancelFunc
}

func (c *client) kick(code websocket.StatusCode, reason string) {
	c.cancel()
	_ = c.conn.Close(code, reason)
}

type hub struct {
	mu         sync.Mutex
	rooms      map[string]map[key]*client
	maxPerRoom int
	forwarded  atomic.Uint64
	dropped    atomic.Uint64
}

func newHub(maxPerRoom int) *hub {
	return &hub{rooms: make(map[string]map[key]*client), maxPerRoom: maxPerRoom}
}

// hasSpace reports whether a client with this id could join the room now.
func (h *hub) hasSpace(room string, id key) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.rooms[room]
	if _, ok := r[id]; ok {
		return true
	}
	return len(r) < h.maxPerRoom
}

// join adds the client. A client that reconnects with the same id replaces the old connection.
func (h *hub) join(room string, c *client) (replaced *client, ok bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.rooms[room]
	if r == nil {
		r = make(map[key]*client)
		h.rooms[room] = r
	}
	if old, exists := r[c.id]; exists {
		replaced = old
	} else if len(r) >= h.maxPerRoom {
		return nil, false
	}
	r[c.id] = c
	return replaced, true
}

func (h *hub) leave(room string, c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.rooms[room]
	if r[c.id] == c {
		delete(r, c.id)
	}
	if len(r) == 0 {
		delete(h.rooms, room)
	}
}

func (h *hub) counts() (rooms, clients int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, r := range h.rooms {
		clients += len(r)
	}
	return len(h.rooms), clients
}

const (
	modeBroadcast = 0
	modeTargeted  = 1
)

// forward relays one frame from a client. Frame layout from the client:
//
//	[mode 1 byte][payload]                   mode 0: send to every other client in the room
//	[mode 1 byte][target key 32][payload]    mode 1: send only to the client with that key
//
// Receivers get [sender key 32][payload]. The relay never looks inside the payload.
// It returns false if the frame is malformed.
func (h *hub) forward(room string, from *client, frame []byte) bool {
	if len(frame) < 1 {
		return false
	}
	var targets []*client
	var payload []byte

	h.mu.Lock()
	r := h.rooms[room]
	switch frame[0] {
	case modeBroadcast:
		payload = frame[1:]
		for id, c := range r {
			if id != from.id {
				targets = append(targets, c)
			}
		}
	case modeTargeted:
		if len(frame) < 33 {
			h.mu.Unlock()
			return false
		}
		var t key
		copy(t[:], frame[1:33])
		payload = frame[33:]
		if c, ok := r[t]; ok && t != from.id {
			targets = append(targets, c)
		}
	default:
		h.mu.Unlock()
		return false
	}
	h.mu.Unlock()

	out := make([]byte, 0, 32+len(payload))
	out = append(out, from.id[:]...)
	out = append(out, payload...)
	for _, c := range targets {
		select {
		case c.send <- out:
			h.forwarded.Add(1)
		default:
			h.dropped.Add(1)
			c.kick(websocket.StatusPolicyViolation, "too slow")
		}
	}
	return true
}
