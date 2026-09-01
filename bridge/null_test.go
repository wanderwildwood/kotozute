package main

import (
	"path/filepath"
	"testing"
)

// The schema on an existing database permits NULL in several columns, and a row written by
// anything other than InsertMessage -- an import, a manual repair -- can carry one. Before
// this was handled, one such row made the whole thread return 500 rather than the row being
// skipped or read as empty.
func TestReadsSurviveNullColumns(t *testing.T) {
	dir := t.TempDir()
	st, err := OpenStore(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	// Write the way something else might: only the columns it knows about.
	_, err = st.db.Exec(`INSERT INTO messages (msg_id, thread_key, ts, outgoing, read)
	                     VALUES ('partial:1', 'group:a/b==', 1000, 0, 0)`)
	if err != nil {
		t.Fatalf("insert: %v", err)
	}

	msgs, err := st.ThreadMessages("group:a/b==", 0, 10)
	if err != nil {
		t.Fatalf("ThreadMessages returned an error on a partial row: %v", err)
	}
	if len(msgs) != 1 {
		t.Fatalf("got %d messages, want 1", len(msgs))
	}
	if msgs[0].SenderNumber != "" || msgs[0].Body != "" {
		t.Errorf("expected empty strings for absent columns, got %+v", msgs[0])
	}

	changes, err := st.Changes(0, 10)
	if err != nil {
		t.Fatalf("Changes returned an error on a partial row: %v", err)
	}
	if len(changes) != 1 {
		t.Fatalf("Changes got %d, want 1", len(changes))
	}
}
