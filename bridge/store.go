package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
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
  sender_uuid TEXT,
  sender_num  TEXT,
  outgoing    INTEGER NOT NULL DEFAULT 0,
  body        TEXT,
  group_id    TEXT,
  quote_ts    INTEGER,
  attachments TEXT,                      -- json array
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
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// InsertMessage is idempotent on msg_id. Returns the assigned seq and whether it
// was new. One logical Signal message can arrive as several notifications, and an
// imported backup can re-deliver messages we already have, so this must never
// duplicate.
func (s *Store) InsertMessage(m *Message) (int64, bool, error) {
	atts, _ := json.Marshal(m.Attachments)
	res, err := s.db.Exec(`
		INSERT OR IGNORE INTO messages
		  (msg_id, thread_key, ts, sender_uuid, sender_num, outgoing, body,
		   group_id, quote_ts, attachments, read, source, raw)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		m.ID, m.ThreadKey, m.TS, m.SenderUUID, m.SenderNumber, b2i(m.Outgoing),
		m.Body, m.GroupID, m.QuoteTS, string(atts), b2i(m.Read), m.Source, m.Raw)
	if err != nil {
		return 0, false, err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		var seq int64
		err := s.db.QueryRow(`SELECT seq FROM messages WHERE msg_id=?`, m.ID).Scan(&seq)
		return seq, false, err
	}
	seq, _ := res.LastInsertId()
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
		  kind=excluded.kind, title=excluded.title, members=excluded.members`,
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
		SELECT seq,msg_id,thread_key,ts,sender_uuid,sender_num,outgoing,body,
		       group_id,quote_ts,attachments,read,source
		FROM messages WHERE seq > ? ORDER BY seq LIMIT ?`, sinceSeq, limit)
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
		SELECT seq,msg_id,thread_key,ts,sender_uuid,sender_num,outgoing,body,
		       group_id,quote_ts,attachments,read,source
		FROM messages WHERE thread_key=? AND ts < ? ORDER BY ts DESC LIMIT ?`,
		key, beforeTS, limit)
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

func (s *Store) MarkRead(key string, upToTS int64) (int64, error) {
	res, err := s.db.Exec(`
		UPDATE messages SET read=1
		WHERE thread_key=? AND ts<=? AND outgoing=0 AND read=0`, key, upToTS)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	_, err = s.db.Exec(`
		UPDATE threads SET unread = MAX(0, unread - ?) WHERE thread_key=?`, n, key)
	return n, err
}

func scanMessages(rows *sql.Rows) ([]*Message, error) {
	defer rows.Close()
	var out []*Message
	for rows.Next() {
		m := &Message{}
		var atts string
		var outg, read int
		if err := rows.Scan(&m.Seq, &m.ID, &m.ThreadKey, &m.TS, &m.SenderUUID,
			&m.SenderNumber, &outg, &m.Body, &m.GroupID, &m.QuoteTS, &atts,
			&read, &m.Source); err != nil {
			return nil, err
		}
		m.Outgoing, m.Read = outg == 1, read == 1
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
