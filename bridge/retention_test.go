package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func write(t *testing.T, dir, name string, size int, age time.Duration) string {
	t.Helper()
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, make([]byte, size), 0o644); err != nil {
		t.Fatal(err)
	}
	when := time.Now().Add(-age)
	if err := os.Chtimes(p, when, when); err != nil {
		t.Fatal(err)
	}
	return p
}

func names(t *testing.T, dir string) []string {
	t.Helper()
	es, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	out := []string{}
	for _, e := range es {
		out = append(out, e.Name())
	}
	return out
}

func TestSweepRemovesOnlyOldFiles(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "attachments")
	os.MkdirAll(dir, 0o700)
	write(t, dir, "old.png", 10, 100*24*time.Hour)
	write(t, dir, "new.png", 10, 1*time.Hour)

	NewRetention(root, 90, 0).sweep()

	got := names(t, dir)
	if len(got) != 1 || got[0] != "new.png" {
		t.Fatalf("expected only new.png to survive, got %v", got)
	}
}

func TestSweepHonoursSizeCapOldestFirst(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "attachments")
	os.MkdirAll(dir, 0o700)
	// All young, so only the cap can act. 3 MB total against a 2 MB cap.
	write(t, dir, "a.png", 1024*1024, 3*time.Hour)
	write(t, dir, "b.png", 1024*1024, 2*time.Hour)
	write(t, dir, "c.png", 1024*1024, 1*time.Hour)

	NewRetention(root, 0, 2).sweep()

	got := names(t, dir)
	if len(got) != 2 {
		t.Fatalf("expected 2 files under the cap, got %v", got)
	}
	for _, n := range got {
		if n == "a.png" {
			t.Fatalf("the oldest should have gone first, got %v", got)
		}
	}
}

func TestSweepKeepsEverythingWhenDisabled(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "attachments")
	os.MkdirAll(dir, 0o700)
	write(t, dir, "ancient.png", 10, 5000*24*time.Hour)

	NewRetention(root, 0, 0).sweep()

	if got := names(t, dir); len(got) != 1 {
		t.Fatalf("retention disabled should delete nothing, got %v", got)
	}
}

func TestSweepSurvivesMissingDirectory(t *testing.T) {
	NewRetention(t.TempDir(), 90, 10).sweep() // must not panic
}
