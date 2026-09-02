package main

import (
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

func main() {
	var (
		scAddr    = flag.String("signal-cli", "127.0.0.1:7583", "signal-cli JSON-RPC address (must be loopback)")
		account   = flag.String("account", "", "Signal account number, e.g. +15551234567 (required)")
		selfID    = flag.String("self-uuid", "", "own account UUID (auto-detected if omitted)")
		dataDir   = flag.String("data", "/var/lib/kotozute-bridge", "bridge data directory")
		scData    = flag.String("signal-cli-data", "/var/lib/signal-cli", "signal-cli data directory (for attachments)")
		attDays   = flag.Int("attachment-days", 90, "delete attachments older than this many days (0 = never)")
		attMB     = flag.Int64("attachment-max-mb", 2048, "cap the attachment store at this many MB (0 = no cap)")
		listen    = flag.String("listen", "0.0.0.0", "address to listen on")
		port      = flag.Int("port", 8422, "port to listen on")
		advert    = flag.String("advertise", "", "host/IP to put in the pairing payload (default: --listen)")
		showPair  = flag.Bool("pairing", false, "print the pairing payload and exit")
		importDir = flag.String("import", "", "import a Signal \"export chat history\" folder into the store, then exit")
	)
	flag.Parse()

	if *account == "" {
		fatal("--account is required (the Signal number signal-cli is linked to)")
	}
	if err := os.MkdirAll(*dataDir, 0o700); err != nil {
		fatal("data dir: %v", err)
	}

	auth, err := LoadOrCreateAuth(*dataDir)
	if err != nil {
		fatal("auth: %v", err)
	}
	advertHost := *advert
	if advertHost == "" || advertHost == "0.0.0.0" {
		advertHost = firstLocalIP()
	}
	cert, fp, err := LoadOrCreateCert(*dataDir, []string{advertHost, "localhost", "127.0.0.1"})
	if err != nil {
		fatal("tls: %v", err)
	}

	if *showPair {
		fmt.Println(PairingPayload(advertHost, *port, auth.Token(), fp))
		return
	}

	store, err := OpenStore(*dataDir + "/bridge.db")
	if err != nil {
		fatal("store: %v", err)
	}
	defer store.Close()

	// A one-shot: import the history and stop. Deliberately not something the running
	// daemon does on a signal or an endpoint -- it writes thousands of rows and copies
	// media, and it should happen when an operator asks for it, not when a packet does.
	if *importDir != "" {
		// The export carries no identifier for the account itself. The bridge learned it
		// when it first connected, so it comes from meta; --self-uuid overrides for a
		// store that has not run yet.
		self := *selfID
		if self == "" {
			self = store.GetMeta("selfUuid")
		}
		if self == "" {
			fatal("import: this account's own uuid is unknown -- run the bridge once so it " +
				"can learn it, or pass --self-uuid. Without it every message you sent has " +
				"no author and would be dropped.")
		}
		stats, err := ImportExport(store, *importDir, filepath.Join(*scData, "attachments"), self, *account)
		if err != nil {
			fatal("import: %v", err)
		}
		log.Printf("import: %s", stats)
		return
	}

	sc := NewSignalCLI(*scAddr, *account)
	self := *selfID
	if self == "" {
		// Remembered from a previous run. Without this the first few seconds after a
		// restart normalise messages without knowing whose account this is, and a Note
		// to Self drained from the queue in that window looks like someone else's.
		self = store.GetMeta("selfUuid")
		if self != "" {
			log.Printf("self uuid (remembered): %s", self)
		}
	}

	api := NewAPI(store, sc, auth, self, NewAttachments(*scData))

	// Every envelope signal-cli pushes lands here. This is the only writer of
	// live messages, so ordering and idempotency are settled in one place.
	// Envelopes that arrive before the account's own uuid is known are held rather than
	// guessed at. On any run after the first this never fills, because self is loaded
	// from the store above.
	var pendingMu sync.Mutex
	var pending []json.RawMessage

	var handle func(params json.RawMessage)
	handle = func(params json.RawMessage) {
		m, receipt, err := normalize(self, params)
		if err != nil {
			log.Printf("normalize: %v", err)
			return
		}
		if receipt != nil {
			return // delivery/read receipts are not stored yet
		}
		if m == nil {
			return
		}
		seq, isNew, err := store.InsertMessage(m)
		if err != nil {
			log.Printf("store: %v", err)
			return
		}
		m.Seq = seq
		if isNew {
			api.Broadcast(m)
		}
	}

	sc.OnEvent = func(params json.RawMessage) {
		if self == "" {
			pendingMu.Lock()
			if self == "" {
				pending = append(pending, params)
				pendingMu.Unlock()
				return
			}
			pendingMu.Unlock()
		}
		handle(params)
	}

	drainPending := func() {
		pendingMu.Lock()
		held := pending
		pending = nil
		pendingMu.Unlock()
		for _, p := range held {
			handle(p)
		}
		if len(held) > 0 {
			log.Printf("processed %d envelope(s) held until the account uuid was known", len(held))
		}
	}

	stop := make(chan struct{})
	go sc.Run(stop)
	go NewRetention(*scData, *attDays, *attMB).Run(stop)
	go sweepExpired(store, stop)

	// Resolve our own UUID once connected; the sync/sent distinction depends on it.
	go func() {
		for i := 0; i < 60; i++ {
			time.Sleep(2 * time.Second)
			if !sc.Connected() {
				continue
			}
			if self == "" {
				if u := detectSelfUUID(sc, *account); u != "" {
					pendingMu.Lock()
					self = u
					pendingMu.Unlock()
					api.self = u
					_ = store.SetMeta("selfUuid", u)
					log.Printf("self uuid: %s", u)
					drainPending()
				}
			}
			syncDirectory(sc, store)
			return
		}
	}()

	// Refresh contacts and groups periodically; names change and groups gain members.
	go func() {
		t := time.NewTicker(30 * time.Minute)
		defer t.Stop()
		for range t.C {
			if sc.Connected() {
				syncDirectory(sc, store)
			}
		}
	}()

	srv := &http.Server{
		Addr:              fmt.Sprintf("%s:%d", *listen, *port),
		Handler:           api.Handler(),
		TLSConfig:         &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12},
		ReadHeaderTimeout: 15 * time.Second,
	}

	log.Printf("kotozute-bridge listening on https://%s:%d", *listen, *port)
	log.Printf("pairing: %s", PairingPayload(advertHost, *port, "<token hidden - run --pairing>", fp))
	log.Printf("cert fingerprint: %s", fp)

	go func() {
		if err := srv.ListenAndServeTLS("", ""); err != nil && err != http.ErrServerClosed {
			fatal("http: %v", err)
		}
	}()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	log.Printf("shutting down")
	close(stop)
	_ = srv.Close()
}

func detectSelfUUID(sc *SignalCLI, account string) string {
	ids, err := sc.ListIdentities()
	if err != nil {
		return ""
	}
	for _, i := range ids {
		if i.Number == account && i.UUID != "" {
			return i.UUID
		}
	}
	return ""
}

func syncDirectory(sc *SignalCLI, store *Store) {
	if contacts, err := sc.ListContacts(); err == nil {
		for _, c := range contacts {
			name := strings.TrimSpace(c.Name)
			if name == "" {
				name = strings.TrimSpace(c.Profile.GivenName + " " + c.Profile.FamilyName)
			}
			_ = store.UpsertContact(c.UUID, c.Number, strings.TrimSpace(name))
			if c.UUID != "" {
				_ = store.SetThreadMeta("direct:"+c.UUID, "direct", strings.TrimSpace(name), nil)
			}
		}
	} else {
		log.Printf("listContacts: %v", err)
	}
	if groups, err := sc.ListGroups(); err == nil {
		for _, g := range groups {
			if !g.IsMember {
				continue
			}
			_ = store.SetThreadMeta("group:"+g.ID, "group", g.Name, nil)
		}
	} else {
		log.Printf("listGroups: %v", err)
	}
}

func firstLocalIP() string {
	// Best effort: used only to put a usable host in the pairing payload and cert.
	addrs, err := netInterfaceAddrs()
	if err != nil {
		return "127.0.0.1"
	}
	for _, a := range addrs {
		if !strings.Contains(a, ":") && !strings.HasPrefix(a, "127.") {
			return a
		}
	}
	return "127.0.0.1"
}

func fatal(f string, a ...any) {
	log.Printf("fatal: "+f, a...)
	os.Exit(1)
}

// sweepExpired deletes disappearing messages whose time is up.
//
// Separate from the attachment retention sweep, which runs every six hours: a Signal timer
// can be thirty seconds, and "deleted within six hours" is not what a disappearing message
// promises. Reads already hide an expired message, so this is not about what is shown --
// it is about how long the row survives on disk after it stopped being shown.
func sweepExpired(store *Store, stop <-chan struct{}) {
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		purge(store)
		select {
		case <-stop:
			return
		case <-tick.C:
		}
	}
}

func purge(store *Store) {
	n, err := store.PurgeExpired()
	if err != nil {
		log.Printf("expiry sweep: %v", err)
		return
	}
	// Logged positively: a sweep that silently does nothing looks the same whether it is
	// working or broken, and this one is only ever noticed when it has failed.
	if n > 0 {
		log.Printf("expiry sweep: removed %d expired message(s)", n)
	}
}
