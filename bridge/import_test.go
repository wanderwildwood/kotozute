package main

import (
	"encoding/base64"
	"encoding/hex"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
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

// The claim export makes is that what comes out goes back in. Anything less and it is not a
// backup, it is a file that looks like one.
func TestExportRoundTripsThroughImport(t *testing.T) {
	withFrozenClock(t)
	const selfACI = "00000000-0000-4000-8000-000000000000"
	const theirACI = "11111111-1111-4111-8111-111111111111"

	origin, err := OpenStore(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer origin.Close()

	// A thread with a message each way, and a title to carry across.
	if err := origin.SetThreadMeta("direct:"+theirACI, "direct", "Ada Lovelace", nil); err != nil {
		t.Fatal(err)
	}
	want := []*Message{
		{
			ID: theirACI + ":1700000000000", ThreadKey: "direct:" + theirACI,
			TS: 1700000000000, SenderUUID: theirACI, Body: "hello", Source: "live", Read: true,
		},
		{
			ID: selfACI + ":1700000001000", ThreadKey: "direct:" + theirACI,
			TS: 1700000001000, SenderUUID: selfACI, Outgoing: true, Body: "hi back",
			Source: "live", Read: true,
		},
	}
	for _, m := range want {
		if _, _, err := origin.InsertMessage(m); err != nil {
			t.Fatal(err)
		}
	}

	dir := t.TempDir()
	attach := t.TempDir()
	exported, err := ExportStore(origin, dir, attach, selfACI)
	if err != nil {
		t.Fatal(err)
	}
	if exported.Messages != 2 || exported.Threads != 1 {
		t.Fatalf("exported %d messages in %d threads, want 2 and 1", exported.Messages, exported.Threads)
	}

	// Back into a store that has never seen any of it.
	fresh, err := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Close()
	imported, err := ImportExport(fresh, dir, t.TempDir(), selfACI, "+15559998888")
	if err != nil {
		t.Fatal(err)
	}
	if imported.Messages != 2 {
		t.Fatalf("imported %d, want 2 -- %s", imported.Messages, imported)
	}

	got, err := fresh.ThreadMessages("direct:"+theirACI, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Fatalf("round trip produced %d messages, want 2", len(got))
	}
	byID := map[string]*Message{}
	for _, m := range got {
		byID[m.ID] = m
	}
	for _, w := range want {
		g, ok := byID[w.ID]
		if !ok {
			t.Errorf("%s did not survive the round trip; got %v", w.ID, byID)
			continue
		}
		if g.Body != w.Body {
			t.Errorf("%s body = %q, want %q", w.ID, g.Body, w.Body)
		}
		if g.Outgoing != w.Outgoing {
			t.Errorf("%s outgoing = %v, want %v", w.ID, g.Outgoing, w.Outgoing)
		}
		if g.TS != w.TS {
			t.Errorf("%s ts = %d, want %d", w.ID, g.TS, w.TS)
		}
	}
}

// The group case is the one that broke twice: first because a group's key cannot be derived
// from Signal's own export, then because the author of a group message is a member rather
// than the thread's other party, which left every incoming one with no identity.
func TestExportRoundTripsAGroupWithSeveralSenders(t *testing.T) {
	withFrozenClock(t)
	const selfACI = "00000000-0000-4000-8000-000000000000"
	const alice = "11111111-1111-4111-8111-111111111111"
	const bob = "22222222-2222-4222-8222-222222222222"
	// A real signal-cli group id: base64, and it contains a slash.
	const groupKey = "group:AAAAAAAAAAAAAAAAAAAAAA/BBBBBBBBBBBBBBBBBBBBBB="

	origin, err := OpenStore(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer origin.Close()
	if err := origin.SetThreadMeta(groupKey, "group", "Bridge Club", nil); err != nil {
		t.Fatal(err)
	}
	for i, sender := range []string{alice, bob, selfACI} {
		ts := int64(1700000000000 + i*1000)
		m := &Message{
			ID: sender + ":" + strconv.FormatInt(ts, 10), ThreadKey: groupKey, TS: ts,
			SenderUUID: sender, Body: "in the group", Source: "live", Read: true,
			Outgoing: sender == selfACI, GroupID: strings.TrimPrefix(groupKey, "group:"),
		}
		if _, _, err := origin.InsertMessage(m); err != nil {
			t.Fatal(err)
		}
	}

	dir := t.TempDir()
	if _, err := ExportStore(origin, dir, t.TempDir(), selfACI); err != nil {
		t.Fatal(err)
	}

	// A store that has never seen this group: nothing to match a title against, which is
	// exactly the restore case.
	fresh, err := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Close()
	st, err := ImportExport(fresh, dir, t.TempDir(), selfACI, "+15559998888")
	if err != nil {
		t.Fatal(err)
	}
	if st.Messages != 3 {
		t.Fatalf("imported %d of 3 -- %s", st.Messages, st)
	}
	got, err := fresh.ThreadMessages(groupKey, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 {
		t.Fatalf("group holds %d messages after the round trip, want 3", len(got))
	}
	// Each sender kept their own identity, rather than collapsing into the group.
	senders := map[string]bool{}
	for _, m := range got {
		senders[m.SenderUUID] = true
	}
	for _, want := range []string{alice, bob, selfACI} {
		if !senders[want] {
			t.Errorf("sender %s did not survive; got %v", want, senders)
		}
	}
}

// A chatItem missing dateSent imports with ts = 0. Paging an export backwards by timestamp
// then pins the cursor at 0 for ever -- the backup an operator takes right before something
// irreversible never completes, at 100% CPU, with no error.
func TestExportTerminatesWithATimestampOfZero(t *testing.T) {
	withFrozenClock(t)
	store, err := OpenStore(filepath.Join(t.TempDir(), "z.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if err := store.SetThreadMeta("direct:"+strings.Repeat("1", 8)+"-1111-4111-8111-111111111111",
		"direct", "Ada Lovelace", nil); err != nil {
		t.Fatal(err)
	}
	key := "direct:11111111-1111-4111-8111-111111111111"
	for _, m := range []*Message{
		{ID: "a:0", ThreadKey: key, TS: 0, Body: "no timestamp", Source: "import", Read: true},
		{ID: "a:1", ThreadKey: key, TS: 1700000000000, Body: "ordinary", Source: "import", Read: true},
	} {
		if _, _, err := store.InsertMessage(m); err != nil {
			t.Fatal(err)
		}
	}

	done := make(chan ExportStats, 1)
	errc := make(chan error, 1)
	go func() {
		st, err := ExportStore(store, t.TempDir(), t.TempDir(), "00000000-0000-4000-8000-000000000000")
		if err != nil {
			errc <- err
			return
		}
		done <- st
	}()
	select {
	case err := <-errc:
		t.Fatal(err)
	case st := <-done:
		if st.Messages != 2 {
			t.Errorf("exported %d messages, want 2", st.Messages)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("ExportStore did not terminate with a ts of 0 in the store")
	}
}

// Two messages can share a timestamp -- ordinary in a group. A strict `ts <` page boundary
// silently drops whichever of them did not end the page.
func TestExportKeepsMessagesSharingATimestamp(t *testing.T) {
	withFrozenClock(t)
	store, err := OpenStore(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	key := "direct:11111111-1111-4111-8111-111111111111"
	if err := store.SetThreadMeta(key, "direct", "Ada Lovelace", nil); err != nil {
		t.Fatal(err)
	}
	// 502 messages. The old export paged backwards by timestamp 500 at a time, so the tie
	// has to sit at descending positions 499 and 500 -- exactly the page edge -- for the
	// boundary to cut between them. Anywhere else and both land on the same page and the
	// bug does not show.
	const total = 502
	base := int64(1700000000000)
	for i := 0; i < total; i++ {
		var ts int64
		switch {
		case i < 499:
			ts = base + int64(total-i) // strictly descending: positions 0..498
		case i == 499 || i == 500:
			ts = base + 2 // the tie, at descending positions 499 and 500
		default:
			ts = base + 1 // strictly below the tie
		}
		if _, _, err := store.InsertMessage(&Message{
			ID: "a:" + strconv.Itoa(i), ThreadKey: key, TS: ts,
			Body: "m" + strconv.Itoa(i), Source: "live", Read: true,
		}); err != nil {
			t.Fatal(err)
		}
	}
	st, err := ExportStore(store, t.TempDir(), t.TempDir(), "00000000-0000-4000-8000-000000000000")
	if err != nil {
		t.Fatal(err)
	}
	if st.Messages != total {
		t.Errorf("exported %d of %d messages -- a tie at the page boundary was dropped", st.Messages, total)
	}
}

// Size alone cannot tell two attachments of equal length apart. The importer dropped both,
// and with them any message whose only content was the attachment.
func TestRoundTripKeepsTwoAttachmentsOfEqualSize(t *testing.T) {
	withFrozenClock(t)
	const selfACI = "00000000-0000-4000-8000-000000000000"
	const theirACI = "11111111-1111-4111-8111-111111111111"
	key := "direct:" + theirACI

	attach := t.TempDir()
	// Two DIFFERENT files of identical length, as two screenshots would be.
	for i, name := range []string{"one.png", "two.png"} {
		if err := os.WriteFile(filepath.Join(attach, name),
			[]byte(strings.Repeat(string(rune('a'+i)), 10)), 0o600); err != nil {
			t.Fatal(err)
		}
	}

	origin, err := OpenStore(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer origin.Close()
	if err := origin.SetThreadMeta(key, "direct", "Ada Lovelace", nil); err != nil {
		t.Fatal(err)
	}
	for i, name := range []string{"one.png", "two.png"} {
		ts := int64(1700000000000 + i)
		// No body: the message IS the attachment, so losing the file loses the message.
		if _, _, err := origin.InsertMessage(&Message{
			ID: theirACI + ":" + strconv.FormatInt(ts, 10), ThreadKey: key, TS: ts,
			SenderUUID: theirACI, Source: "live", Read: true,
			Attachments: []Attachment{{ID: name, Type: "image/png", Size: 10}},
		}); err != nil {
			t.Fatal(err)
		}
	}

	dir := t.TempDir()
	st, err := ExportStore(origin, dir, attach, selfACI)
	if err != nil {
		t.Fatal(err)
	}
	if st.Attachments != 2 {
		t.Fatalf("exported %d attachments, want 2 -- %s", st.Attachments, st)
	}

	fresh, err := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Close()
	back, err := ImportExport(fresh, dir, t.TempDir(), selfACI, "+15559998888")
	if err != nil {
		t.Fatal(err)
	}
	if back.Messages != 2 {
		t.Errorf("imported %d of 2 messages -- equal-sized attachments took them with them; %s",
			back.Messages, back)
	}
	if back.AttachmentsLost != 0 {
		t.Errorf("%d attachments unmatched, want 0", back.AttachmentsLost)
	}
}

// A reply's link to what it answers is part of the conversation.
func TestRoundTripKeepsQuoteLinks(t *testing.T) {
	withFrozenClock(t)
	const selfACI = "00000000-0000-4000-8000-000000000000"
	const theirACI = "11111111-1111-4111-8111-111111111111"
	key := "direct:" + theirACI

	origin, _ := OpenStore(filepath.Join(t.TempDir(), "a.db"))
	defer origin.Close()
	_ = origin.SetThreadMeta(key, "direct", "Ada Lovelace", nil)
	if _, _, err := origin.InsertMessage(&Message{
		ID: theirACI + ":1700000000000", ThreadKey: key, TS: 1700000000000,
		SenderUUID: theirACI, Body: "the question", Source: "live", Read: true,
	}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := origin.InsertMessage(&Message{
		ID: selfACI + ":1700000001000", ThreadKey: key, TS: 1700000001000,
		SenderUUID: selfACI, Outgoing: true, Body: "the answer", Source: "live", Read: true,
		QuoteTS: 1700000000000,
	}); err != nil {
		t.Fatal(err)
	}

	dir := t.TempDir()
	if _, err := ExportStore(origin, dir, t.TempDir(), selfACI); err != nil {
		t.Fatal(err)
	}
	fresh, _ := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	defer fresh.Close()
	if _, err := ImportExport(fresh, dir, t.TempDir(), selfACI, "+15559998888"); err != nil {
		t.Fatal(err)
	}
	msgs, _ := fresh.ThreadMessages(key, 0, 50)
	var reply *Message
	for _, m := range msgs {
		if m.Body == "the answer" {
			reply = m
		}
	}
	if reply == nil {
		t.Fatal("the reply did not survive the round trip")
	}
	if reply.QuoteTS != 1700000000000 {
		t.Errorf("quoteTs = %d, want 1700000000000 -- the reply lost what it was answering",
			reply.QuoteTS)
	}
}

// Retention prunes old attachments on purpose. A message whose only content was the
// attachment then looked empty, and empty messages are discarded on import -- so the
// message vanished from the backup, not merely its picture.
func TestAMessageSurvivesItsAttachmentBeingPruned(t *testing.T) {
	withFrozenClock(t)
	const selfACI = "00000000-0000-4000-8000-000000000000"
	const theirACI = "11111111-1111-4111-8111-111111111111"
	key := "direct:" + theirACI

	origin, err := OpenStore(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer origin.Close()
	if err := origin.SetThreadMeta(key, "direct", "Ada Lovelace", nil); err != nil {
		t.Fatal(err)
	}
	// No body, and the attachment file does not exist -- retention took it.
	if _, _, err := origin.InsertMessage(&Message{
		ID: theirACI + ":1700000000000", ThreadKey: key, TS: 1700000000000,
		SenderUUID: theirACI, Source: "live", Read: true,
		Attachments: []Attachment{{ID: "gone.jpg", Type: "image/jpeg", Size: 4242}},
	}); err != nil {
		t.Fatal(err)
	}

	dir := t.TempDir()
	if _, err := ExportStore(origin, dir, t.TempDir() /* empty attachment dir */, selfACI); err != nil {
		t.Fatal(err)
	}
	fresh, err := OpenStore(filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Close()
	st, err := ImportExport(fresh, dir, t.TempDir(), selfACI, "+15559998888")
	if err != nil {
		t.Fatal(err)
	}
	if st.Messages != 1 {
		t.Fatalf("imported %d of 1 -- the message went with its pruned attachment; %s", st.Messages, st)
	}
	msgs, _ := fresh.ThreadMessages(key, 0, 10)
	if len(msgs) != 1 {
		t.Fatalf("store holds %d messages, want 1", len(msgs))
	}
	if len(msgs[0].Attachments) != 1 {
		t.Fatalf("the surviving message lost its attachment reference entirely")
	}
	if msgs[0].Attachments[0].ID != "" {
		t.Errorf("id = %q, want empty -- there is no file to fetch", msgs[0].Attachments[0].ID)
	}
	if msgs[0].Attachments[0].Type != "image/jpeg" {
		t.Errorf("content type lost: %q", msgs[0].Attachments[0].Type)
	}
}
