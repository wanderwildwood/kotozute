package main

import (
	"encoding/json"
	"path/filepath"
	"testing"
)

// A fixed clock, so "has it expired yet" is a question with one answer.
const testNow = int64(1_700_000_000_000)

func withFrozenClock(t *testing.T) {
	t.Helper()
	prev := nowMs
	nowMs = func() int64 { return testNow }
	t.Cleanup(func() { nowMs = prev })
}

func envelopeJSON(t *testing.T, dm map[string]any) json.RawMessage {
	t.Helper()
	raw, err := json.Marshal(map[string]any{
		"envelope": map[string]any{
			"source": "+15550001111", "sourceNumber": "+15550001111",
			"sourceUuid":  "aaaaaaaa-0000-0000-0000-000000000001",
			"timestamp":   testNow,
			"dataMessage": dm,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func TestExpiringMessageCarriesADeadline(t *testing.T) {
	withFrozenClock(t)
	m, _, err := normalize("self-uuid", envelopeJSON(t, map[string]any{
		"timestamp": testNow, "message": "gone in a minute", "expiresInSeconds": 60,
	}))
	if err != nil || m == nil {
		t.Fatalf("expected a message, got %v %v", m, err)
	}
	if m.ExpiresInSeconds != 60 {
		t.Errorf("timer = %d, want 60", m.ExpiresInSeconds)
	}
	if want := testNow + 60_000; m.ExpiresAt != want {
		t.Errorf("deadline = %d, want %d", m.ExpiresAt, want)
	}
}

func TestMessageWithNoTimerNeverExpires(t *testing.T) {
	withFrozenClock(t)
	m, _, _ := normalize("self-uuid", envelopeJSON(t, map[string]any{
		"timestamp": testNow, "message": "ordinary", "expiresInSeconds": 0,
	}))
	if m == nil {
		t.Fatal("expected a message")
	}
	if m.ExpiresAt != 0 {
		t.Errorf("deadline = %d, want 0 (never)", m.ExpiresAt)
	}
}

// A timer change is an event, not a message. Stored as one it is an empty bubble -- and a
// permanent record of a conversation being made impermanent.
func TestExpirationUpdateIsNotAMessage(t *testing.T) {
	withFrozenClock(t)
	m, _, err := normalize("self-uuid", envelopeJSON(t, map[string]any{
		"timestamp": testNow, "message": "", "expiresInSeconds": 300,
		"isExpirationUpdate": true,
	}))
	if err != nil {
		t.Fatal(err)
	}
	if m != nil {
		t.Errorf("stored a timer change as a message: %+v", m)
	}
}

// Signal's promise is that it can be opened once. A copy in this database is a copy that
// can be opened for ever, so the attachment is never kept -- but the row is, or the thread
// has a silent hole where a message was.
func TestViewOnceKeepsTheRowAndDropsTheAttachment(t *testing.T) {
	withFrozenClock(t)
	m, _, err := normalize("self-uuid", envelopeJSON(t, map[string]any{
		"timestamp": testNow, "message": "", "viewOnce": true,
		"attachments": []map[string]any{{"id": "att-1", "contentType": "image/jpeg"}},
	}))
	if err != nil || m == nil {
		t.Fatalf("expected a message, got %v %v", m, err)
	}
	if !m.ViewOnce {
		t.Error("not marked view-once")
	}
	if len(m.Attachments) != 0 {
		t.Errorf("kept %d attachment(s) of a view-once message", len(m.Attachments))
	}
}

func TestPurgeRemovesExpiredAndKeepsTheRest(t *testing.T) {
	withFrozenClock(t)
	store, err := OpenStore(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	insert := func(id string, expiresAt int64) {
		t.Helper()
		if _, _, err := store.InsertMessage(&Message{
			ID: id, ThreadKey: "direct:x", TS: testNow, Body: "b",
			Source: "live", ExpiresAt: expiresAt,
		}); err != nil {
			t.Fatal(err)
		}
	}
	insert("gone:1", testNow-1) // deadline passed
	insert("due:2", testNow)    // deadline is now; now is not "still in the future"
	insert("alive:3", testNow+60_000)
	insert("forever:4", 0)

	// Hidden from reads before the sweep has even run.
	before, err := store.ThreadMessages("direct:x", 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(before) != 2 {
		t.Fatalf("reads returned %d messages, want 2 (the unexpired ones)", len(before))
	}

	n, err := store.PurgeExpired()
	if err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Errorf("purged %d, want 2", n)
	}
	after, _ := store.ThreadMessages("direct:x", 0, 100)
	if len(after) != 2 {
		t.Errorf("after the sweep %d remain, want 2", len(after))
	}
	for _, m := range after {
		if m.ID == "gone:1" || m.ID == "due:2" {
			t.Errorf("%s survived its deadline", m.ID)
		}
	}
}

func TestDeleteEverythingLeavesNothing(t *testing.T) {
	withFrozenClock(t)
	store, err := OpenStore(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	if _, _, err := store.InsertMessage(&Message{
		ID: "a:1", ThreadKey: "direct:x", TS: testNow, Body: "b", Source: "live",
	}); err != nil {
		t.Fatal(err)
	}
	_ = store.SetMeta("selfUuid", "aaaa")
	if err := store.DeleteEverything(); err != nil {
		t.Fatal(err)
	}
	if msgs, _ := store.ThreadMessages("direct:x", 0, 100); len(msgs) != 0 {
		t.Errorf("%d messages survived the wipe", len(msgs))
	}
	if threads, _ := store.Threads(); len(threads) != 0 {
		t.Errorf("%d threads survived the wipe", len(threads))
	}
	if v := store.GetMeta("selfUuid"); v != "" {
		t.Errorf("account identity survived the wipe: %q", v)
	}
}
