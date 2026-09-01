package main

import (
	"encoding/json"
	"testing"
)

const self = "00000000-0000-4000-8000-00000000cafe"
const other = "00000000-0000-4000-8000-00000000beef"

func norm(t *testing.T, raw string) (*Message, *Receipt) {
	t.Helper()
	m, r, err := normalize(self, json.RawMessage(raw))
	if err != nil {
		t.Fatalf("normalize: %v", err)
	}
	return m, r
}

// A message from someone else. Must be incoming, or it never notifies and sits on the
// wrong side of the thread.
func TestIncomingDataMessage(t *testing.T) {
	m, _ := norm(t, `{"envelope":{"sourceUuid":"`+other+`","sourceNumber":"+15551234567",
		"timestamp":1000,"dataMessage":{"timestamp":1000,"message":"hello"}}}`)
	if m == nil {
		t.Fatal("expected a message")
	}
	if m.Outgoing {
		t.Error("a message from another account must not be outgoing")
	}
	if m.ThreadKey != "direct:"+other {
		t.Errorf("thread key = %q", m.ThreadKey)
	}
	if m.ID != other+":1000" {
		t.Errorf("id = %q; identity is author:timestamp", m.ID)
	}
}

// Sent from another of our own devices. Arrives as a sync, and the thread belongs to the
// destination, not the sender -- getting this wrong files our replies under ourselves.
func TestOwnSendArrivesAsSyncAndBelongsToTheRecipient(t *testing.T) {
	m, _ := norm(t, `{"envelope":{"sourceUuid":"`+self+`","timestamp":2000,
		"syncMessage":{"sentMessage":{"timestamp":2000,"message":"hi there",
		"destinationUuid":"`+other+`"}}}}`)
	if m == nil {
		t.Fatal("expected a message")
	}
	if !m.Outgoing {
		t.Error("a sent-sync must be outgoing")
	}
	if m.ThreadKey != "direct:"+other {
		t.Errorf("thread key = %q; must be the recipient", m.ThreadKey)
	}
	if !m.Read {
		t.Error("our own message is not unread")
	}
}

// Note to Self: a dataMessage whose source is our own account. The user wrote it, so it
// belongs on the outgoing side -- and must never raise a notification.
func TestNoteToSelfIsOutgoing(t *testing.T) {
	m, _ := norm(t, `{"envelope":{"sourceUuid":"`+self+`","timestamp":3000,
		"dataMessage":{"timestamp":3000,"message":"a note"}}}`)
	if m == nil {
		t.Fatal("expected a message")
	}
	if !m.Outgoing {
		t.Error("Note to Self must be outgoing")
	}
	if m.ThreadKey != "direct:"+self {
		t.Errorf("thread key = %q", m.ThreadKey)
	}
}

func TestGroupMessageKeysOnTheGroup(t *testing.T) {
	m, _ := norm(t, `{"envelope":{"sourceUuid":"`+other+`","timestamp":4000,
		"dataMessage":{"timestamp":4000,"message":"in a group",
		"groupInfo":{"groupId":"GROUPID=="}}}}`)
	if m == nil {
		t.Fatal("expected a message")
	}
	if m.ThreadKey != "group:GROUPID==" || m.GroupID != "GROUPID==" {
		t.Errorf("thread key = %q group = %q", m.ThreadKey, m.GroupID)
	}
	if m.Outgoing {
		t.Error("someone else's group message is incoming")
	}
}

func TestReceiptIsNotAMessage(t *testing.T) {
	m, r := norm(t, `{"envelope":{"sourceUuid":"`+other+`","timestamp":5000,
		"receiptMessage":{"isDelivery":true,"isRead":false,"timestamps":[4000]}}}`)
	if m != nil {
		t.Error("a receipt must not be stored as a message")
	}
	if r == nil || len(r.Timestamps) != 1 {
		t.Error("expected a receipt")
	}
}

func TestTypingAndEmptyAreDropped(t *testing.T) {
	if m, _ := norm(t, `{"envelope":{"sourceUuid":"`+other+`","timestamp":6000,
		"typingMessage":{"action":"STARTED"}}}`); m != nil {
		t.Error("typing is not a message")
	}
	// An empty body with no attachments is a reaction/edit artefact, not something to show.
	if m, _ := norm(t, `{"envelope":{"sourceUuid":"`+other+`","timestamp":7000,
		"dataMessage":{"timestamp":7000,"message":""}}}`); m != nil {
		t.Error("an empty message must be dropped")
	}
}

// A message carrying only a picture has no body, and must survive the empty-body drop.
func TestAttachmentOnlyMessageSurvives(t *testing.T) {
	m, _ := norm(t, `{"envelope":{"sourceUuid":"`+other+`","timestamp":8000,
		"dataMessage":{"timestamp":8000,"message":"",
		"attachments":[{"id":"abc.png","contentType":"image/png","size":10}]}}}`)
	if m == nil {
		t.Fatal("an attachment-only message must be kept")
	}
	if len(m.Attachments) != 1 || m.Attachments[0].ID != "abc.png" {
		t.Errorf("attachments = %+v", m.Attachments)
	}
}

// The same logical message arrives more than once. The id is what stops a duplicate.
func TestIdIsStableAcrossRedelivery(t *testing.T) {
	raw := `{"envelope":{"sourceUuid":"` + other + `","timestamp":9000,
		"dataMessage":{"timestamp":9000,"message":"twice"}}}`
	a, _ := norm(t, raw)
	b, _ := norm(t, raw)
	if a.ID != b.ID {
		t.Errorf("ids differ across redelivery: %q vs %q", a.ID, b.ID)
	}
}
