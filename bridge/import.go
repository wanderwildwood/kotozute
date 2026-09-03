package main

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
)

// Importing a Signal "export chat history" folder.
//
// Signal Desktop writes the export as backup-v2 records, one JSON object per line, plus a
// files/ directory of plaintext attachments. This reads that into the bridge's store so a
// freshly registered account starts with its history instead of empty.
//
// Idempotent by construction: a message's id here is built the same way normalize() builds
// it for a live message -- author:timestamp -- so re-running an import, or importing a
// window that overlaps messages already received live, updates nothing and duplicates
// nothing. InsertMessage is INSERT OR IGNORE on that id.

// ImportStats is what the import did, for the operator to read and sanity-check.
type ImportStats struct {
	Lines           int
	Recipients      int
	Chats           int
	Messages        int // inserted
	AlreadyPresent  int
	SkippedUpdates  int // "X joined", timer changes, profile changes: events, not messages
	SkippedDeleted  int // remote-deleted: tombstones, there is no content to import
	SkippedExpired  int // already past its disappearing deadline
	SkippedNoThread int // the chat's recipient is not one we can key a thread on
	SkippedNoAuthor int // the message's author has no identifier we can use
	SkippedGroup    int // a group this account does not already have a thread for
	Attachments     int
	AttachmentsLost int
}

func (s ImportStats) String() string {
	return fmt.Sprintf(
		"%d messages imported (%d already present), %d attachments (%d unmatched); "+
			"skipped %d events, %d deleted, %d expired, %d without a thread, "+
			"%d without an author, %d in unknown groups",
		s.Messages, s.AlreadyPresent, s.Attachments, s.AttachmentsLost,
		s.SkippedUpdates, s.SkippedDeleted, s.SkippedExpired, s.SkippedNoThread,
		s.SkippedNoAuthor, s.SkippedGroup)
}

// --- the subset of the export schema we read -------------------------------------------
//
// Only the fields that become a message. The export carries a great deal more -- sticker
// packs, call links, chat folders, styling -- and reading fields we do not use would be
// inventing a dependency on a format we do not control.

type expRecipient struct {
	ID      string `json:"id"`
	Contact *struct {
		ACI               string `json:"aci"`
		PNI               string `json:"pni"`
		E164              string `json:"e164"`
		SystemGivenName   string `json:"systemGivenName"`
		SystemFamilyName  string `json:"systemFamilyName"`
		ProfileGivenName  string `json:"profileGivenName"`
		ProfileFamilyName string `json:"profileFamilyName"`
	} `json:"contact"`
	Group *struct {
		MasterKey string `json:"masterKey"`
		Snapshot  *struct {
			Title *struct {
				Title string `json:"title"`
			} `json:"title"`
		} `json:"snapshot"`
	} `json:"group"`
	// Only in an export this bridge wrote. Signal's own export cannot carry it -- it holds
	// a group's master key, and signal-cli's id is derived from that through zkgroup -- so
	// restoring our own file is exact where restoring Signal's has to match on title.
	ThreadKey string    `json:"kotozuteThreadKey"`
	Self      *struct{} `json:"self"`
}

type expChat struct {
	ID          string `json:"id"`
	RecipientID string `json:"recipientId"`
}

type expChatItem struct {
	ChatID   string `json:"chatId"`
	AuthorID string `json:"authorId"`
	DateSent int64  `json:"dateSent,string"`

	ExpireStartDate int64 `json:"expireStartDate,string"`
	ExpiresInMs     int64 `json:"expiresInMs,string"`

	Incoming *struct {
		DateReceived int64 `json:"dateReceived,string"`
		Read         bool  `json:"read"`
	} `json:"incoming"`
	Outgoing *struct {
		DateReceived int64 `json:"dateReceived,string"`
	} `json:"outgoing"`

	StandardMessage *struct {
		Text *struct {
			Body string `json:"body"`
		} `json:"text"`
		Quote *struct {
			TargetSentTimestamp int64 `json:"targetSentTimestamp,string"`
		} `json:"quote"`
		Attachments []struct {
			Pointer *struct {
				ContentType string `json:"contentType"`
				FileName    string `json:"fileName"`
				LocatorInfo *struct {
					Size int64 `json:"size"`
				} `json:"locatorInfo"`
			} `json:"pointer"`
			// Only in an export this bridge wrote. Size alone cannot tell two attachments of
			// equal length apart, and dropping both is how an attachment-only message
			// disappears entirely.
			FileName string `json:"kotozuteFileName"`
		} `json:"attachments"`
	} `json:"standardMessage"`

	UpdateMessage        json.RawMessage `json:"updateMessage"`
	RemoteDeletedMessage json.RawMessage `json:"remoteDeletedMessage"`
}

type person struct {
	uuid   string
	number string
	name   string
	isSelf bool
}

// --- the import -------------------------------------------------------------------------

// ImportExport reads dir (the folder Signal wrote, containing main.jsonl and files/) into
// store, copying any attachments it can match into attachDir.
// selfUUID is this account's own ACI. The export does not carry it -- an account record
// has a profile, a username and settings, but no identifier for itself -- so it has to be
// supplied. Without it every message the user sent has no author, and Note to Self has no
// thread: on a real export that was 817 of 1901 items dropped.
func ImportExport(store *Store, dir, attachDir, selfUUID, selfNumber string) (ImportStats, error) {
	var st ImportStats

	main := filepath.Join(dir, "main.jsonl")
	if _, err := os.Stat(main); err != nil {
		return st, fmt.Errorf("not a Signal export (no main.jsonl in %s): %w", dir, err)
	}

	people := map[string]person{}
	groupTitles := map[string]string{}     // recipient id -> group title, matched below
	groupThreadKeys := map[string]string{} // recipient id -> exact key, our own exports only
	chats := map[string]string{}           // chat id -> recipient id

	// First pass: who and where. The export does not promise that a recipient appears
	// before the messages that reference it, so the mapping has to be complete first.
	if err := eachLine(main, func(raw []byte) error {
		st.Lines++
		var probe struct {
			Recipient *expRecipient `json:"recipient"`
			Chat      *expChat      `json:"chat"`
		}
		if err := json.Unmarshal(raw, &probe); err != nil {
			return nil // a record shape we do not read; not an error
		}
		if r := probe.Recipient; r != nil {
			switch {
			case r.Contact != nil:
				// ACI first, because that is what a live message keys on and what makes
				// an imported thread merge with one that already exists. A pni-only
				// contact still gets a thread; the alternative is dropping them.
				uuid := canonicalUUID(r.Contact.ACI)
				if uuid == "" {
					uuid = canonicalUUID(r.Contact.PNI)
				}
				people[r.ID] = person{
					uuid:   uuid,
					number: r.Contact.E164,
					name:   contactName(r),
				}
				st.Recipients++
			case r.Group != nil:
				if r.ThreadKey != "" {
					groupThreadKeys[r.ID] = r.ThreadKey
				}
				if r.Group.Snapshot != nil && r.Group.Snapshot.Title != nil {
					groupTitles[r.ID] = r.Group.Snapshot.Title.Title
				}
				st.Recipients++
			case r.Self != nil:
				people[r.ID] = person{uuid: selfUUID, number: selfNumber, isSelf: true}
			}
		}
		if c := probe.Chat; c != nil {
			chats[c.ID] = c.RecipientID
			st.Chats++
		}
		return nil
	}); err != nil {
		return st, err
	}

	// A group's thread key cannot be derived from what the export gives us. signal-cli
	// identifies a group by an id derived from its master key through zkgroup; the export
	// carries the master key itself. Rather than invent a second thread for a group that
	// already has one, match on the title and skip a group we do not already know.
	knownGroups := map[string]string{} // lowercased title -> existing thread key
	if rows, err := store.Threads(); err == nil {
		for _, t := range rows {
			if t.Kind == "group" && t.Title != "" {
				knownGroups[strings.ToLower(t.Title)] = t.Key
			}
		}
	}
	groupKeys := map[string]string{} // recipient id -> existing thread key
	for recipientID, title := range groupTitles {
		if key, ok := knownGroups[strings.ToLower(title)]; ok {
			groupKeys[recipientID] = key
		}
	}
	// An exact key beats a title match, and works when there is no thread to match against
	// -- which is the whole of restoring into an empty store.
	for recipientID, key := range groupThreadKeys {
		groupKeys[recipientID] = key
	}

	files, err := indexFiles(filepath.Join(dir, "files"))
	if err != nil {
		return st, err
	}

	// Second pass: the messages.
	err = eachLine(main, func(raw []byte) error {
		var probe struct {
			ChatItem *expChatItem `json:"chatItem"`
		}
		if err := json.Unmarshal(raw, &probe); err != nil || probe.ChatItem == nil {
			return nil
		}
		it := probe.ChatItem

		// Events, not messages: "X joined the group", a timer change, a profile change.
		if len(it.UpdateMessage) > 0 {
			st.SkippedUpdates++
			return nil
		}
		// A tombstone for something already deleted for everyone. There is no content to
		// import, and creating a row for it would resurrect a message as an empty bubble.
		if len(it.RemoteDeletedMessage) > 0 {
			st.SkippedDeleted++
			return nil
		}
		if it.StandardMessage == nil {
			st.SkippedUpdates++
			return nil
		}

		// A message whose disappearing deadline has already passed is not history, it is
		// something Signal would have removed. Importing it would undo the sender's choice.
		if it.ExpireStartDate > 0 && it.ExpiresInMs > 0 &&
			it.ExpireStartDate+it.ExpiresInMs <= nowMs() {
			st.SkippedExpired++
			return nil
		}

		threadKey, groupID := threadFor(it.ChatID, chats, people, groupKeys)
		if threadKey == "" {
			st.SkippedNoThread++
			return nil
		}

		author := people[it.AuthorID]
		outgoing := it.Outgoing != nil || author.isSelf

		body := ""
		if it.StandardMessage.Text != nil {
			body = it.StandardMessage.Text.Body
		}
		quoteTS := int64(0)
		if q := it.StandardMessage.Quote; q != nil {
			quoteTS = q.TargetSentTimestamp
		}

		m := &Message{
			ThreadKey:    threadKey,
			TS:           it.DateSent,
			SenderUUID:   author.uuid,
			SenderNumber: author.number,
			Outgoing:     outgoing,
			Body:         body,
			GroupID:      groupID,
			// Imported history has been seen. Marking it unread would raise a notification
			// storm for messages from years ago.
			Read:    true,
			Source:  "import",
			QuoteTS: quoteTS,
		}
		if it.ExpiresInMs > 0 {
			m.ExpiresInSeconds = it.ExpiresInMs / 1000
			if it.ExpireStartDate > 0 {
				m.ExpiresAt = it.ExpireStartDate + it.ExpiresInMs
			} else {
				// Signal writes expireStartDate only once the timer has actually started, so
				// an unread disappearing message carries a duration and no start. Requiring
				// both meant such a message was imported as "never expires" -- turning every
				// not-yet-started disappearing message in a history into a permanent record,
				// which is the outcome this importer exists to avoid. Start the clock now:
				// that expires no later than Signal would, which is the safe direction.
				m.ExpiresAt = nowMs() + it.ExpiresInMs
			}
		}

		// The id must match what normalize() would build for the same message arriving
		// live, or an import next to live data duplicates every overlapping message.
		idAuthor := author.uuid
		if idAuthor == "" {
			idAuthor = author.number
		}
		if idAuthor == "" {
			st.SkippedNoAuthor++
			return nil
		}
		m.ID = fmt.Sprintf("%s:%d", idAuthor, m.TS)

		for _, a := range it.StandardMessage.Attachments {
			if a.Pointer == nil || a.Pointer.LocatorInfo == nil {
				continue
			}
			name, ok := files.takeNamed(a.FileName, a.Pointer.LocatorInfo.Size, attachDir)
			if !ok {
				// No file behind the reference: retention pruned it, or the export named a
				// file it did not write. The reference is kept with an empty id, which is
				// what our own sent attachments carry and what the app draws as "not
				// downloaded". Dropping it instead made a message whose only content was
				// the attachment look empty, and empty messages are discarded below -- so
				// the message vanished rather than just its picture.
				st.AttachmentsLost++
				m.Attachments = append(m.Attachments, Attachment{
					Type:     a.Pointer.ContentType,
					Filename: a.Pointer.FileName,
					Size:     a.Pointer.LocatorInfo.Size,
				})
				continue
			}
			m.Attachments = append(m.Attachments, Attachment{
				ID:       name,
				Type:     a.Pointer.ContentType,
				Filename: a.Pointer.FileName,
				Size:     a.Pointer.LocatorInfo.Size,
			})
			st.Attachments++
		}

		if m.Body == "" && len(m.Attachments) == 0 {
			st.SkippedUpdates++
			return nil
		}

		_, inserted, err := store.InsertMessage(m)
		if err != nil {
			return fmt.Errorf("insert %s: %w", m.ID, err)
		}
		if inserted {
			st.Messages++
		} else {
			st.AlreadyPresent++
		}
		return nil
	})
	if err != nil {
		return st, err
	}

	// Thread titles, so an imported conversation is not a row of bare uuids. Done after
	// the messages so a thread exists to name.
	for chatID, recipientID := range chats {
		key, _ := threadFor(chatID, chats, people, groupKeys)
		if key == "" {
			continue
		}
		if p, ok := people[recipientID]; ok && p.name != "" {
			_ = store.SetThreadMeta(key, "direct", p.name, nil)
			continue
		}
		// A group recipient is never in `people` -- it goes to groupTitles instead -- so
		// this loop used to walk straight past every group and leave it named with its raw
		// zkgroup id. Restoring a backup then gave back a list where every group
		// conversation was a wall of base64, until signal-cli happened to re-sync the
		// names on its own, which it only does when something changes in the group.
		if title := groupTitles[recipientID]; title != "" {
			_ = store.SetThreadMeta(key, "group", title, nil)
		}
	}
	return st, nil
}

func contactName(r *expRecipient) string {
	c := r.Contact
	// The name this account's own address book had is the one the user recognises; the
	// profile name is what the other person chose to publish, and is the fallback.
	if n := strings.TrimSpace(c.SystemGivenName + " " + c.SystemFamilyName); n != "" {
		return n
	}
	return strings.TrimSpace(c.ProfileGivenName + " " + c.ProfileFamilyName)
}

func threadFor(
	chatID string, chats map[string]string,
	people map[string]person, groupKeys map[string]string,
) (key, groupID string) {
	recipientID, ok := chats[chatID]
	if !ok {
		return "", ""
	}
	if key, ok := groupKeys[recipientID]; ok {
		return key, strings.TrimPrefix(key, "group:")
	}
	p, ok := people[recipientID]
	if !ok {
		return "", ""
	}
	id := p.uuid
	if id == "" {
		id = p.number
	}
	if id == "" {
		return "", ""
	}
	return "direct:" + id, ""
}

// --- attachments --------------------------------------------------------------------
//
// The export names its files by a derived media name, not by anything that appears in
// main.jsonl, and the content is plaintext. Size is what the two sides share: across this
// export's files it is unique, so a size that matches exactly one file is a safe match and
// a size that matches none or several is not a match at all.

type fileIndex struct {
	bySize map[int64][]string // size -> full paths
	byName map[string]string  // base filename -> full path, for our own exports
	copied map[string]string  // source path -> attachment id already copied
}

func indexFiles(dir string) (*fileIndex, error) {
	idx := &fileIndex{
		bySize: map[int64][]string{},
		byName: map[string]string{},
		copied: map[string]string{},
	}
	if _, err := os.Stat(dir); err != nil {
		return idx, nil // an export with no attachments is fine
	}
	err := filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return err
		}
		idx.bySize[info.Size()] = append(idx.bySize[info.Size()], path)
		idx.byName[info.Name()] = path
		return nil
	})
	return idx, err
}

// take resolves one attachment reference to a file and copies it where the bridge serves
// attachments from, returning the id to record. The same source file referenced twice
// returns the same id rather than being copied twice.
// takeNamed prefers an exact filename, which our own exports carry, and falls back to a
// unique size, which is all Signal's own export makes possible.
func (f *fileIndex) takeNamed(name string, size int64, attachDir string) (string, bool) {
	if name != "" {
		if src, ok := f.byName[filepath.Base(name)]; ok {
			return f.copyOut(src, attachDir)
		}
	}
	return f.take(size, attachDir)
}

func (f *fileIndex) take(size int64, attachDir string) (string, bool) {
	paths := f.bySize[size]
	if len(paths) != 1 {
		return "", false
	}
	return f.copyOut(paths[0], attachDir)
}

func (f *fileIndex) copyOut(src, attachDir string) (string, bool) {
	if id, ok := f.copied[src]; ok {
		return id, true
	}
	// The served id becomes the filename, and Serve() only accepts [A-Za-z0-9_-] with an
	// optional extension -- so the id is built from the export's own name, which is hex.
	id := "import-" + filepath.Base(src)
	if !attachmentID.MatchString(id) {
		return "", false
	}
	if err := copyFile(src, filepath.Join(attachDir, id)); err != nil {
		log.Printf("import: could not copy %s: %v", filepath.Base(src), err)
		return "", false
	}
	f.copied[src] = id
	return id, true
}

func copyFile(src, dst string) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o700); err != nil {
		return err
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

// eachLine streams the JSONL. The file is small next to the media, but streaming keeps the
// importer's memory flat whatever size an account's history turns out to be.
func eachLine(path string, fn func([]byte) error) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	// A single chat item with a long body can exceed the default 64KB token.
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if len(strings.TrimSpace(string(line))) == 0 {
			continue
		}
		if err := fn(line); err != nil {
			return err
		}
	}
	return sc.Err()
}

// canonicalUUID turns the export's identifier into the form signal-cli uses.
//
// The export writes an ACI as base64 of the raw 16 bytes; signal-cli reports the same
// value as a hyphenated UUID string. They are the same identity and they do not compare
// equal, so without this every contact would get a second thread and every message a
// second copy -- silently, and only visibly once the history was already doubled.
//
// Anything already hyphenated is passed through, and anything that is neither is returned
// empty so the caller falls back rather than keying a thread on a string it cannot read.
func canonicalUUID(v string) string {
	if v == "" {
		return ""
	}
	if strings.Count(v, "-") == 4 && len(v) == 36 {
		return strings.ToLower(v)
	}
	raw, err := base64.StdEncoding.DecodeString(v)
	if err != nil || len(raw) != 16 {
		return ""
	}
	h := hex.EncodeToString(raw)
	return fmt.Sprintf("%s-%s-%s-%s-%s", h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])
}
