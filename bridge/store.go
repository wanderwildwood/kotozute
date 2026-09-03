package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// Store is the bridge's durable copy of everything signal-cli has handed us.
// signal-cli delivers a message once and forgets it, so if this is not written
// down here it does not exist anywhere the phone can reach.
type Store struct{ db *sql.DB }

const schema = `
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS messages (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  msg_id      TEXT    NOT NULL UNIQUE,   -- idempotency key; see normalize.go
  thread_key  TEXT    NOT NULL,
  ts          INTEGER NOT NULL,          -- message timestamp, ms
  sender_uuid TEXT    NOT NULL DEFAULT '',
  sender_num  TEXT    NOT NULL DEFAULT '',
  outgoing    INTEGER NOT NULL DEFAULT 0,
  body        TEXT    NOT NULL DEFAULT '',
  group_id    TEXT    NOT NULL DEFAULT '',
  quote_ts    INTEGER NOT NULL DEFAULT 0,
  attachments TEXT    NOT NULL DEFAULT '',  -- json array
  read        INTEGER NOT NULL DEFAULT 0,
  source      TEXT    NOT NULL DEFAULT 'live', -- 'live' | 'import'
  raw         TEXT
);
CREATE INDEX IF NOT EXISTS idx_msg_thread_ts ON messages(thread_key, ts);
CREATE INDEX IF NOT EXISTS idx_msg_ts        ON messages(ts);

CREATE TABLE IF NOT EXISTS threads (
  thread_key  TEXT PRIMARY KEY,
  kind        TEXT NOT NULL,             -- 'direct' | 'group'
  title       TEXT,
  members     TEXT,                      -- json array
  last_ts     INTEGER NOT NULL DEFAULT 0,
  last_seq    INTEGER NOT NULL DEFAULT 0,
  unread      INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS contacts (
  uuid        TEXT PRIMARY KEY,
  number      TEXT,
  name        TEXT,
  updated     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
`

func OpenStore(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, err
	}
	// One writer. SQLite is fine with this and it removes a whole class of bug.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		return nil, fmt.Errorf("schema: %w", err)
	}
	if err := migrate(db); err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

// migrate adds columns to a store that already exists. CREATE TABLE IF NOT EXISTS does
// nothing to a table that is already there, so every column added after the first release
// has to arrive this way. Adding a column that is already present is not an error worth
// stopping for: SQLite has no ADD COLUMN IF NOT EXISTS, and the alternative is parsing
// PRAGMA table_info to ask a question the failure already answers.
func migrate(db *sql.DB) error {
	adds := []string{
		`ALTER TABLE messages ADD COLUMN expires_in INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE messages ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE messages ADD COLUMN view_once INTEGER NOT NULL DEFAULT 0`,
	}
	for _, stmt := range adds {
		if _, err := db.Exec(stmt); err != nil && !strings.Contains(err.Error(), "duplicate column") {
			return err
		}
	}
	_, err := db.Exec(`CREATE INDEX IF NOT EXISTS idx_msg_expires ON messages(expires_at)`)
	return err
}

func (s *Store) Close() error { return s.db.Close() }

// PurgeExpired removes messages whose disappearing-message timer has run out. Reads
// already hide them, so this is what stops the database from being a record of
// conversations that were meant to leave no record. Returns how many went.
// PurgeExpired removes messages whose disappearing-message timer has run out and returns
// how many went, along with the attachment ids they referenced so the caller can delete the
// files. Deleting the row alone left the picture on disk and fetchable by id.
func (s *Store) PurgeExpired() (int64, []string, error) {
	now := nowMs()

	// Read what is about to go, so the files can be removed and the affected threads can
	// have their counts re-derived. A count is not decremented; it is recomputed, because
	// arithmetic on a number that other paths also write drifts.
	rows, err := s.db.Query(
		`SELECT COALESCE(attachments,''), thread_key FROM messages
		 WHERE expires_at != 0 AND expires_at <= ?`, now)
	if err != nil {
		return 0, nil, err
	}
	var files []string
	threads := map[string]struct{}{}
	for rows.Next() {
		var atts, key string
		if err := rows.Scan(&atts, &key); err != nil {
			rows.Close()
			return 0, nil, err
		}
		threads[key] = struct{}{}
		var parsed []Attachment
		if json.Unmarshal([]byte(atts), &parsed) == nil {
			for _, a := range parsed {
				if a.ID != "" {
					files = append(files, a.ID)
				}
			}
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, nil, err
	}

	res, err := s.db.Exec(
		`DELETE FROM messages WHERE expires_at != 0 AND expires_at <= ?`, now)
	if err != nil {
		return 0, nil, err
	}
	n, _ := res.RowsAffected()

	// A thread whose newest message just expired would otherwise keep showing it as unread
	// and keep it as the preview.
	for key := range threads {
		if err := s.recountThread(key); err != nil {
			return n, files, err
		}
	}
	return n, files, nil
}

// recountThread re-derives a thread's unread count and last timestamp from the messages
// that are actually still there.
func (s *Store) recountThread(key string) error {
	_, err := s.db.Exec(`
		UPDATE threads SET
		  unread = (SELECT COUNT(*) FROM messages
		            WHERE thread_key = threads.thread_key AND outgoing = 0 AND read = 0),
		  last_ts = COALESCE((SELECT MAX(ts) FROM messages
		                      WHERE thread_key = threads.thread_key), 0)
		WHERE thread_key = ?`, key)
	return err
}

// AllAttachmentIDs lists every attachment id the store references, so a wipe can remove the
// files as well as the rows. Reporting "the store is empty" while every picture is still on
// disk is the wrong kind of true.
func (s *Store) AllAttachmentIDs() ([]string, error) {
	rows, err := s.db.Query(`SELECT COALESCE(attachments,'') FROM messages`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var atts string
		if err := rows.Scan(&atts); err != nil {
			return nil, err
		}
		var parsed []Attachment
		if json.Unmarshal([]byte(atts), &parsed) == nil {
			for _, a := range parsed {
				if a.ID != "" {
					out = append(out, a.ID)
				}
			}
		}
	}
	return out, rows.Err()
}

// DeleteEverything empties the store: every message, thread, contact, and the cursor
// and account identity in meta. Used by the app's "delete Signal data". The tables are
// kept so the next sync has somewhere to land.
func (s *Store) DeleteEverything() error {
	_, err := s.db.Exec(`
		DELETE FROM messages;
		DELETE FROM threads;
		DELETE FROM contacts;
		DELETE FROM meta;`)
	return err
}

// InsertMessage is idempotent on msg_id. Returns the assigned seq and whether it
// was new. One logical Signal message can arrive as several notifications, and an
// imported backup can re-deliver messages we already have, so this must never
// duplicate.
func (s *Store) InsertMessage(m *Message) (int64, bool, error) {
	atts, _ := json.Marshal(m.Attachments)
	res, err := s.db.Exec(`
		INSERT OR IGNORE INTO messages
		  (msg_id, thread_key, ts, sender_uuid, sender_num, outgoing, body,
		   group_id, quote_ts, attachments, read, source, raw,
		   expires_in, expires_at, view_once)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		m.ID, m.ThreadKey, m.TS, m.SenderUUID, m.SenderNumber, b2i(m.Outgoing),
		m.Body, m.GroupID, m.QuoteTS, string(atts), b2i(m.Read), m.Source, m.Raw,
		m.ExpiresInSeconds, m.ExpiresAt, b2i(m.ViewOnce))
	if err != nil {
		return 0, false, err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		var seq int64
		if err := s.db.QueryRow(`SELECT seq FROM messages WHERE msg_id=?`, m.ID).Scan(&seq); err != nil {
			return 0, false, err
		}
		// The row is already here, but this copy may know something it did not.
		//
		// A message sent from the browser is stored by the send path, which cannot know the
		// thread's disappearing-message timer -- signal-cli applies that on the wire. The
		// sync echo that comes back moments later does carry it, and has the same id, so
		// INSERT OR IGNORE dropped the only copy that knew when the message should go. The
		// sender's copy of every disappearing message they sent then lived for ever.
		//
		// Only ever fills a gap: a deadline already recorded is never moved, so this cannot
		// be used to extend one.
		if m.ExpiresAt > 0 {
			if _, err := s.db.Exec(`
				UPDATE messages SET expires_in = ?, expires_at = ?
				WHERE msg_id = ? AND (expires_at = 0 OR expires_at IS NULL)`,
				m.ExpiresInSeconds, m.ExpiresAt, m.ID); err != nil {
				return seq, false, err
			}
		}
		return seq, false, nil
	}
	seq, _ := res.LastInsertId()
	// If this fails the message is in and the thread row is not, so the inbox shows nothing
	// for a message the store holds. Reported rather than swallowed; the caller logs it.
	if err := s.touchThread(m, seq); err != nil {
		return seq, true, err
	}
	return seq, true, nil
}

func (s *Store) touchThread(m *Message, seq int64) error {
	kind := "direct"
	if m.GroupID != "" {
		kind = "group"
	}
	unread := 0
	if !m.Outgoing && !m.Read {
		unread = 1
	}
	_, err := s.db.Exec(`
		INSERT INTO threads (thread_key, kind, last_ts, last_seq, unread)
		VALUES (?,?,?,?,?)
		ON CONFLICT(thread_key) DO UPDATE SET
		  last_ts  = MAX(last_ts, excluded.last_ts),
		  last_seq = MAX(last_seq, excluded.last_seq),
		  unread   = unread + excluded.unread`,
		m.ThreadKey, kind, m.TS, seq, unread)
	return err
}

func (s *Store) SetThreadMeta(key, kind, title string, members []string) error {
	mj, _ := json.Marshal(members)
	_, err := s.db.Exec(`
		INSERT INTO threads (thread_key, kind, title, members)
		VALUES (?,?,?,?)
		ON CONFLICT(thread_key) DO UPDATE SET
		  kind=excluded.kind,
		  -- A blank title is "I do not know", not "this thread has no name". signal-cli
		  -- reports one for a contact it has not resolved yet, and letting that through
		  -- erased a name the directory sync had already found, leaving a bare uuid.
		  title=CASE WHEN excluded.title != '' THEN excluded.title ELSE threads.title END,
		  members=CASE WHEN excluded.members NOT IN ('','null','[]')
		               THEN excluded.members ELSE threads.members END`,
		key, kind, title, string(mj))
	return err
}

func (s *Store) UpsertContact(uuid, number, name string) error {
	if uuid == "" {
		return nil
	}
	_, err := s.db.Exec(`
		INSERT INTO contacts (uuid, number, name, updated) VALUES (?,?,?,?)
		ON CONFLICT(uuid) DO UPDATE SET number=excluded.number, name=excluded.name,
		  updated=excluded.updated`,
		uuid, number, name, time.Now().UnixMilli())
	return err
}

// Meta is a tiny key/value corner of the store. The account's own uuid lives here so a
// restart knows it before the first envelope arrives rather than four seconds later.
func (s *Store) GetMeta(k string) string {
	var v string
	_ = s.db.QueryRow(`SELECT v FROM meta WHERE k=?`, k).Scan(&v)
	return v
}

func (s *Store) SetMeta(k, v string) error {
	_, err := s.db.Exec(`INSERT INTO meta (k,v) VALUES (?,?)
		ON CONFLICT(k) DO UPDATE SET v=excluded.v`, k, v)
	return err
}

// InstanceID identifies this particular database. Sequence numbers are only meaningful
// relative to the store that issued them, so a client holding a cursor needs to know when
// it is talking to a different store -- a rebuilt database, a move to another host -- and
// start again rather than wait forever for a sequence that will never come round.
func (s *Store) InstanceID() (string, error) {
	if v := s.GetMeta("instanceId"); v != "" {
		return v, nil
	}
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	id := hex.EncodeToString(b)
	return id, s.SetMeta("instanceId", id)
}

func (s *Store) MaxSeq() (int64, error) {
	var seq sql.NullInt64
	err := s.db.QueryRow(`SELECT MAX(seq) FROM messages`).Scan(&seq)
	return seq.Int64, err
}

// Changes returns everything after a cursor. This is how a phone that has been
// offline catches up, and it is the same path an import uses, so message order
// must never be assumed to match seq order -- callers sort by ts for display.
func (s *Store) Changes(sinceSeq int64, limit int) ([]*Message, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	rows, err := s.db.Query(`
		SELECT seq, msg_id, thread_key, ts,
		       COALESCE(sender_uuid,''), COALESCE(sender_num,''), outgoing,
		       COALESCE(body,''), COALESCE(group_id,''), COALESCE(quote_ts,0),
		       COALESCE(attachments,''), read, COALESCE(source,'live'),
		       COALESCE(expires_in,0), COALESCE(expires_at,0), COALESCE(view_once,0)
		-- Never hand out a message whose time is up, even if the sweep has not run yet.
		FROM messages WHERE seq > ? AND (expires_at = 0 OR expires_at > ?)
		ORDER BY seq LIMIT ?`, sinceSeq, nowMs(), limit)
	if err != nil {
		return nil, err
	}
	return scanMessages(rows)
}

func (s *Store) ThreadMessages(key string, beforeTS int64, limit int) ([]*Message, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	if beforeTS <= 0 {
		beforeTS = 1 << 62
	}
	rows, err := s.db.Query(`
		SELECT seq, msg_id, thread_key, ts,
		       COALESCE(sender_uuid,''), COALESCE(sender_num,''), outgoing,
		       COALESCE(body,''), COALESCE(group_id,''), COALESCE(quote_ts,0),
		       COALESCE(attachments,''), read, COALESCE(source,'live'),
		       COALESCE(expires_in,0), COALESCE(expires_at,0), COALESCE(view_once,0)
		FROM messages WHERE thread_key=? AND ts < ? AND (expires_at = 0 OR expires_at > ?)
		ORDER BY ts DESC LIMIT ?`,
		key, beforeTS, nowMs(), limit)
	if err != nil {
		return nil, err
	}
	return scanMessages(rows)
}

// ThreadMessagesBySeq pages a whole thread in insertion order, oldest first.
//
// ThreadMessages pages backwards by timestamp, which is right for a reader scrolling up and
// wrong for anything that must see every row exactly once. Two messages can share a
// timestamp -- ordinary in a group -- and a strict `ts <` boundary then skips whichever one
// did not end the page. Worse, a message with ts = 0, which an import can produce from a
// chatItem missing dateSent, pins the cursor at 0 for ever and the caller spins.
//
// seq is the autoincrement primary key: unique, strictly increasing, and never 0.
func (s *Store) ThreadMessagesBySeq(key string, afterSeq int64, limit int) ([]*Message, error) {
	if limit <= 0 || limit > 1000 {
		limit = 500
	}
	rows, err := s.db.Query(`
		SELECT seq, msg_id, thread_key, ts,
		       COALESCE(sender_uuid,''), COALESCE(sender_num,''), outgoing,
		       COALESCE(body,''), COALESCE(group_id,''), COALESCE(quote_ts,0),
		       COALESCE(attachments,''), read, COALESCE(source,'live'),
		       COALESCE(expires_in,0), COALESCE(expires_at,0), COALESCE(view_once,0)
		FROM messages WHERE thread_key=? AND seq > ? AND (expires_at = 0 OR expires_at > ?)
		ORDER BY seq LIMIT ?`,
		key, afterSeq, nowMs(), limit)
	if err != nil {
		return nil, err
	}
	return scanMessages(rows)
}

type ThreadRow struct {
	Key     string   `json:"threadKey"`
	Kind    string   `json:"kind"`
	Title   string   `json:"title,omitempty"`
	Members []string `json:"members,omitempty"`
	LastTS  int64    `json:"lastTs"`
	LastSeq int64    `json:"lastSeq"`
	Unread  int      `json:"unread"`
	// The counterpart's phone number, when we know it. The app needs this to pair a
	// Signal thread with the SMS thread for the same person; a thread key only ever
	// carries the uuid.
	Number string `json:"counterpartNumber,omitempty"`
}

func (s *Store) Threads() ([]*ThreadRow, error) {
	rows, err := s.db.Query(`
		SELECT t.thread_key, t.kind, COALESCE(t.title,''), COALESCE(t.members,'[]'),
		       t.last_ts, t.last_seq, t.unread,
		       COALESCE(c.number,'')
		FROM threads t
		LEFT JOIN contacts c
		  ON t.kind = 'direct'
		 AND c.uuid = substr(t.thread_key, 8)
		ORDER BY t.last_ts DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*ThreadRow
	for rows.Next() {
		t := &ThreadRow{}
		var mj string
		if err := rows.Scan(&t.Key, &t.Kind, &t.Title, &mj, &t.LastTS, &t.LastSeq,
			&t.Unread, &t.Number); err != nil {
			return nil, err
		}
		_ = json.Unmarshal([]byte(mj), &t.Members)
		out = append(out, t)
	}
	return out, rows.Err()
}

// MarkRead returns what it marked, grouped by who sent it, so the caller can tell those
// people it was read. A read receipt goes to the sender of each message, not to the thread.
func (s *Store) MarkRead(key string, upToTS int64) (int64, map[string][]int64, error) {
	rows, err := s.db.Query(`
		SELECT sender_uuid, ts FROM messages
		WHERE thread_key=? AND ts<=? AND outgoing=0 AND read=0`, key, upToTS)
	if err != nil {
		return 0, nil, err
	}
	bySender := map[string][]int64{}
	for rows.Next() {
		var sender sql.NullString
		var ts int64
		if err := rows.Scan(&sender, &ts); err != nil {
			rows.Close()
			return 0, nil, err
		}
		if sender.Valid && sender.String != "" {
			bySender[sender.String] = append(bySender[sender.String], ts)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, nil, err
	}

	res, err := s.db.Exec(`
		UPDATE messages SET read=1
		WHERE thread_key=? AND ts<=? AND outgoing=0 AND read=0`, key, upToTS)
	if err != nil {
		return 0, nil, err
	}
	n, _ := res.RowsAffected()
	if _, err := s.db.Exec(`
		UPDATE threads SET unread = MAX(0, unread - ?) WHERE thread_key=?`, n, key); err != nil {
		return n, bySender, err
	}
	return n, bySender, nil
}

func scanMessages(rows *sql.Rows) ([]*Message, error) {
	defer rows.Close()
	var out []*Message
	for rows.Next() {
		m := &Message{}
		var atts string
		var outg, read, viewOnce int
		if err := rows.Scan(&m.Seq, &m.ID, &m.ThreadKey, &m.TS, &m.SenderUUID,
			&m.SenderNumber, &outg, &m.Body, &m.GroupID, &m.QuoteTS, &atts,
			&read, &m.Source, &m.ExpiresInSeconds, &m.ExpiresAt, &viewOnce); err != nil {
			return nil, err
		}
		m.Outgoing, m.Read, m.ViewOnce = outg == 1, read == 1, viewOnce == 1
		_ = json.Unmarshal([]byte(atts), &m.Attachments)
		out = append(out, m)
	}
	return out, rows.Err()
}

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}

// CountMessages is how many messages the store holds, for telling someone what --wipe is
// about to destroy before they confirm it.
func (s *Store) CountMessages() (int64, error) {
	var n int64
	err := s.db.QueryRow(`SELECT COUNT(*) FROM messages`).Scan(&n)
	return n, err
}
