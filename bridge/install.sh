#!/bin/bash
#
# Set up the Signal side of Messaging on this computer.
#
# There are three pieces and this puts all of them in place:
#
#   signal-cli        speaks Signal. Runs as a daemon on loopback only.
#   kotozute-bridge   sits in front of it, stores messages, and is the only thing
#                     the phone ever talks to.
#   two systemd units so both come back after a reboot.
#
# What it will not do for you: register or link your Signal account, which needs a code
# only you can receive, and pairing the phone, which is a paste into Desktop Sync. It tells
# you exactly what to do for both, at the point where you need to do it.
#
# Re-running is safe. Anything already in place is left alone and said so.

set -euo pipefail

SIGNAL_CLI_VERSION="${SIGNAL_CLI_VERSION:-0.14.7}"
SIGNAL_CLI_DIR=/opt/signal-cli-dist
SIGNAL_CLI="$SIGNAL_CLI_DIR/signal-cli-$SIGNAL_CLI_VERSION/bin/signal-cli"
SIGNAL_CLI_DATA=/var/lib/signal-cli
BRIDGE_BIN=/usr/local/bin/kotozute-bridge
BRIDGE_DATA=/var/lib/kotozute-bridge
RPC_ADDR=127.0.0.1:7583
BRIDGE_PORT="${BRIDGE_PORT:-8422}"

say()  { printf '\n== %s\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }
die()  { printf '\n%s\n' "$1" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run this with sudo: it installs into /opt, /usr/local/bin and /etc/systemd."
have systemctl || die "This script sets up systemd services and this machine does not have systemd.
The pieces still work by hand: run signal-cli's daemon on $RPC_ADDR with
--receive-mode on-connection, and kotozute-bridge pointing at it. See README.md."

# --- what this machine will be known as ------------------------------------------------
#
# The phone connects to an address, so the pairing payload has to carry one that the phone
# can actually reach. A guess here is the difference between "it works" and a page that
# never loads, so it is asked rather than assumed when it cannot be worked out.
ADVERTISE="${ADVERTISE:-}"
if [ -z "$ADVERTISE" ]; then
  ADVERTISE=$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+' || true)
fi
[ -n "$ADVERTISE" ] || die "Could not work out this machine's address on your network.
Re-run with ADVERTISE=192.168.x.y $0"

say "This machine will be advertised to the phone as $ADVERTISE:$BRIDGE_PORT"

# --- signal-cli -------------------------------------------------------------------------
if [ -x "$SIGNAL_CLI" ]; then
  say "signal-cli $SIGNAL_CLI_VERSION already installed"
else
  say "Installing signal-cli $SIGNAL_CLI_VERSION"
  have java || die "signal-cli needs Java 21 or newer. Install a JDK first (on Debian/Ubuntu:
  apt install openjdk-21-jre-headless) and run this again."
  have curl || die "curl is needed to download signal-cli."
  mkdir -p "$SIGNAL_CLI_DIR"
  url="https://github.com/AsamK/signal-cli/releases/download/v$SIGNAL_CLI_VERSION/signal-cli-$SIGNAL_CLI_VERSION.tar.gz"
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  curl -fsSL "$url" -o "$tmp/signal-cli.tar.gz" || die "Could not download $url"
  tar -C "$SIGNAL_CLI_DIR" -xzf "$tmp/signal-cli.tar.gz"
  [ -x "$SIGNAL_CLI" ] || die "The archive did not contain $SIGNAL_CLI."
  echo "  installed to $SIGNAL_CLI"
fi
mkdir -p "$SIGNAL_CLI_DATA"; chmod 700 "$SIGNAL_CLI_DATA"

# --- the bridge ---------------------------------------------------------------------------
say "Installing kotozute-bridge"
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -f "$here/main.go" ] && have go; then
  echo "  building from source in $here"
  ( cd "$here" && CGO_ENABLED=0 go build -trimpath -o "$BRIDGE_BIN" . )
elif [ -x "$here/kotozute-bridge" ]; then
  echo "  using the prebuilt binary beside this script"
  install -m0755 "$here/kotozute-bridge" "$BRIDGE_BIN"
else
  die "No way to get a bridge binary: Go is not installed and there is no prebuilt
kotozute-bridge next to this script. Install Go, or build it elsewhere with
  CGO_ENABLED=0 go build -o kotozute-bridge .
and put the result beside install.sh."
fi
mkdir -p "$BRIDGE_DATA"; chmod 700 "$BRIDGE_DATA"
echo "  installed to $BRIDGE_BIN"

# --- the account ---------------------------------------------------------------------------
#
# Two ways in, and which one you want is a real decision rather than a detail:
#
#   link      this computer becomes another device on a Signal account your phone already
#             holds. Nothing about your existing Signal changes. If that phone ever stops
#             being registered, this stops working with it.
#   register  this computer becomes the account. Signal's own app stops working for that
#             number, and Signal's Android app cannot be a secondary, so there is no phone
#             app afterwards. Choose it only if you mean to.
ACCOUNT="${ACCOUNT:-}"
if [ -z "$ACCOUNT" ]; then
  # Read the number out of signal-cli's own index rather than asking signal-cli. The CLI
  # takes the account lock, and on a re-run the daemon is already holding it -- so
  # `listAccounts` does not fail, it simply blocks for ever, and the installer hangs with
  # no output. accounts.json is just a file.
  ACCOUNT=$(grep -oE '"number"[[:space:]]*:[[:space:]]*"\+[0-9]+"' \
              "$SIGNAL_CLI_DATA/data/accounts.json" 2>/dev/null \
            | grep -oE '\+[0-9]+' | head -1 || true)
fi

if [ -n "$ACCOUNT" ]; then
  say "Signal account $ACCOUNT is already set up on this machine"
else
  say "No Signal account on this machine yet"
  cat <<'EOF'
  Do that now, in another terminal, then run this script again.

  To LINK to a phone that already has Signal (the usual choice):
      signal-cli --config /var/lib/signal-cli link -n "kotozute"
    It prints an sgnl:// URI. Turn it into a QR (qrencode -t utf8 '<uri>') and scan it
    with Signal on your phone: Settings -> Linked devices -> +.

  To REGISTER this machine as the account itself, read the warning above first:
      signal-cli --config /var/lib/signal-cli -a +15551234567 register
    You will need a captcha from https://signalcaptchas.org/registration/generate.html
    and the code Signal sends you, then:
      signal-cli --config /var/lib/signal-cli -a +15551234567 verify 123456
EOF
  exit 0
fi

# --- services ----------------------------------------------------------------------------
say "Writing systemd units"
java_home=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")

cat > /etc/systemd/system/signal-cli.service <<EOF
[Unit]
Description=signal-cli daemon - JSON-RPC on localhost only
Documentation=https://github.com/AsamK/signal-cli
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=$SIGNAL_CLI \\
  --config $SIGNAL_CLI_DATA \\
  daemon --tcp $RPC_ADDR --receive-mode on-connection --no-receive-stdout
Restart=on-failure
RestartSec=15
# Deliberately localhost-only: signal-cli's JSON-RPC has NO authentication. Anything that
# can reach this port can read and send Signal as the account owner. Do not change it to
# 0.0.0.0 without an authenticating layer in front -- which is what the bridge is.
#
# on-connection, not on-start: with on-start signal-cli acknowledges messages to Signal's
# servers whether or not anything is listening, so anything arriving while the bridge is
# down is gone. on-connection leaves them queued server-side.
Environment=JAVA_HOME=$java_home

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/kotozute-bridge.service <<EOF
[Unit]
Description=kotozute-bridge - authenticated Signal bridge for the Messaging app
After=network-online.target signal-cli.service
Wants=network-online.target
Requires=signal-cli.service

[Service]
Type=simple
User=root
ExecStart=$BRIDGE_BIN \\
  --account $ACCOUNT \\
  --signal-cli $RPC_ADDR \\
  --data $BRIDGE_DATA \\
  --listen 0.0.0.0 --port $BRIDGE_PORT \\
  --advertise $ADVERTISE \\
  --signal-cli-data $SIGNAL_CLI_DATA
Restart=always
RestartSec=10
# Unlike signal-cli's own port this one is meant to be reachable: TLS with a pinned
# self-signed certificate and a bearer token. The token in $BRIDGE_DATA/token is the only
# credential there is -- treat it as the account.
StateDirectory=kotozute-bridge
StateDirectoryMode=0700

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now signal-cli >/dev/null 2>&1
echo "  signal-cli: $(systemctl is-active signal-cli)"
sleep 8
systemctl enable --now kotozute-bridge >/dev/null 2>&1
sleep 4
echo "  kotozute-bridge: $(systemctl is-active kotozute-bridge)"

if [ "$(systemctl is-active kotozute-bridge)" != "active" ]; then
  die "The bridge did not start. What it said:
$(journalctl -u kotozute-bridge -n 15 --no-pager -o cat)"
fi

# --- pairing ------------------------------------------------------------------------------
say "Pair your phone"
cat <<EOF
Open Desktop Sync in a browser (Messaging -> Settings -> Desktop Sync -> Show link) and
paste the line below into the box at the top of the conversation list. Do not type it into
the phone: two thirds of it is a fingerprint.

EOF
"$BRIDGE_BIN" --account "$ACCOUNT" --data "$BRIDGE_DATA" --signal-cli-data "$SIGNAL_CLI_DATA" \
  --advertise "$ADVERTISE" --port "$BRIDGE_PORT" --pairing
cat <<EOF

Then in Messaging: Settings -> Signal -> turn it on.

Signal threads begin empty and fill from now. To bring older messages across, export them
from Signal Desktop (Settings -> Chats -> Export chat history) and:
  kotozute-bridge --account $ACCOUNT --import /path/to/the-export
EOF
