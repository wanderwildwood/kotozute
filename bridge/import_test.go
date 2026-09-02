package main

import (
	"encoding/base64"
	"encoding/hex"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// The identifier trap: the export writes an ACI as base64 of the raw 16 bytes, signal-cli
// reports the same identity as a hyphenated UUID. They are equal and they do not compare
// equal, and getting it wrong doubles a history silently.
func TestCanonicalUUIDMatchesWhatSignalCliReports(t *testing.T) {
	const canonical = "11111111-1111-4111-8111-111111111111"
	raw, err := hex.DecodeString(strings.ReplaceAll(canonical, "-", ""))
	if err != nil {
		t.Fatal(err)
	}
	b64 := base64.StdEncoding.EncodeToString(raw)

	if got := canonicalUUID(b64); got != canonical {
		t.Errorf("base64 %q -> %q, want %q", b64, got, canonical)
	}
	if got := canonicalUUID(canonical); got != canonical {
		t.Errorf("already-canonical was changed to %q", got)
	}
	if got := canonicalUUID("not-an-identifier"); got != "" {
		t.Errorf("unreadable identifier returned %q, want empty so the caller falls back", got)
	}
	if got := canonicalUUID(base64.StdEncoding.EncodeToString([]byte("short"))); got != "" {
		t.Errorf("wrong-length identifier returned %q, want empty", got)
	}
}

// A minimal export: one contact, one chat, one message each way, plus the record kinds the
// importer must refuse to turn into messages.
func writeExport(t *testing.T, dir string, selfRecipientID string) {
	t.Helper()
	const theirACI = "11111111-1111-4111-8111-111111111111"
	raw, _ := hex.DecodeString(strings.ReplaceAll(theirACI, "-", ""))
	b64 := base64.StdEncoding.EncodeToString(raw)

	lines := []string{
		`{"account":{"givenName":"Me"}}`,
		`{"recipient":{"id":"` + selfRecipientID + `","self":{}}}`,
		`{"recipient":{"id":"2","contact":{"aci":"` + b64 + `","e164":"+15550001111",` +
			`"systemGivenName":"Ada","systemFamilyName":"Lovelace"}}}`,
		`{"chat":{"id":"10","recipientId":"2"}}`,
		`{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1700000000000",` +
			`"incoming":{"dateReceived":"1700000000100","read":true},` +
			`"standardMessage":{"text":{"body":"hello"}}}}`,
		`{"chatItem":{"chatId":"10","authorId":"` + selfRecipientID + `","dateSent":"1700000001000",` +
			`"outgoing":{"dateReceived":"1700000001100"},` +
			`"standardMessage":{"text":{"body":"hi back"}}}}`,
		// Not messages: an event, and a tombstone for something deleted for everyone.
		`{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1700000002000",` +
			`"updateMessage":{"simpleUpdate":{"type":"IDENTITY_UPDATE"}}}}`,
		`{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1700000003000",` +
			`"remoteDeletedMessage":{}}}`,
	}
	if err := os.WriteFile(filepath.Join(dir, "main.jsonl"),
		[]byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestImportReadsBothDirectionsAndRefusesNonMessages(t *testing.T) {
	withFrozenClock(t)
	exportDir := t.TempDir()
	writeExport(t, exportDir, "1")

	store, err := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	attach := t.TempDir()

	const selfACI = "00000000-0000-4000-8000-000000000000"
	st, err := ImportExport(store, exportDir, attach, selfACI, "+15559998888")
	if err != nil {
		t.Fatal(err)
	}
	if st.Messages != 2 {
		t.Errorf("imported %d messages, want 2 -- %s", st.Messages, st)
	}
	if st.SkippedUpdates != 1 || st.SkippedDeleted != 1 {
		t.Errorf("events/tombstones: %d/%d, want 1/1", st.SkippedUpdates, st.SkippedDeleted)
	}
	if st.SkippedNoAuthor != 0 || st.SkippedNoThread != 0 {
		t.Errorf("dropped messages: %d without an author, %d without a thread",
			st.SkippedNoAuthor, st.SkippedNoThread)
	}

	msgs, err := store.ThreadMessages("direct:11111111-1111-4111-8111-111111111111", 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 2 {
		t.Fatalf("thread holds %d messages, want 2", len(msgs))
	}
	var sent, received *Message
	for _, m := range msgs {
		if m.Outgoing {
			sent = m
		} else {
			received = m
		}
	}
	if sent == nil || received == nil {
		t.Fatal("expected one message each way")
	}
	// The author of an outgoing message is this account, so the id must be built from the
	// self uuid -- the export has no identifier for the account at all.
	if want := selfACI + ":1700000001000"; sent.ID != want {
		t.Errorf("outgoing id = %q, want %q", sent.ID, want)
	}
	// And an incoming one from the contact's ACI, canonicalised.
	if want := "11111111-1111-4111-8111-111111111111:1700000000000"; received.ID != want {
		t.Errorf("incoming id = %q, want %q", received.ID, want)
	}
	// Imported history has been seen; unread would raise notifications for old messages.
	if !received.Read {
		t.Error("imported message is unread")
	}
	if received.Source != "import" {
		t.Errorf("source = %q, want import", received.Source)
	}
}

// The import runs next to live data and may be run twice. Neither may duplicate.
func TestImportIsIdempotentAndDoesNotDuplicateLiveMessages(t *testing.T) {
	withFrozenClock(t)
	exportDir := t.TempDir()
	writeExport(t, exportDir, "1")

	store, err := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	attach := t.TempDir()
	const selfACI = "00000000-0000-4000-8000-000000000000"

	// A message already received live, with the id normalize() would have given it.
	if _, _, err := store.InsertMessage(&Message{
		ID:        "11111111-1111-4111-8111-111111111111:1700000000000",
		ThreadKey: "direct:11111111-1111-4111-8111-111111111111",
		TS:        1700000000000, Body: "hello", Source: "live",
	}); err != nil {
		t.Fatal(err)
	}

	first, err := ImportExport(store, exportDir, attach, selfACI, "+15559998888")
	if err != nil {
		t.Fatal(err)
	}
	if first.Messages != 1 || first.AlreadyPresent != 1 {
		t.Errorf("first import: %d new, %d already present; want 1 and 1 -- %s",
			first.Messages, first.AlreadyPresent, first)
	}

	second, err := ImportExport(store, exportDir, attach, selfACI, "+15559998888")
	if err != nil {
		t.Fatal(err)
	}
	if second.Messages != 0 || second.AlreadyPresent != 2 {
		t.Errorf("second import: %d new, %d already present; want 0 and 2",
			second.Messages, second.AlreadyPresent)
	}

	msgs, _ := store.ThreadMessages("direct:11111111-1111-4111-8111-111111111111", 0, 50)
	if len(msgs) != 2 {
		t.Errorf("thread holds %d messages after two imports over live data, want 2", len(msgs))
	}
}

// A message whose disappearing deadline has passed is not history; importing it would undo
// the sender's choice.
func TestImportSkipsAlreadyExpiredMessages(t *testing.T) {
	withFrozenClock(t)
	exportDir := t.TempDir()
	const theirACI = "11111111-1111-4111-8111-111111111111"
	raw, _ := hex.DecodeString(strings.ReplaceAll(theirACI, "-", ""))
	b64 := base64.StdEncoding.EncodeToString(raw)

	lines := []string{
		`{"recipient":{"id":"1","self":{}}}`,
		`{"recipient":{"id":"2","contact":{"aci":"` + b64 + `","e164":"+15550001111"}}}`,
		`{"chat":{"id":"10","recipientId":"2"}}`,
		// started long ago, one minute to live: long gone by the frozen clock
		`{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1600000000000",` +
			`"expireStartDate":"1600000000000","expiresInMs":"60000",` +
			`"incoming":{"dateReceived":"1600000000000","read":true},` +
			`"standardMessage":{"text":{"body":"should be gone"}}}}`,
		// a live timer that has not run out yet
		`{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1700000000000",` +
			`"expireStartDate":"` + strconv.FormatInt(testNow, 10) + `","expiresInMs":"600000",` +
			`"incoming":{"dateReceived":"1700000000000","read":true},` +
			`"standardMessage":{"text":{"body":"still here"}}}}`,
	}
	if err := os.WriteFile(filepath.Join(exportDir, "main.jsonl"),
		[]byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	store, _ := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	defer store.Close()
	st, err := ImportExport(store, exportDir, t.TempDir(),
		"00000000-0000-4000-8000-000000000000", "+15559998888")
	if err != nil {
		t.Fatal(err)
	}
	if st.SkippedExpired != 1 {
		t.Errorf("skipped %d expired, want 1 -- %s", st.SkippedExpired, st)
	}
	if st.Messages != 1 {
		t.Errorf("imported %d, want 1 (the one still counting down)", st.Messages)
	}
	msgs, _ := store.ThreadMessages("direct:"+theirACI, 0, 50)
	if len(msgs) != 1 || msgs[0].ExpiresAt == 0 {
		t.Errorf("the surviving message should keep its deadline: %+v", msgs)
	}
}

func TestImportRefusesAFolderThatIsNotAnExport(t *testing.T) {
	store, _ := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	defer store.Close()
	_, err := ImportExport(store, t.TempDir(), t.TempDir(), "self", "+1")
	if err == nil {
		t.Error("expected an error naming what is missing, got nil")
	}
	if err != nil && !strings.Contains(err.Error(), "main.jsonl") {
		t.Errorf("error should say what is missing, got: %v", err)
	}
}
