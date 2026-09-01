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
	"strings"
	"syscall"
	"time"
)

func main() {
	var (
		scAddr  = flag.String("signal-cli", "127.0.0.1:7583", "signal-cli JSON-RPC address (must be loopback)")
		account = flag.String("account", "", "Signal account number, e.g. +15551234567 (required)")
		selfID  = flag.String("self-uuid", "", "own account UUID (auto-detected if omitted)")
		dataDir = flag.String("data", "/var/lib/kotozute-bridge", "bridge data directory")
		scData  = flag.String("signal-cli-data", "/var/lib/signal-cli", "signal-cli data directory (for attachments)")
		attDays = flag.Int("attachment-days", 90, "delete attachments older than this many days (0 = never)")
		attMB   = flag.Int64("attachment-max-mb", 2048, "cap the attachment store at this many MB (0 = no cap)")
		listen  = flag.String("listen", "0.0.0.0", "address to listen on")
		port    = flag.Int("port", 8422, "port to listen on")
		advert  = flag.String("advertise", "", "host/IP to put in the pairing payload (default: --listen)")
		showPair = flag.Bool("pairing", false, "print the pairing payload and exit")
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

	sc := NewSignalCLI(*scAddr, *account)
	self := *selfID

	api := NewAPI(store, sc, auth, self, NewAttachments(*scData))

	// Every envelope signal-cli pushes lands here. This is the only writer of
	// live messages, so ordering and idempotency are settled in one place.
	sc.OnEvent = func(params json.RawMessage) {
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

	stop := make(chan struct{})
	go sc.Run(stop)
	go NewRetention(*scData, *attDays, *attMB).Run(stop)

	// Resolve our own UUID once connected; the sync/sent distinction depends on it.
	go func() {
		for i := 0; i < 60; i++ {
			time.Sleep(2 * time.Second)
			if !sc.Connected() {
				continue
			}
			if self == "" {
				if u := detectSelfUUID(sc, *account); u != "" {
					self = u
					api.self = u
					log.Printf("self uuid: %s", u)
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
		Addr:      fmt.Sprintf("%s:%d", *listen, *port),
		Handler:   api.Handler(),
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12},
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
