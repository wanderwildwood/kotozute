package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Auth holds the bearer token the phone must present. It is generated once, on
// first run, and written 0600. signal-cli itself has no authentication at all, so
// this token is the only thing between the network and the Signal account.
type Auth struct{ token string }

func LoadOrCreateAuth(dir string) (*Auth, error) {
	p := filepath.Join(dir, "token")
	if b, err := os.ReadFile(p); err == nil {
		t := strings.TrimSpace(string(b))
		if len(t) >= 32 {
			return &Auth{token: t}, nil
		}
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	t := base64.RawURLEncoding.EncodeToString(raw)
	if err := os.WriteFile(p, []byte(t+"\n"), 0o600); err != nil {
		return nil, err
	}
	return &Auth{token: t}, nil
}

func (a *Auth) Token() string { return a.token }

// Check compares in constant time. A timing-safe compare is not paranoia here:
// the reward for guessing is the user's entire message history.
func (a *Auth) Check(header string) bool {
	const p = "Bearer "
	if !strings.HasPrefix(header, p) {
		return false
	}
	got := strings.TrimSpace(header[len(p):])
	return subtle.ConstantTimeCompare([]byte(got), []byte(a.token)) == 1
}

// LoadOrCreateCert makes a long-lived self-signed certificate. There is no CA to
// trust on a LAN, so the app pins this certificate's SHA-256 fingerprint, which
// the pairing payload carries. That is strictly better than plaintext and avoids
// asking the user to install anything.
func LoadOrCreateCert(dir string, hosts []string) (tls.Certificate, string, error) {
	cp := filepath.Join(dir, "cert.pem")
	kp := filepath.Join(dir, "key.pem")

	if _, err := os.Stat(cp); err == nil {
		c, err := tls.LoadX509KeyPair(cp, kp)
		if err != nil {
			return tls.Certificate{}, "", err
		}
		return c, fingerprint(c.Certificate[0]), nil
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	tmpl := x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "kotozute-bridge"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	for _, h := range hosts {
		if ip := net.ParseIP(h); ip != nil {
			tmpl.IPAddresses = append(tmpl.IPAddresses, ip)
		} else if h != "" {
			tmpl.DNSNames = append(tmpl.DNSNames, h)
		}
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	kb, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	if err := os.WriteFile(cp, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644); err != nil {
		return tls.Certificate{}, "", err
	}
	if err := os.WriteFile(kp, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0o600); err != nil {
		return tls.Certificate{}, "", err
	}
	c, err := tls.LoadX509KeyPair(cp, kp)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	return c, fingerprint(der), nil
}

func fingerprint(der []byte) string {
	sum := sha256.Sum256(der)
	parts := make([]string, 0, len(sum))
	for _, b := range sum {
		parts = append(parts, hex.EncodeToString([]byte{b}))
	}
	return strings.ToUpper(strings.Join(parts, ":"))
}

// PairingPayload is what the user carries to the phone: where to connect, the
// token, and the fingerprint to pin.
func PairingPayload(host string, port int, token, fp string) string {
	return fmt.Sprintf("kotozute-bridge://%s:%d/?token=%s&fp=%s",
		host, port, token, strings.ReplaceAll(fp, ":", ""))
}
