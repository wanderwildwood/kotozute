package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// SignalCLI is a persistent JSON-RPC connection to signal-cli on loopback.
//
// There is deliberately no generic passthrough. Every call the bridge can make is
// a method on this type, so the destructive parts of signal-cli's surface
// (unregister, deleteLocalAccountData, removeDevice, setPin, ...) are not merely
// filtered -- there is no code path that reaches them.
type SignalCLI struct {
	addr    string
	account string

	mu      sync.Mutex
	conn    net.Conn
	pending map[int64]chan *rpcResp
	nextID  atomic.Int64

	OnEvent func(json.RawMessage)

	connected atomic.Bool
	lastErr   atomic.Value // string
}

type rpcResp struct {
	Result json.RawMessage `json:"result"`
	Error  *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
	ID     *int64          `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}

func NewSignalCLI(addr, account string) *SignalCLI {
	return &SignalCLI{addr: addr, account: account, pending: map[int64]chan *rpcResp{}}
}

func (s *SignalCLI) Connected() bool { return s.connected.Load() }

// Account is the number this bridge is attached to.
func (s *SignalCLI) Account() string { return s.account }
func (s *SignalCLI) LastError() string {
	v, _ := s.lastErr.Load().(string)
	return v
}

// Run keeps the connection up forever. signal-cli must be started with
// --receive-mode on-connection: while nothing is connected, Signal's servers hold
// undelivered messages. With on-start they are acked and dropped instead, so every
// reconnect gap would be a silent hole in the user's history.
func (s *SignalCLI) Run(stop <-chan struct{}) {
	backoff := time.Second
	for {
		select {
		case <-stop:
			return
		default:
		}
		if err := s.session(stop); err != nil {
			s.lastErr.Store(err.Error())
			log.Printf("signal-cli: %v (retry in %s)", err, backoff)
		}
		s.connected.Store(false)
		select {
		case <-stop:
			return
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
		}
	}
}

func (s *SignalCLI) session(stop <-chan struct{}) error {
	conn, err := net.DialTimeout("tcp", s.addr, 10*time.Second)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.conn = conn
	s.mu.Unlock()
	s.connected.Store(true)
	s.lastErr.Store("")
	log.Printf("signal-cli: connected to %s", s.addr)

	defer func() {
		conn.Close()
		s.mu.Lock()
		s.conn = nil
		for id, ch := range s.pending {
			close(ch)
			delete(s.pending, id)
		}
		s.mu.Unlock()
	}()

	go func() { <-stop; conn.Close() }()

	r := bufio.NewReaderSize(conn, 1<<20)
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return err
		}
		if len(line) < 2 {
			continue
		}
		var msg rpcResp
		if err := json.Unmarshal(line, &msg); err != nil {
			continue
		}
		if msg.ID != nil {
			s.mu.Lock()
			ch, ok := s.pending[*msg.ID]
			delete(s.pending, *msg.ID)
			s.mu.Unlock()
			if ok {
				m := msg
				ch <- &m
				close(ch)
			}
			continue
		}
		if msg.Method == "receive" && s.OnEvent != nil {
			s.OnEvent(msg.Params)
		}
	}
}

func (s *SignalCLI) call(method string, params any, out any) error {
	id := s.nextID.Add(1)
	req := map[string]any{"jsonrpc": "2.0", "method": method, "id": id}
	if params != nil {
		req["params"] = params
	}
	body, err := json.Marshal(req)
	if err != nil {
		return err
	}
	ch := make(chan *rpcResp, 1)

	s.mu.Lock()
	conn := s.conn
	if conn == nil {
		s.mu.Unlock()
		return errors.New("signal-cli not connected")
	}
	s.pending[id] = ch
	_, err = conn.Write(append(body, '\n'))
	s.mu.Unlock()
	if err != nil {
		return err
	}

	select {
	case resp, ok := <-ch:
		if !ok || resp == nil {
			return errors.New("signal-cli connection closed mid-call")
		}
		if resp.Error != nil {
			return fmt.Errorf("signal-cli %s: %s", method, resp.Error.Message)
		}
		if out != nil && len(resp.Result) > 0 {
			return json.Unmarshal(resp.Result, out)
		}
		return nil
	case <-time.After(90 * time.Second):
		s.mu.Lock()
		delete(s.pending, id)
		s.mu.Unlock()
		return fmt.Errorf("signal-cli %s: timeout", method)
	}
}

// ---- the entire allowlist ------------------------------------------------

func (s *SignalCLI) Version() (string, error) {
	var v struct {
		Version string `json:"version"`
	}
	err := s.call("version", nil, &v)
	return v.Version, err
}

type SCContact struct {
	UUID    string `json:"uuid"`
	Number  string `json:"number"`
	Name    string `json:"name"`
	Profile struct {
		GivenName  string `json:"givenName"`
		FamilyName string `json:"familyName"`
	} `json:"profile"`
}

func (s *SignalCLI) ListContacts() ([]SCContact, error) {
	var out []SCContact
	err := s.call("listContacts", map[string]any{"account": s.account}, &out)
	return out, err
}

type SCGroup struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	Members  []any    `json:"members"`
	IsMember bool     `json:"isMember"`
	Admins   []any    `json:"admins"`
	_        struct{} `json:"-"`
}

func (s *SignalCLI) ListGroups() ([]SCGroup, error) {
	var out []SCGroup
	err := s.call("listGroups", map[string]any{"account": s.account}, &out)
	return out, err
}

type SendResult struct {
	Timestamp int64 `json:"timestamp"`
}

func (s *SignalCLI) SendToRecipient(recipient, body string, attachments []string) (int64, error) {
	p := map[string]any{"account": s.account, "message": body, "recipient": []string{recipient}}
	if len(attachments) > 0 {
		p["attachments"] = attachments
	}
	var r SendResult
	err := s.call("send", p, &r)
	return r.Timestamp, err
}

func (s *SignalCLI) SendToGroup(groupID, body string, attachments []string) (int64, error) {
	p := map[string]any{"account": s.account, "message": body, "groupId": groupID}
	if len(attachments) > 0 {
		p["attachments"] = attachments
	}
	var r SendResult
	err := s.call("send", p, &r)
	return r.Timestamp, err
}

func (s *SignalCLI) SendToNoteToSelf(body string, attachments []string) (int64, error) {
	p := map[string]any{"account": s.account, "message": body, "noteToSelf": true}
	if len(attachments) > 0 {
		p["attachments"] = attachments
	}
	var r SendResult
	err := s.call("send", p, &r)
	return r.Timestamp, err
}

// SetBlocked blocks or unblocks a contact on the Signal account itself, which is what
// makes it stick across every device rather than only hiding them here.
//
// One method with a flag rather than two, so the allowlist grows by one entry: this file is
// the entire surface the phone can reach through the bridge, and every addition to it is a
// thing a stolen pairing token can now do.
func (s *SignalCLI) SetBlocked(recipient string, blocked bool) error {
	method := "unblock"
	if blocked {
		method = "block"
	}
	return s.call(method, map[string]any{
		"account": s.account, "recipient": []string{recipient},
	}, nil)
}

// SetGroupBlocked is the same for a group; signal-cli takes groups by a separate parameter.
func (s *SignalCLI) SetGroupBlocked(groupID string, blocked bool) error {
	method := "unblock"
	if blocked {
		method = "block"
	}
	return s.call(method, map[string]any{
		"account": s.account, "groupId": []string{groupID},
	}, nil)
}

func (s *SignalCLI) SendReadReceipt(recipient string, timestamps []int64) error {
	return s.call("sendReceipt", map[string]any{
		"account": s.account, "recipient": recipient,
		"targetTimestamp": timestamps, "type": "read",
	}, nil)
}

// SCDevice is one device linked to this Signal account. Device 1 is the primary; a linked
// device cannot register, change the number, set the registration lock, or transfer the
// account -- so which of these we are decides what the app can honestly offer.
type SCDevice struct {
	ID       int    `json:"id"`
	Name     string `json:"name"`
	Created  int64  `json:"createdTimestamp"`
	LastSeen int64  `json:"lastSeenTimestamp"`
}

func (s *SignalCLI) ListDevices() ([]SCDevice, error) {
	var out []SCDevice
	err := s.call("listDevices", map[string]any{"account": s.account}, &out)
	return out, err
}

type SCIdentity struct {
	Number string `json:"number"`
	UUID   string `json:"uuid"`
	// The 60-digit safety number two people compare to know they are talking to each
	// other and not to something in between.
	SafetyNumber string `json:"safetyNumber"`
	// TRUSTED_VERIFIED, TRUSTED_UNVERIFIED or UNTRUSTED. UNTRUSTED is the one that
	// matters: the other end's key changed and has not been accepted since.
	TrustLevel string `json:"trustLevel"`
	Added      int64  `json:"addedTimestamp"`
}

// ListIdentities is how we learn our own ACI. Note that getUserStatus returns the
// PNI (phone-number identity) for the same number, which is a *different* uuid and
// is not what sync messages are keyed on -- using it would split every thread.
func (s *SignalCLI) ListIdentities() ([]SCIdentity, error) {
	var out []SCIdentity
	err := s.call("listIdentities", map[string]any{"account": s.account}, &out)
	return out, err
}
