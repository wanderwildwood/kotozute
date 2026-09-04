package main

import (
	"encoding/json"
	"fmt"
	"time"
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

	// A reaction to another message, rather than a message of its own. ReactionTarget is
	// the msg_id of what it points at, built the same way any message id is, so the phone
	// can find the row without knowing how ids are made. Empty on an ordinary message.
	ReactionEmoji  string `json:"reactionEmoji,omitempty"`
	ReactionTarget string `json:"reactionTarget,omitempty"`
	ReactionRemove bool   `json:"reactionRemove,omitempty"`

	// ExpiresInSeconds is the disappearing-message timer the sender set, 0 for none.
	// ExpiresAt is when this copy must be gone, in ms; 0 means never. Signal starts the
	// clock when the recipient reads the message, and we cannot know that moment for a
	// device that is not this one, so the clock starts on receipt instead. That deletes
	// no later than Signal would, which is the only direction that is safe to be wrong in.
	ExpiresInSeconds int64 `json:"expiresInSeconds,omitempty"`
	ExpiresAt        int64 `json:"expiresAt,omitempty"`

	// ViewOnce marks a message Signal intends to be opened once. Its attachment is never
	// stored; the row is kept so the conversation does not have a silent hole in it.
	ViewOnce bool `json:"viewOnce,omitempty"`

	// Files signal-cli already wrote to disk for a view-once message, for the caller to
	// remove. Not stored and not sent: keeping the row out of the database was never
	// enough on its own, because the bytes were still there to be fetched.
	ViewOnceFiles []string `json:"-"`

	Raw string `json:"-"`
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
	Timestamp        int64  `json:"timestamp"`
	Message          string `json:"message"`
	ExpiresInSeconds int64  `json:"expiresInSeconds"`
	// True when the message carries nothing but a change to the disappearing-message
	// timer. Signal shows these as an event, not a message; stored as one it would be an
	// empty bubble in the thread.
	IsExpirationUpdate bool `json:"isExpirationUpdate"`
	ViewOnce           bool `json:"viewOnce"`
	GroupInfo          *struct {
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
	// A reaction to one of your messages. signal-cli reports it in its own object on an
	// otherwise empty dataMessage, which is exactly the shape the "empty means artefact"
	// rule at the end of this function discards -- so every reaction anyone sent was
	// dropped in silence. The SMS side has shown reactions all along, by parsing the
	// "Liked ..." text other clients send, so the same gesture was visible on one rail and
	// invisible on the other.
	Reaction *struct {
		Emoji               string `json:"emoji"`
		TargetAuthor        string `json:"targetAuthor"`
		TargetAuthorUUID    string `json:"targetAuthorUuid"`
		TargetSentTimestamp int64  `json:"targetSentTimestamp"`
		IsRemove            bool   `json:"isRemove"`
	} `json:"reaction"`

	// A sticker is not an attachment and does not appear in the list above -- signal-cli
	// reports it in its own object, with the message null. Without this field such an
	// envelope unmarshalled to an empty body with no attachments and was discarded as a
	// reaction artefact, so a sticker sent with no caption left a silent hole in the
	// thread: nothing stored, nothing shown, nothing logged, and the sender with every
	// reason to think it arrived.
	Sticker *struct {
		PackID    string `json:"packId"`
		StickerID int    `json:"stickerId"`
		Emoji     string `json:"emoji"`
	} `json:"sticker"`
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

	// A sent-sync with no destination at all, and no group, is a Note to Self.
	//
	// Both halves of the destination have to be empty. Testing the uuid alone filed a
	// message sent to a real person as Note to Self whenever signal-cli reported only their
	// number -- which it does for a recipient it has not yet resolved to an ACI. The message
	// then appeared in the wrong conversation, and a reply in that thread went to yourself.
	if outgoing && counterpartUUID == "" && counterpartNumber == "" &&
		(dm.GroupInfo == nil || dm.GroupInfo.GroupID == "") {
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

	// A timer change carries no message. Signal shows it as an event in the thread; kept
	// as a message it would be an empty bubble, and kept as one that never expires it
	// would be a permanent record of a conversation being made impermanent.
	if dm.IsExpirationUpdate {
		return nil, nil, nil
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

		ExpiresInSeconds: dm.ExpiresInSeconds,
		ViewOnce:         dm.ViewOnce,

		Raw: string(raw),
	}
	// The clock starts now rather than at the moment of reading. See Message.ExpiresAt:
	// we cannot observe a read on another device, and erring early is the safe direction.
	if dm.ExpiresInSeconds > 0 {
		m.ExpiresAt = nowMs() + dm.ExpiresInSeconds*1000
	}
	if dm.Quote != nil {
		m.QuoteTS = dm.Quote.ID
	}
	// A view-once attachment is not stored, and the file signal-cli already wrote is
	// removed. Keeping the row out of the database was not enough: the bytes were still on
	// disk under an id the raw envelope preserved, so anything that could read raw could
	// still fetch it. Signal's promise is that it can be opened once.
	if dm.ViewOnce {
		for _, a := range dm.Attachments {
			if a.ID != "" {
				m.ViewOnceFiles = append(m.ViewOnceFiles, a.ID)
			}
		}
		// The envelope names the attachment. Storing it would put the id of a file the
		// sender meant to be seen once into a column anything can read.
		m.Raw = ""
	}
	if !dm.ViewOnce {
		for _, a := range dm.Attachments {
			m.Attachments = append(m.Attachments, Attachment{
				ID: a.ID, Type: a.ContentType, Filename: a.Filename, Size: a.Size,
			})
		}
	}
	// A sticker with no caption. The bridge cannot render the sticker itself -- the image
	// lives in a pack signal-cli would have to fetch and the phone would have to draw --
	// but a bubble saying which one it was is the truth, and a hole in the thread is not.
	// The emoji the pack assigns is what Signal falls back to in its own notifications.
	if m.Body == "" && dm.Sticker != nil {
		if dm.Sticker.Emoji != "" {
			m.Body = dm.Sticker.Emoji
		} else {
			m.Body = "(sticker)"
		}
	}

	// A reaction. It arrives on an otherwise empty dataMessage, so it has to be recognised
	// before the emptiness rule below throws it away -- which is exactly what used to
	// happen to every one of them.
	if r := dm.Reaction; r != nil && r.Emoji != "" {
		author := r.TargetAuthorUUID
		if author == "" {
			author = r.TargetAuthor
		}
		// The id of the message being reacted to, built the way ids are built everywhere
		// here: author and the timestamp they sent it at.
		if author != "" && r.TargetSentTimestamp != 0 {
			m.ReactionEmoji = r.Emoji
			m.ReactionTarget = fmt.Sprintf("%s:%d", author, r.TargetSentTimestamp)
			m.ReactionRemove = r.IsRemove
			// Its own id, so two people reacting to one message are two rows and removing
			// a reaction replaces the one it removes rather than piling up beside it.
			m.ID = fmt.Sprintf("react:%s:%s", m.ReactionTarget, authorUUID)
			m.Raw = ""
			return m, nil, nil
		}
	}

	// An empty message with no attachments is a reaction/edit/receipt artefact -- unless
	// it is view-once, where the emptiness is the point and the row says so.
	if m.Body == "" && len(m.Attachments) == 0 && !m.ViewOnce {
		return nil, nil, nil
	}
	return m, nil, nil
}

// nowMs is a variable so the expiry tests can hold time still.
var nowMs = func() int64 { return time.Now().UnixMilli() }
