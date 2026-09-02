package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Exporting the bridge's store back out as the same JSONL an import reads.
//
// Symmetry is the point: what comes out here goes back in through ImportExport, so a store
// can be moved, backed up, or rebuilt from a file. That matters most right before something
// irreversible -- re-registering the account, say -- which is exactly when a backup of the
// messages the bridge is holding is worth having.
//
// This is not Signal's own export format and does not pretend to be. It is the subset of
// that shape the importer actually reads, written so the importer can read it: recipients
// keyed by uuid, chats keyed by recipient, and chat items carrying the direction, the body
// and any attachment.

type ExportStats struct {
	Threads     int
	Messages    int
	Attachments int
	Missing     int // attachments the store references but the disk no longer has
}

func (s ExportStats) String() string {
	return fmt.Sprintf("%d messages in %d threads, %d attachments (%d missing)",
		s.Messages, s.Threads, s.Attachments, s.Missing)
}

// ExportStore writes dir/main.jsonl plus dir/files/ holding the attachments.
func ExportStore(store *Store, dir, attachDir, selfUUID string) (ExportStats, error) {
	var st ExportStats

	if err := os.MkdirAll(filepath.Join(dir, "files"), 0o700); err != nil {
		return st, err
	}
	out, err := os.OpenFile(filepath.Join(dir, "main.jsonl"),
		os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return st, err
	}
	defer out.Close()
	enc := json.NewEncoder(out)

	threads, err := store.Threads()
	if err != nil {
		return st, err
	}

	// Recipient ids are ours to choose; the importer only needs them to be consistent
	// within the file. Numbering from 2 leaves 1 for self, as Signal's own export does.
	const selfID = "1"
	if err := enc.Encode(map[string]any{"recipient": map[string]any{
		"id": selfID, "self": map[string]any{},
	}}); err != nil {
		return st, err
	}

	// Every distinct sender, emitted as a recipient before anything references one. In a
	// group the author of a message is a member, not the thread's other party, and mapping
	// them to the group recipient leaves them with no identity at all -- which silently
	// dropped every incoming group message on the way back in.
	next := 2
	senders := map[string]string{} // sender uuid -> recipient id
	for _, t := range threads {
		uuids, err := threadSenders(store, t.Key)
		if err != nil {
			return st, err
		}
		for _, uuid := range uuids {
			if uuid == "" || uuid == selfUUID || senders[uuid] != "" {
				continue
			}
			id := strconv.Itoa(next)
			next++
			senders[uuid] = id
			if err := enc.Encode(map[string]any{"recipient": map[string]any{
				"id":      id,
				"contact": map[string]any{"aci": uuid},
			}}); err != nil {
				return st, err
			}
		}
	}

	for _, t := range threads {
		recipientID := strconv.Itoa(next)
		chatID := strconv.Itoa(next)
		next++

		switch {
		case strings.HasPrefix(t.Key, "group:"):
			// The thread key itself, which Signal's own export cannot carry: it holds the
			// group's master key, and signal-cli's id is derived from that through zkgroup.
			// We know the derived id here, so writing it makes a restore exact instead of
			// depending on a thread with a matching title already existing. Importing
			// Signal's export still falls back to the title.
			if err := enc.Encode(map[string]any{"recipient": map[string]any{
				"id":                recipientID,
				"kotozuteThreadKey": t.Key,
				"group": map[string]any{
					"snapshot": map[string]any{
						"title": map[string]any{"title": t.Title},
					},
				},
			}}); err != nil {
				return st, err
			}
		case strings.HasPrefix(t.Key, "direct:"):
			// The same person may already have a recipient from the sender pass; naming
			// them again here would be two ids for one identity. Reuse it, and let this
			// record carry the name and number the sender pass did not have.
			if existing := senders[strings.TrimPrefix(t.Key, "direct:")]; existing != "" {
				recipientID = existing
			}
			if err := enc.Encode(map[string]any{"recipient": map[string]any{
				"id": recipientID,
				"contact": map[string]any{
					// Written canonically, which canonicalUUID passes through unchanged.
					"aci":             strings.TrimPrefix(t.Key, "direct:"),
					"e164":            t.Number,
					"systemGivenName": t.Title,
				},
			}}); err != nil {
				return st, err
			}
		default:
			continue
		}

		if err := enc.Encode(map[string]any{"chat": map[string]any{
			"id": chatID, "recipientId": recipientID,
		}}); err != nil {
			return st, err
		}
		st.Threads++

		if err := exportThread(store, enc, t.Key, chatID, recipientID, selfID, senders,
			dir, attachDir, &st); err != nil {
			return st, err
		}
	}
	return st, nil
}

func exportThread(
	store *Store, enc *json.Encoder,
	key, chatID, recipientID, selfID string, senders map[string]string,
	dir, attachDir string, st *ExportStats,
) error {
	// Paged backwards from the newest, which is the only order ThreadMessages offers.
	before := int64(0)
	for {
		msgs, err := store.ThreadMessages(key, before, 500)
		if err != nil {
			return err
		}
		if len(msgs) == 0 {
			return nil
		}
		for _, m := range msgs {
			item := map[string]any{
				"chatId":   chatID,
				"authorId": authorID(m, recipientID, selfID, senders),
				// Written as strings, because that is how Signal's export writes them and
				// how the importer reads them.
				"dateSent": strconv.FormatInt(m.TS, 10),
			}
			if m.Outgoing {
				item["outgoing"] = map[string]any{
					"dateReceived": strconv.FormatInt(m.TS, 10),
				}
			} else {
				item["incoming"] = map[string]any{
					"dateReceived": strconv.FormatInt(m.TS, 10),
					"read":         m.Read,
				}
			}
			if m.ExpiresAt > 0 && m.ExpiresInSeconds > 0 {
				item["expiresInMs"] = strconv.FormatInt(m.ExpiresInSeconds*1000, 10)
				item["expireStartDate"] =
					strconv.FormatInt(m.ExpiresAt-m.ExpiresInSeconds*1000, 10)
			}

			standard := map[string]any{}
			if m.Body != "" {
				standard["text"] = map[string]any{"body": m.Body}
			}
			if atts := exportAttachments(m, dir, attachDir, st); len(atts) > 0 {
				standard["attachments"] = atts
			}
			item["standardMessage"] = standard

			if err := enc.Encode(map[string]any{"chatItem": item}); err != nil {
				return err
			}
			st.Messages++
		}
		before = msgs[len(msgs)-1].TS
	}
}

// authorID maps a message back to a recipient id. In a one-to-one thread the sender is the
// thread's other party; in a group it is whichever member wrote it, which is why senders
// are emitted as recipients of their own.
func authorID(m *Message, recipientID, selfID string, senders map[string]string) string {
	if m.Outgoing {
		return selfID
	}
	if id := senders[m.SenderUUID]; id != "" {
		return id
	}
	return recipientID
}

// threadSenders lists the distinct uuids that have written into a thread.
func threadSenders(store *Store, key string) ([]string, error) {
	seen := map[string]bool{}
	var out []string
	before := int64(0)
	for {
		msgs, err := store.ThreadMessages(key, before, 500)
		if err != nil {
			return nil, err
		}
		if len(msgs) == 0 {
			return out, nil
		}
		for _, m := range msgs {
			if m.SenderUUID != "" && !seen[m.SenderUUID] {
				seen[m.SenderUUID] = true
				out = append(out, m.SenderUUID)
			}
		}
		before = msgs[len(msgs)-1].TS
	}
}

// exportAttachments copies each attachment out and describes it the way the importer reads
// it: by size, since that is what the importer matches on.
func exportAttachments(m *Message, dir, attachDir string, st *ExportStats) []map[string]any {
	var out []map[string]any
	for _, a := range m.Attachments {
		if a.ID == "" {
			continue
		}
		src := filepath.Join(attachDir, a.ID)
		info, err := os.Stat(src)
		if err != nil {
			// Retention deletes old attachments on purpose, so this is expected on an
			// older conversation rather than a fault. Counted, not shouted about.
			st.Missing++
			continue
		}
		dst := filepath.Join(dir, "files", a.ID)
		if err := copyFile(src, dst); err != nil {
			st.Missing++
			continue
		}
		out = append(out, map[string]any{
			"pointer": map[string]any{
				"contentType": a.Type,
				"fileName":    a.Filename,
				"locatorInfo": map[string]any{"size": info.Size()},
			},
		})
		st.Attachments++
	}
	return out
}
