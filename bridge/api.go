package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// A photo grows by about a third in base64, so this allows a large one with room over.
const maxSendBytes = 48 << 20

// SelfUUID holds the account's own UUID.
//
// On a first run it is not known at start: a goroutine resolves it from signal-cli seconds
// later, and by then the reader goroutine and every HTTP handler are already reading it. A
// Go string is two words, a pointer and a length, so an unsynchronised read across that
// write can pair one string's pointer with the other's length -- a read past the end of
// the allocation at worst, a wrong sender on a message at best. Behind an atomic a reader
// sees one whole value or the other.
type SelfUUID struct{ v atomic.Value }

func (s *SelfUUID) Get() string {
	u, _ := s.v.Load().(string)
	return u
}

func (s *SelfUUID) Set(u string) { s.v.Store(u) }

type API struct {
	store *Store
	sc    *SignalCLI
	auth  *Auth
	self  *SelfUUID
	atts  *Attachments

	subsMu sync.Mutex
	subs   map[chan *Message]*subscriber
}

// subscriber is one attached phone's live stream.
//
// dropped is the whole point of the type. A phone advances its cursor from the messages it
// actually receives, so a message the bridge quietly skipped is skipped for ever: the next
// catch-up asks for everything after the message that DID arrive. Recording the drop lets
// the stream end instead, and a reconnect resumes from the cursor the phone really reached.
type subscriber struct {
	ch      chan *Message
	dropped bool
}

func NewAPI(st *Store, sc *SignalCLI, auth *Auth, self *SelfUUID, atts *Attachments) *API {
	return &API{store: st, sc: sc, auth: auth, self: self, atts: atts,
		subs: map[chan *Message]*subscriber{}}
}

// Broadcast fans a newly stored message out to attached phones.
//
// A subscriber that cannot keep up is marked and its stream ended rather than skipped past.
// The comment that used to sit here said the phone would "catch up by cursor instead"; it
// would not. The phone's cursor is the seq of the last message it processed, so dropping one
// and delivering the next moves the cursor past the gap and the missed message is never
// asked for again. Ending the stream leaves the cursor where it truly is, and the reconnect
// fetches everything after it.
func (a *API) Broadcast(m *Message) {
	a.subsMu.Lock()
	defer a.subsMu.Unlock()
	for _, sub := range a.subs {
		if sub.dropped {
			continue
		}
		select {
		case sub.ch <- m:
		default:
			sub.dropped = true
			log.Printf("events: a subscriber fell behind at seq %d; ending its stream to force a catch-up", m.Seq)
			close(sub.ch)
		}
	}
}

func (a *API) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/state", a.guard(a.state))
	mux.HandleFunc("/v1/threads", a.guard(a.threads))
	mux.HandleFunc("/v1/changes", a.guard(a.changes))
	mux.HandleFunc("/v1/events", a.guard(a.events))
	mux.HandleFunc("/v1/threads/", a.guard(a.threadSub))
	mux.HandleFunc("/v1/attachments/", a.guard(a.attachment))
	mux.HandleFunc("/v1/account", a.guard(a.account))
	return logging(mux)
}

func (a *API) attachment(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.NotFound(w, r)
		return
	}
	a.atts.Serve(w, r, strings.TrimPrefix(r.URL.Path, "/v1/attachments/"))
}

func (a *API) guard(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.auth.Check(r.Header.Get("Authorization")) {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		h(w, r)
	}
}

func (a *API) state(w http.ResponseWriter, r *http.Request) {
	maxSeq, _ := a.store.MaxSeq()
	instance, _ := a.store.InstanceID()
	writeJSON(w, 200, map[string]any{
		"instance":        instance,
		"account":         a.self.Get(),
		"signalConnected": a.sc.Connected(),
		"signalError":     a.sc.LastError(),
		"maxSeq":          maxSeq,
		"serverTime":      time.Now().UnixMilli(),
	})
}

func (a *API) threads(w http.ResponseWriter, r *http.Request) {
	t, err := a.store.Threads()
	if err != nil {
		writeErr(w, err)
		return
	}
	if t == nil {
		t = []*ThreadRow{}
	}
	// The account is never in its own contact list, so its thread would otherwise
	// reach the client as a bare uuid with no name to show.
	for _, row := range t {
		self := a.self.Get()
		if row.Title == "" && self != "" && row.Key == "direct:"+self {
			row.Title = "Note to Self"
		}
	}
	writeJSON(w, 200, map[string]any{"threads": t})
}

// changes is how a phone that has been offline catches up: give us your cursor,
// get everything after it. Same path an imported backup takes.
func (a *API) changes(w http.ResponseWriter, r *http.Request) {
	since, _ := strconv.ParseInt(r.URL.Query().Get("sinceSeq"), 10, 64)
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))

	// Read before the page, not after. The client treats an empty page plus a higher
	// maxSeq as "nothing more, you are caught up" and jumps its cursor there. Read after,
	// a message arriving between the two statements would be counted in maxSeq but absent
	// from the page, and the jump would step straight over it -- changes() never returns
	// it again, and the SSE stream is not attached yet during catch-up, so nothing else
	// would ever deliver it. Read first, the same arrival simply leaves maxSeq one behind
	// and the client asks again.
	maxSeq, _ := a.store.MaxSeq()

	msgs, err := a.store.Changes(since, limit)
	if err != nil {
		writeErr(w, err)
		return
	}
	if msgs == nil {
		msgs = []*Message{}
	}
	writeJSON(w, 200, map[string]any{"messages": msgs, "maxSeq": maxSeq})
}

// events is Server-Sent Events rather than a websocket: no dependency, no upgrade
// handshake, and the client is a plain streaming HTTP read on Android.
func (a *API) events(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", 500)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(200)

	// Replay anything the caller has not seen before going live, so the gap
	// between "asked for history" and "subscribed" cannot drop a message.
	if s := r.URL.Query().Get("sinceSeq"); s != "" {
		since, _ := strconv.ParseInt(s, 10, 64)
		if msgs, err := a.store.Changes(since, 500); err == nil {
			for _, m := range msgs {
				sseSend(w, fl, "message", m)
			}
		}
	}

	ch := make(chan *Message, 64)
	sub := &subscriber{ch: ch}
	a.subsMu.Lock()
	a.subs[ch] = sub
	a.subsMu.Unlock()
	defer func() {
		a.subsMu.Lock()
		delete(a.subs, ch)
		// Only the broadcaster closes the channel, and only once; marking it dropped here
		// stops a close racing with a send after this handler has gone.
		sub.dropped = true
		a.subsMu.Unlock()
	}()

	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case m, open := <-ch:
			if !open {
				// Fell behind: end the response so the phone reconnects and asks for
				// everything after the cursor it actually reached.
				return
			}
			sseSend(w, fl, "message", m)
		case <-ping.C:
			fmt.Fprintf(w, ": ping\n\n")
			fl.Flush()
		}
	}
}

// parseThreadRoute splits /v1/threads/{key}/{action}.
//
// It splits on the LAST separator rather than the first, because a thread key is not
// opaque: a group's key carries a base64 group id, and the standard base64 alphabet
// includes "/". Splitting on the first separator would truncate every group whose id
// happens to contain one -- roughly half of them -- and the failure would look like an
// empty conversation rather than an error.
func parseThreadRoute(path string) (key, action string, ok bool) {
	rest := strings.TrimPrefix(path, "/v1/threads/")
	if rest == path {
		return "", "", false
	}
	i := strings.LastIndex(rest, "/")
	if i <= 0 {
		return "", "", false
	}
	key, action = rest[:i], rest[i+1:]
	if key == "" || action == "" {
		return "", "", false
	}
	return key, action, true
}

// threadSub routes /v1/threads/{key}/{messages,send,read}
func (a *API) threadSub(w http.ResponseWriter, r *http.Request) {
	key, action, ok := parseThreadRoute(r.URL.Path)
	if !ok {
		http.NotFound(w, r)
		return
	}
	switch {
	case action == "messages" && r.Method == http.MethodGet:
		before, _ := strconv.ParseInt(r.URL.Query().Get("beforeTs"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		msgs, err := a.store.ThreadMessages(key, before, limit)
		if err != nil {
			writeErr(w, err)
			return
		}
		if msgs == nil {
			msgs = []*Message{}
		}
		writeJSON(w, 200, map[string]any{"messages": msgs})
	case action == "send" && r.Method == http.MethodPost:
		a.send(w, r, key)
	case action == "read" && r.Method == http.MethodPost:
		a.markRead(w, r, key)
	case action == "block" && r.Method == http.MethodPost:
		a.setBlocked(w, r, key)
	case action == "identity" && r.Method == http.MethodGet:
		a.identity(w, r, key)
	default:
		http.NotFound(w, r)
	}
}

// account describes the Signal account this bridge is attached to, and which device we are
// on it. Read-only: everything an account page could otherwise want to change -- the
// registration lock, the number, transferring the account -- belongs to the primary device,
// and this is not it.
func (a *API) account(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.NotFound(w, r)
		return
	}
	devices, err := a.sc.ListDevices()
	if err != nil {
		// Not fatal: the number and uuid are known locally and are worth returning even
		// when signal-cli cannot be asked about the rest.
		log.Printf("listDevices: %v", err)
	}
	out := map[string]any{
		"number":   a.sc.Account(),
		"selfUuid": a.store.GetMeta("selfUuid"),
		"devices":  devices,
	}
	if devices == nil {
		out["devices"] = []SCDevice{}
	}
	writeJSON(w, 200, out)
}

// identity returns the safety number for a one-to-one thread and whether the other end's
// key is still the one that was accepted.
//
// This is the part of "verify keys" that can honestly be offered. Automatically trusting a
// changed key is not verification, it is the opposite: safety numbers exist so that a key
// changing under you is something you are told about rather than something handled quietly.
func (a *API) identity(w http.ResponseWriter, r *http.Request, key string) {
	uuid, ok := strings.CutPrefix(key, "direct:")
	if !ok {
		// A group has no single safety number; each member has their own.
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "not a direct thread"})
		return
	}
	ids, err := a.sc.ListIdentities()
	if err != nil {
		writeErr(w, err)
		return
	}
	for _, id := range ids {
		if id.UUID == uuid {
			writeJSON(w, 200, map[string]any{
				"safetyNumber": id.SafetyNumber,
				"trustLevel":   id.TrustLevel,
				"added":        id.Added,
			})
			return
		}
	}
	// Not an error: a contact who has never exchanged a message has no identity record.
	writeJSON(w, 200, map[string]any{"safetyNumber": "", "trustLevel": "", "added": 0})
}

// setBlocked blocks or unblocks the other party of a thread, on the Signal account itself
// so it holds on every device rather than only hiding them here.
func (a *API) setBlocked(w http.ResponseWriter, r *http.Request, key string) {
	var body struct {
		Blocked bool `json:"blocked"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad request body"})
		return
	}

	var err error
	switch {
	case strings.HasPrefix(key, "group:"):
		err = a.sc.SetGroupBlocked(strings.TrimPrefix(key, "group:"), body.Blocked)
	case strings.HasPrefix(key, "direct:"):
		err = a.sc.SetBlocked(strings.TrimPrefix(key, "direct:"), body.Blocked)
	default:
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "unknown thread"})
		return
	}
	if err != nil {
		// Said out loud rather than swallowed: a block that quietly failed would leave
		// someone believing they had stopped hearing from a person they had not.
		log.Printf("block %s (blocked=%v): %v", redactPath(key), body.Blocked, err)
		writeErr(w, err)
		return
	}
	log.Printf("block %s: blocked=%v", redactPath(key), body.Blocked)
	writeJSON(w, 200, map[string]any{"ok": true, "blocked": body.Blocked})
}

func (a *API) send(w http.ResponseWriter, r *http.Request, key string) {
	var body struct {
		Message string `json:"message"`
		// RFC 2397 data URIs. signal-cli takes these directly, so nothing decrypted
		// is written to disk on the way through.
		Attachments []string `json:"attachments"`
	}
	// Generous, because a photo base64s to a third again its size.
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxSendBytes)).Decode(&body); err != nil {
		writeJSON(w, 400, map[string]string{"error": "bad json or body too large"})
		return
	}
	if strings.TrimSpace(body.Message) == "" && len(body.Attachments) == 0 {
		writeJSON(w, 400, map[string]string{"error": "nothing to send"})
		return
	}
	for _, att := range body.Attachments {
		if !strings.HasPrefix(att, "data:") {
			writeJSON(w, 400, map[string]string{"error": "attachments must be data URIs"})
			return
		}
	}
	if !a.sc.Connected() {
		// Sending fails hard when the bridge cannot reach signal-cli. Say so
		// plainly rather than accepting a message we cannot deliver.
		writeJSON(w, 503, map[string]string{"error": "signal-cli not connected"})
		return
	}

	var ts int64
	var err error
	switch {
	case strings.HasPrefix(key, "group:"):
		ts, err = a.sc.SendToGroup(strings.TrimPrefix(key, "group:"), body.Message, body.Attachments)
	case strings.HasPrefix(key, "direct:"):
		to := strings.TrimPrefix(key, "direct:")
		if to == a.self.Get() {
			ts, err = a.sc.SendToNoteToSelf(body.Message, body.Attachments)
		} else {
			ts, err = a.sc.SendToRecipient(to, body.Message, body.Attachments)
		}
	default:
		writeJSON(w, 400, map[string]string{"error": "unknown thread key"})
		return
	}
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}

	// Record our own send immediately. signal-cli does not echo it back to the
	// sending connection, so without this the message would not appear until some
	// other device synced it -- and for Note to Self, never.
	m := &Message{
		ID: fmt.Sprintf("%s:%d", a.self.Get(), ts), ThreadKey: key, TS: ts,
		SenderUUID: a.self.Get(), Outgoing: true, Body: body.Message,
		Read: true, Source: "live",
		// Signal assigns attachment ids on upload and the send result does not report
		// them, so our own sent attachments are recorded without one. The sender still
		// sees that the message carried a picture; there is simply nothing to fetch,
		// which is what an empty id means to the client.
		Attachments: describeSent(body.Attachments),
	}
	if strings.HasPrefix(key, "group:") {
		m.GroupID = strings.TrimPrefix(key, "group:")
	}
	seq, isNew, serr := a.store.InsertMessage(m)
	if serr != nil {
		log.Printf("send: stored badly: %v", serr)
	}
	m.Seq = seq
	if isNew {
		a.Broadcast(m)
	}
	writeJSON(w, 200, map[string]any{"timestamp": ts, "seq": seq})
}

func (a *API) markRead(w http.ResponseWriter, r *http.Request, key string) {
	var body struct {
		UpToTs int64 `json:"upToTs"`
		// Whether to tell the senders. Left to the caller because it is the user's
		// privacy choice, and Signal's own read-receipt setting is not readable here.
		SendReceipts bool `json:"sendReceipts"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&body)
	if body.UpToTs <= 0 {
		body.UpToTs = time.Now().UnixMilli()
	}
	n, bySender, err := a.store.MarkRead(key, body.UpToTs)
	if err != nil {
		writeErr(w, err)
		return
	}

	if body.SendReceipts && len(bySender) > 0 && a.sc.Connected() {
		// Off the request: a receipt per sender is a network round trip each, and the
		// phone is waiting to redraw a thread the user is already looking at.
		go func() {
			// Logged on success as well as failure. A feature whose only evidence is the
			// absence of an error looks identical to one that never runs.
			sent, failed := 0, 0
			for sender, stamps := range bySender {
				if err := a.sc.SendReadReceipt(sender, stamps); err != nil {
					failed++
					log.Printf("read receipt to %s...: %v", sender[:8], err)
					continue
				}
				sent++
			}
			log.Printf("read receipts: %d sent, %d failed, over %d message(s)",
				sent, failed, countStamps(bySender))
		}()
	}

	writeJSON(w, 200, map[string]any{"marked": n})
}

// describeSent turns outgoing data URIs into the little we can honestly say about them.
func describeSent(uris []string) []Attachment {
	out := make([]Attachment, 0, len(uris))
	for _, u := range uris {
		mime := "application/octet-stream"
		if i := strings.Index(u, ";"); i > 5 {
			mime = u[5:i]
		}
		payload := u
		if i := strings.Index(u, ","); i >= 0 {
			payload = u[i+1:]
		}
		// base64 length to bytes, near enough for a size label.
		out = append(out, Attachment{Type: mime, Size: int64(len(payload)) * 3 / 4})
	}
	return out
}

func countStamps(m map[string][]int64) int {
	n := 0
	for _, v := range m {
		n += len(v)
	}
	return n
}

func sseSend(w http.ResponseWriter, fl http.Flusher, event string, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event, b)
	fl.Flush()
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, err error) {
	writeJSON(w, 500, map[string]string{"error": err.Error()})
}

// redactPath replaces the identifying segment of a route with its kind, so the log says
// which route was called without saying who it was about: /v1/threads/direct:<aci>/messages
// becomes /v1/threads/direct:_/messages.
func redactPath(p string) string {
	parts := strings.Split(p, "/")
	for i, seg := range parts {
		switch {
		case strings.HasPrefix(seg, "direct:"):
			parts[i] = "direct:_"
		case strings.HasPrefix(seg, "group:"):
			parts[i] = "group:_"
		case i > 0 && parts[i-1] == "attachments" && seg != "":
			parts[i] = "_"
		}
	}
	return strings.Join(parts, "/")
}

func logging(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		h.ServeHTTP(w, r)
		// Never log query strings: the token rides in one during pairing. And never log
		// the path as given, because the path is where the identifiers are -- every
		// message fetch names a contact's ACI, every group fetch its group id. Left
		// alone that writes a timestamped record of who this person talks to and when,
		// in plaintext, into a journal that persists across reboots. The route is what
		// is useful in a log; who it was about is not.
		log.Printf("%s %s %s", r.Method, redactPath(r.URL.Path), time.Since(start).Round(time.Millisecond))
	})
}
