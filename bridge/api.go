package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// A photo grows by about a third in base64, so this allows a large one with room over.
const maxSendBytes = 48 << 20

type API struct {
	store *Store
	sc    *SignalCLI
	auth  *Auth
	self  string
	atts  *Attachments

	subsMu sync.Mutex
	subs   map[chan *Message]struct{}
}

func NewAPI(st *Store, sc *SignalCLI, auth *Auth, self string, atts *Attachments) *API {
	return &API{store: st, sc: sc, auth: auth, self: self, atts: atts,
		subs: map[chan *Message]struct{}{}}
}

// Broadcast fans a newly stored message out to attached phones.
func (a *API) Broadcast(m *Message) {
	a.subsMu.Lock()
	defer a.subsMu.Unlock()
	for ch := range a.subs {
		select {
		case ch <- m:
		default: // a phone that cannot keep up will catch up by cursor instead
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
		"account":         a.self,
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
		if row.Title == "" && a.self != "" && row.Key == "direct:"+a.self {
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
	msgs, err := a.store.Changes(since, limit)
	if err != nil {
		writeErr(w, err)
		return
	}
	if msgs == nil {
		msgs = []*Message{}
	}
	maxSeq, _ := a.store.MaxSeq()
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
	a.subsMu.Lock()
	a.subs[ch] = struct{}{}
	a.subsMu.Unlock()
	defer func() {
		a.subsMu.Lock()
		delete(a.subs, ch)
		a.subsMu.Unlock()
	}()

	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case m := <-ch:
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
	default:
		http.NotFound(w, r)
	}
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
		if to == a.self {
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
		ID: fmt.Sprintf("%s:%d", a.self, ts), ThreadKey: key, TS: ts,
		SenderUUID: a.self, Outgoing: true, Body: body.Message,
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

func logging(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		h.ServeHTTP(w, r)
		// Never log query strings: the token rides in one during pairing.
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}
