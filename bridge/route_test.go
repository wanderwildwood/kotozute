package main

import "testing"

// A group's thread key carries a base64 group id, and base64 includes "/". Around half of
// this account's groups have one, so this is not a hypothetical.
func TestParseThreadRoute(t *testing.T) {
	cases := []struct {
		name, path, key, action string
		ok                      bool
	}{
		{"direct thread", "/v1/threads/direct:abc-123/messages", "direct:abc-123", "messages", true},
		{"group without a slash", "/v1/threads/group:AAAA==/send", "group:AAAA==", "send", true},
		{"group WITH a slash", "/v1/threads/group:ab/cd==/send", "group:ab/cd==", "send", true},
		{"group with several slashes", "/v1/threads/group:a/b/c==/read", "group:a/b/c==", "read", true},
		{"slash immediately before the action", "/v1/threads/group:abc/==/messages", "group:abc/==", "messages", true},
		{"no action", "/v1/threads/direct:abc", "", "", false},
		{"trailing separator, empty action", "/v1/threads/direct:abc/", "", "", false},
		{"empty key", "/v1/threads//messages", "", "", false},
		{"not this route", "/v1/state", "", "", false},
		{"prefix only", "/v1/threads/", "", "", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			key, action, ok := parseThreadRoute(c.path)
			if ok != c.ok {
				t.Fatalf("ok = %v, want %v", ok, c.ok)
			}
			if !c.ok {
				return
			}
			if key != c.key {
				t.Errorf("key = %q, want %q", key, c.key)
			}
			if action != c.action {
				t.Errorf("action = %q, want %q", action, c.action)
			}
		})
	}
}
