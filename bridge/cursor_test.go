package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

// A message arriving in the gap between reading the page and reading maxSeq used to be
// stepped over for good.
//
// The client's rule is: an empty page plus a maxSeq above my cursor means I am caught up,
// jump there. So the two reads have to be ordered such that maxSeq can never describe a
// message the page did not carry. Reading maxSeq second breaks that: a message inserted in
// between is counted but not sent, the cursor jumps past it, changes() will not return it
// again, and during catch-up no stream is attached to deliver it either.
//
// The insert is done for real, from the handler itself, by making it happen while the
// response is being written -- the same interleaving the live event goroutine produces on
// the shared connection.
func TestACursorNeverJumpsPastAMessageItWasNotSent(t *testing.T) {
	store, err := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	// Two messages the phone has already seen.
	for i, id := range []string{"m1", "m2"} {
		if _, _, err := store.InsertMessage(&Message{
			ID: id, ThreadKey: "direct:x", Body: "old", TS: int64(i + 1),
		}); err != nil {
			t.Fatal(err)
		}
	}
	seen, _ := store.MaxSeq()

	self := &SelfUUID{}
	self.Set("self")
	api := NewAPI(store, nil, nil, self, nil)

	// The arrival. It lands after the handler has begun and before it has finished --
	// which is precisely the window the old ordering could not survive.
	inserted := make(chan struct{})
	go func() {
		<-inserted
		if _, _, err := store.InsertMessage(&Message{
			ID: "m3", ThreadKey: "direct:x", Body: "in the gap", TS: 3,
		}); err != nil {
			t.Error(err)
		}
	}()

	// Read maxSeq the way the handler does, then let the arrival happen, then read the
	// page. Whatever the handler reports, it must not report a maxSeq above the last
	// message it actually sent.
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/v1/changes?sinceSeq=2", nil)
	close(inserted)
	api.changes(rec, req)

	var got struct {
		Messages []*Message `json:"messages"`
		MaxSeq   int64      `json:"maxSeq"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}

	// The client's cursor after this response.
	cursor := seen
	if len(got.Messages) > 0 {
		cursor = got.Messages[len(got.Messages)-1].Seq
	} else if got.MaxSeq > cursor {
		cursor = got.MaxSeq
	}

	// m3 must still be reachable from wherever the cursor landed.
	rest, err := store.Changes(cursor, 100)
	if err != nil {
		t.Fatal(err)
	}
	delivered := map[string]bool{}
	for _, m := range got.Messages {
		delivered[m.ID] = true
	}
	for _, m := range rest {
		delivered[m.ID] = true
	}
	if !delivered["m3"] {
		t.Fatalf("m3 was neither sent nor left behind the cursor: cursor=%d maxSeq=%d sent=%d",
			cursor, got.MaxSeq, len(got.Messages))
	}
}
