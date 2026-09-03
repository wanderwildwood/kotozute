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

	n, _, err := store.PurgeExpired()
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

// Keeping a view-once attachment out of the database was never enough on its own: the file
// signal-cli had already written was still on disk, under an id the raw envelope preserved.
func TestViewOnceNamesItsFilesForRemovalAndKeepsNoRaw(t *testing.T) {
	withFrozenClock(t)
	m, _, err := normalize("self-uuid", envelopeJSON(t, map[string]any{
		"timestamp": testNow, "message": "", "viewOnce": true,
		"attachments": []map[string]any{
			{"id": "abc123.jpg", "contentType": "image/jpeg"},
			{"id": "def456.jpg", "contentType": "image/jpeg"},
		},
	}))
	if err != nil || m == nil {
		t.Fatalf("expected a message, got %v %v", m, err)
	}
	if len(m.Attachments) != 0 {
		t.Errorf("stored %d attachment(s) of a view-once message", len(m.Attachments))
	}
	if len(m.ViewOnceFiles) != 2 {
		t.Fatalf("named %d files for removal, want 2", len(m.ViewOnceFiles))
	}
	// The raw envelope names the file. Keeping it puts the id of something meant to be seen
	// once into a column anything that can read the store can read.
	if m.Raw != "" {
		t.Errorf("raw envelope kept for a view-once message: %q", m.Raw[:min(80, len(m.Raw))])
	}
}

// An ordinary message keeps its raw envelope and names nothing for deletion.
func TestOrdinaryMessageKeepsRawAndDeletesNothing(t *testing.T) {
	withFrozenClock(t)
	m, _, _ := normalize("self-uuid", envelopeJSON(t, map[string]any{
		"timestamp": testNow, "message": "ordinary",
		"attachments": []map[string]any{{"id": "keep.jpg", "contentType": "image/jpeg"}},
	}))
	if m == nil {
		t.Fatal("expected a message")
	}
	if len(m.ViewOnceFiles) != 0 {
		t.Errorf("named %v for deletion on an ordinary message", m.ViewOnceFiles)
	}
	if m.Raw == "" {
		t.Error("raw envelope dropped from an ordinary message")
	}
	if len(m.Attachments) != 1 {
		t.Errorf("kept %d attachments, want 1", len(m.Attachments))
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// The send path cannot know the thread's timer -- signal-cli applies that on the wire. The
// sync echo does know, and has the same id, so INSERT OR IGNORE threw away the only copy
// that carried a deadline. The sender's copy then outlived every message they sent.
func TestASyncEchoCanFillInAMissingDeadline(t *testing.T) {
	withFrozenClock(t)
	store, err := OpenStore(filepath.Join(t.TempDir(), "s.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	const id = "self:1700000000000"
	// What the send path stores: no deadline, because it has no way to know one.
	if _, _, err := store.InsertMessage(&Message{
		ID: id, ThreadKey: "direct:x", TS: 1700000000000,
		Outgoing: true, Body: "gone in a minute", Source: "live", Read: true,
	}); err != nil {
		t.Fatal(err)
	}
	// What comes back from signal-cli moments later, for the same message.
	if _, _, err := store.InsertMessage(&Message{
		ID: id, ThreadKey: "direct:x", TS: 1700000000000,
		Outgoing: true, Body: "gone in a minute", Source: "live", Read: true,
		ExpiresInSeconds: 60, ExpiresAt: testNow + 60_000,
	}); err != nil {
		t.Fatal(err)
	}

	msgs, err := store.ThreadMessages("direct:x", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 {
		t.Fatalf("stored %d copies, want 1", len(msgs))
	}
	if msgs[0].ExpiresAt != testNow+60_000 {
		t.Errorf("expiresAt = %d, want %d -- the sender's copy never expires",
			msgs[0].ExpiresAt, testNow+60_000)
	}
	if msgs[0].ExpiresInSeconds != 60 {
		t.Errorf("expiresIn = %d, want 60", msgs[0].ExpiresInSeconds)
	}
}

// A deadline already recorded is never moved, or this would be a way to extend one.
func TestADeadlineAlreadySetIsNeverExtended(t *testing.T) {
	withFrozenClock(t)
	store, _ := OpenStore(filepath.Join(t.TempDir(), "s.db"))
	defer store.Close()
	const id = "them:1700000000000"
	if _, _, err := store.InsertMessage(&Message{
		ID: id, ThreadKey: "direct:x", TS: 1700000000000, Body: "b", Source: "live",
		ExpiresInSeconds: 30, ExpiresAt: testNow + 30_000,
	}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.InsertMessage(&Message{
		ID: id, ThreadKey: "direct:x", TS: 1700000000000, Body: "b", Source: "live",
		ExpiresInSeconds: 86400, ExpiresAt: testNow + 86_400_000,
	}); err != nil {
		t.Fatal(err)
	}
	msgs, _ := store.ThreadMessages("direct:x", 0, 10)
	if len(msgs) != 1 || msgs[0].ExpiresAt != testNow+30_000 {
		t.Errorf("a recorded deadline was moved: %+v", msgs)
	}
}

// Deleting the row was never the whole job: the picture stayed on disk, fetchable by id
// through the attachment route long after the message had expired. And a thread whose
// unread message expired kept counting it.
func TestPurgeNamesItsFilesAndRecountsTheThread(t *testing.T) {
	withFrozenClock(t)
	store, err := OpenStore(filepath.Join(t.TempDir(), "p.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	key := "direct:11111111-1111-4111-8111-111111111111"

	// One expiring, unread, with a picture; one ordinary and unread.
	if _, _, err := store.InsertMessage(&Message{
		ID: "a:1", ThreadKey: key, TS: testNow, Body: "going", Source: "live",
		ExpiresAt: testNow - 1, ExpiresInSeconds: 30,
		Attachments: []Attachment{{ID: "pic.jpg", Type: "image/jpeg"}},
	}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.InsertMessage(&Message{
		ID: "a:2", ThreadKey: key, TS: testNow, Body: "staying", Source: "live",
	}); err != nil {
		t.Fatal(err)
	}

	before, err := store.Threads()
	if err != nil {
		t.Fatal(err)
	}
	if len(before) != 1 || before[0].Unread != 2 {
		t.Fatalf("expected 2 unread before the purge, got %+v", before)
	}

	n, files, err := store.PurgeExpired()
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Errorf("purged %d, want 1", n)
	}
	if len(files) != 1 || files[0] != "pic.jpg" {
		t.Errorf("files to delete = %v, want [pic.jpg] -- the picture stays on disk otherwise", files)
	}
	after, _ := store.Threads()
	if len(after) != 1 || after[0].Unread != 1 {
		t.Errorf("unread after the purge = %v, want 1 -- the expired message is still counted", after)
	}
}

// A blank title means "not resolved yet", not "this thread has no name".
func TestABlankTitleDoesNotEraseAKnownOne(t *testing.T) {
	withFrozenClock(t)
	store, _ := OpenStore(filepath.Join(t.TempDir(), "t.db"))
	defer store.Close()
	key := "direct:11111111-1111-4111-8111-111111111111"
	if err := store.SetThreadMeta(key, "direct", "Ada Lovelace", nil); err != nil {
		t.Fatal(err)
	}
	if err := store.SetThreadMeta(key, "direct", "", nil); err != nil {
		t.Fatal(err)
	}
	rows, _ := store.Threads()
	if len(rows) != 1 || rows[0].Title != "Ada Lovelace" {
		t.Errorf("title = %q, want it kept -- a blank overwrote a known name", rows[0].Title)
	}
}
