package main

import (
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// signal-cli downloads attachments into <data dir>/attachments/<id>, where the id it
// reports in the envelope already carries the extension. So the bridge serves them from
// disk rather than round-tripping getAttachment through JSON-RPC.
type Attachments struct{ dir string }

func NewAttachments(signalCliData string) *Attachments {
	return &Attachments{dir: filepath.Join(signalCliData, "attachments")}
}

// The id comes from the network, so it is matched against a strict shape rather than
// merely cleaned: no separators, no dots beyond the extension, nothing that could climb
// out of the attachments directory.
var attachmentID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}(\.[A-Za-z0-9]{1,8})?$`)

func (a *Attachments) Serve(w http.ResponseWriter, r *http.Request, id string) {
	if !attachmentID.MatchString(id) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad attachment id"})
		return
	}
	path := filepath.Join(a.dir, id)

	// Belt and braces: after joining, the result must still sit inside the directory.
	if rel, err := filepath.Rel(a.dir, path); err != nil || strings.HasPrefix(rel, "..") {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad attachment id"})
		return
	}

	f, err := os.Open(path)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "no such attachment"})
		return
	}
	defer f.Close()

	info, err := f.Stat()
	if err != nil || info.IsDir() {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "no such attachment"})
		return
	}

	w.Header().Set("Content-Type", contentTypeFor(id))
	w.Header().Set("Content-Disposition", "inline")
	http.ServeContent(w, r, id, info.ModTime(), f)
}

func contentTypeFor(name string) string {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".mp4":
		return "video/mp4"
	case ".m4a", ".aac":
		return "audio/aac"
	case ".ogg", ".oga":
		return "audio/ogg"
	case ".pdf":
		return "application/pdf"
	case ".txt":
		return "text/plain; charset=utf-8"
	default:
		return "application/octet-stream"
	}
}
