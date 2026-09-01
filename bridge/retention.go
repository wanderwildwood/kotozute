package main

import (
	"log"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// Attachments accumulate on whatever disk signal-cli lives on, and on a small eMMC that
// is a real ceiling. Nothing else prunes them: signal-cli downloads and forgets.
//
// Deleting one means the phone can no longer fetch it, which is why the default is
// generous and age-based -- the same bargain Signal's own media retention makes.
type Retention struct {
	dir      string
	maxAge   time.Duration
	maxBytes int64
}

func NewRetention(signalCliData string, days int, maxMB int64) *Retention {
	return &Retention{
		dir:      filepath.Join(signalCliData, "attachments"),
		maxAge:   time.Duration(days) * 24 * time.Hour,
		maxBytes: maxMB * 1024 * 1024,
	}
}

func (r *Retention) Run(stop <-chan struct{}) {
	r.sweep()
	t := time.NewTicker(6 * time.Hour)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			r.sweep()
		}
	}
}

type attFile struct {
	path string
	mod  time.Time
	size int64
}

func (r *Retention) sweep() {
	entries, err := os.ReadDir(r.dir)
	if err != nil {
		return // no attachments yet is not a problem
	}

	files := make([]attFile, 0, len(entries))
	var total int64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		files = append(files, attFile{filepath.Join(r.dir, e.Name()), info.ModTime(), info.Size()})
		total += info.Size()
	}
	if len(files) == 0 {
		return
	}

	// Oldest first, so the age pass and the size pass agree on what goes.
	sort.Slice(files, func(i, j int) bool { return files[i].mod.Before(files[j].mod) })

	cutoff := time.Now().Add(-r.maxAge)
	removed, freed := 0, int64(0)

	for i := range files {
		tooOld := r.maxAge > 0 && files[i].mod.Before(cutoff)
		overCap := r.maxBytes > 0 && total-freed > r.maxBytes
		// Sorted oldest first, so once a file is young enough to keep every later one
		// is too. Only the size cap can still call for more.
		if !tooOld && !overCap {
			break
		}
		if err := os.Remove(files[i].path); err != nil {
			continue
		}
		removed++
		freed += files[i].size
	}

	if removed > 0 {
		log.Printf("attachments: removed %d file(s), freed %.1f MB, %.1f MB remain",
			removed, float64(freed)/(1024*1024), float64(total-freed)/(1024*1024))
	}
}
