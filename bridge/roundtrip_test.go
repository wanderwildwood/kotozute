package main

import (
	"path/filepath"
	"testing"
)

// Export the store, import it into an empty one, and hand back the second store. This is
// the procedure the README gives for backing up and for moving to another machine, so what
// survives it is what a user actually keeps.
func roundTrip(t *testing.T, seed func(*Store)) *Store {
	t.Helper()
	withFrozenClock(t)

	src, err := OpenStore(filepath.Join(t.TempDir(), "src.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	seed(src)

	exportDir, attach := t.TempDir(), t.TempDir()
	const selfACI = "00000000-0000-4000-8000-000000000000"
	if _, err := ExportStore(src, exportDir, attach, selfACI); err != nil {
		t.Fatal(err)
	}

	dst, err := OpenStore(filepath.Join(t.TempDir(), "dst.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { dst.Close() })
	if _, err := ImportExport(dst, exportDir, attach, selfACI, "+15559998888"); err != nil {
		t.Fatal(err)
	}
	return dst
}

func threadNamed(t *testing.T, st *Store, key string) *ThreadRow {
	t.Helper()
	rows, err := st.Threads()
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range rows {
		if r.Key == key {
			return r
		}
	}
	return nil
}

// A conversation with someone whose UUID we never learned is keyed on their number --
// normalize falls back to it deliberately. Export wrote that number into the "aci" field,
// which the importer refuses, so the entire conversation was dropped on the way back in.
func TestAThreadKeyedOnANumberSurvivesTheRoundTrip(t *testing.T) {
	dst := roundTrip(t, func(src *Store) {
		if _, _, err := src.InsertMessage(&Message{
			ID: "n1", ThreadKey: "direct:+15550001111", TS: testNow,
			SenderNumber: "+15550001111", Body: "no uuid for me",
		}); err != nil {
			t.Fatal(err)
		}
	})

	msgs, err := dst.ThreadMessages("direct:+15550001111", 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(msgs) != 1 {
		t.Fatalf("the conversation did not survive: %d message(s) restored, want 1", len(msgs))
	}
	if msgs[0].Body != "no uuid for me" {
		t.Errorf("restored body %q", msgs[0].Body)
	}
}

// Restoring a backup used to give back every group named with its raw zkgroup id, because
// the title loop only looked at direct recipients.
func TestAGroupComesBackWithItsName(t *testing.T) {
	const key = "group:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	dst := roundTrip(t, func(src *Store) {
		if _, _, err := src.InsertMessage(&Message{
			ID: "g1", ThreadKey: key, TS: testNow,
			SenderUUID: "11111111-1111-4111-8111-111111111111",
			GroupID:    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
			Body:       "in the group",
		}); err != nil {
			t.Fatal(err)
		}
		if err := src.SetThreadMeta(key, "group", "Bridge Club", nil); err != nil {
			t.Fatal(err)
		}
	})

	row := threadNamed(t, dst, key)
	if row == nil {
		t.Fatal("the group thread did not survive the round trip")
	}
	if row.Title != "Bridge Club" {
		t.Errorf("restored title %q, want %q -- a group named by its base64 id is not a restored backup",
			row.Title, "Bridge Club")
	}
}
