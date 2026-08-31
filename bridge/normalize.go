package main

import (
	"encoding/json"
	"fmt"
)

// Message is the one shape the app ever sees. Everything awkward about the wire
// format is resolved here so the app cannot get it wrong.
type Message struct {
	Seq          int64        `json:"seq"`
	ID           string       `json:"id"`
	ThreadKey    string       `json:"threadKey"`
	TS           int64        `json:"ts"`
	SenderUUID   string       `json:"senderUuid,omitempty"`
	SenderNumber string       `json:"senderNumber,omitempty"`
	Outgoing     bool         `json:"outgoing"`
	Body         string       `json:"body"`
	GroupID      string       `json:"groupId,omitempty"`
	QuoteTS      int64        `json:"quoteTs,omitempty"`
	Attachments  []Attachment `json:"attachments,omitempty"`
	Read         bool         `json:"read"`
	Source       string       `json:"source"` // "live" | "import"
	Raw          string       `json:"-"`
}

type Attachment struct {
	ID       string `json:"id"`
	Type     string `json:"contentType,omitempty"`
	Filename string `json:"filename,omitempty"`
	Size     int64  `json:"size,omitempty"`
}

// Receipt is a delivery/read acknowledgement, not a message.
type Receipt struct {
	SenderUUID string
	Timestamps []int64
	IsRead     bool
}

type envelope struct {
	Source       string `json:"source"`
	SourceNumber string `json:"sourceNumber"`
	SourceUUID   string `json:"sourceUuid"`
	Timestamp    int64  `json:"timestamp"`

	DataMessage *dataMessage `json:"dataMessage"`
	SyncMessage *struct {
		SentMessage *struct {
			dataMessage
			DestinationUUID   string `json:"destinationUuid"`
			DestinationNumber string `json:"destinationNumber"`
		} `json:"sentMessage"`
	} `json:"syncMessage"`
	ReceiptMessage *struct {
		IsDelivery bool    `json:"isDelivery"`
		IsRead     bool    `json:"isRead"`
		Timestamps []int64 `json:"timestamps"`
	} `json:"receiptMessage"`
}

type dataMessage struct {
	Timestamp   int64  `json:"timestamp"`
	Message     string `json:"message"`
	GroupInfo   *struct {
		GroupID string `json:"groupId"`
	} `json:"groupInfo"`
	Quote *struct {
		ID int64 `json:"id"`
	} `json:"quote"`
	Attachments []struct {
		ID          string `json:"id"`
		ContentType string `json:"contentType"`
		Filename    string `json:"filename"`
		Size        int64  `json:"size"`
	} `json:"attachments"`
}

// normalize turns one signal-cli envelope into at most one Message plus any
// receipts. It returns nil,nil for things we deliberately drop (typing, empty
// sync/config traffic).
//
// The subtlety that costs you a broken thread if you miss it: a message the user
// sent from another device arrives as syncMessage.sentMessage with the *recipient*
// in destinationUuid, while everyone else's arrives as dataMessage with the
// *sender* in sourceUuid. Both are real messages in the same thread.
func normalize(selfUUID string, raw json.RawMessage) (*Message, *Receipt, error) {
	var wrap struct {
		Envelope envelope `json:"envelope"`
		Account  string   `json:"account"`
	}
	if err := json.Unmarshal(raw, &wrap); err != nil {
		return nil, nil, err
	}
	e := wrap.Envelope

	if r := e.ReceiptMessage; r != nil {
		return nil, &Receipt{
			SenderUUID: e.SourceUUID,
			Timestamps: r.Timestamps,
			IsRead:     r.IsRead,
		}, nil
	}

	var dm *dataMessage
	var outgoing bool
	var counterpartUUID, counterpartNumber string
	authorUUID, authorNumber := e.SourceUUID, e.SourceNumber

	switch {
	case e.SyncMessage != nil && e.SyncMessage.SentMessage != nil:
		sm := e.SyncMessage.SentMessage
		dm = &sm.dataMessage
		outgoing = true
		// A sync message comes from our own account, so the envelope's source IS
		// us. Taking the author from the envelope rather than from selfUUID means
		// normalization stays correct even before self has been detected.
		if authorUUID == "" {
			authorUUID = selfUUID
		}
		counterpartUUID, counterpartNumber = sm.DestinationUUID, sm.DestinationNumber
	case e.DataMessage != nil:
		dm = e.DataMessage
		counterpartUUID, counterpartNumber = e.SourceUUID, e.SourceNumber
	default:
		return nil, nil, nil // typing, config sync, nothing to store
	}

	// A sent-sync with no destination and no group is a Note to Self.
	if outgoing && counterpartUUID == "" && (dm.GroupInfo == nil || dm.GroupInfo.GroupID == "") {
		counterpartUUID = authorUUID // Note to Self
		if counterpartUUID == "" {
			counterpartUUID = selfUUID
		}
	}

	ts := dm.Timestamp
	if ts == 0 {
		ts = e.Timestamp
	}

	var threadKey, groupID string
	if dm.GroupInfo != nil && dm.GroupInfo.GroupID != "" {
		groupID = dm.GroupInfo.GroupID
		threadKey = "group:" + groupID
	} else {
		id := counterpartUUID
		if id == "" {
			id = counterpartNumber
		}
		if id == "" {
			return nil, nil, nil // nothing to hang a thread on
		}
		threadKey = "direct:" + id
	}

	// Identity of a Signal message is (author, timestamp). Stable across the
	// several notifications one message produces, and across a re-import.
	idAuthor := authorUUID
	if idAuthor == "" {
		idAuthor = authorNumber
	}
	// A dataMessage whose source is our own account is Note to Self -- our own
	// group and direct sends come back as syncMessage instead, so this is the only
	// case. The user wrote it, so it belongs on the outgoing side.
	if !outgoing && selfUUID != "" && authorUUID == selfUUID {
		outgoing = true
	}

	m := &Message{
		ID:           fmt.Sprintf("%s:%d", idAuthor, ts),
		ThreadKey:    threadKey,
		TS:           ts,
		SenderUUID:   authorUUID,
		SenderNumber: authorNumber,
		Outgoing:     outgoing,
		Body:         dm.Message,
		GroupID:      groupID,
		Read:         outgoing, // our own messages are not unread
		Source:       "live",
		Raw:          string(raw),
	}
	if dm.Quote != nil {
		m.QuoteTS = dm.Quote.ID
	}
	for _, a := range dm.Attachments {
		m.Attachments = append(m.Attachments, Attachment{
			ID: a.ID, Type: a.ContentType, Filename: a.Filename, Size: a.Size,
		})
	}
	// An empty message with no attachments is a reaction/edit/receipt artefact.
	if m.Body == "" && len(m.Attachments) == 0 {
		return nil, nil, nil
	}
	return m, nil, nil
}
