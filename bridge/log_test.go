package main

import "testing"

// r.URL.Path is percent-decoded, so a request for /v1/%0Akotozute-bridge:%20paired put a
// real newline into the log. journald splits stderr on newlines, so an unauthenticated
// caller on the LAN could write entries indistinguishable from the bridge's own.
func TestALoggedPathCannotForgeAnEntry(t *testing.T) {
	got := redactPath("/v1/\nkotozute-bridge: pairing succeeded")
	for _, r := range got {
		if r == '\n' || r == '\r' {
			t.Fatalf("a newline survived into the log line: %q", got)
		}
	}
}

// And it still says which route was called, without saying who it was about.
func TestALoggedPathKeepsTheRouteAndDropsTheIdentity(t *testing.T) {
	got := redactPath("/v1/threads/direct:11111111-1111-4111-8111-111111111111/messages")
	if got != "/v1/threads/direct:_/messages" {
		t.Errorf("got %q", got)
	}
	if got := redactPath("/v1/attachments/abc123.jpg"); got != "/v1/attachments/_" {
		t.Errorf("attachment id survived: %q", got)
	}
}
