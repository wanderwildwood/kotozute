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
# Re-running is how you upgrade. It rebuilds and reinstalls the bridge, REWRITES both unit
# files from this run's environment, and restarts both services. That means any hand-editing
# you did to those units is lost -- if you have customised them, diff before re-running.
# signal-cli itself and your account are left alone.

set -euo pipefail

SIGNAL_CLI_VERSION="${SIGNAL_CLI_VERSION:-0.14.7}"

# The SHA-256 of the tarball that version publishes.
#
# This script downloads a program and then runs it as root, holding a Signal account. HTTPS
# proves the bytes came from GitHub; it does not prove they are the bytes this script was
# written against, and that difference is the whole question once the answer becomes a root
# service. Checked, a tampered or truncated download stops here rather than being installed.
#
# Override SIGNAL_CLI_VERSION and this goes unverified, because a hash pinned to one version
# cannot vouch for another -- the script says so plainly rather than comparing against the
# wrong thing. Anyone doing that should check the .asc signature GitHub publishes beside the
# release instead.
SIGNAL_CLI_SHA256="0e1eefdf4a2109edf7c899c9d1667167c54ac12c3ec824f27db7c1dac4fa7506"
SIGNAL_CLI_SHA256_FOR="0.14.7"
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

# --- the machine has to be x86-64 --------------------------------------------------------
#
# Checked here, first, because the failure otherwise comes much later and looks like
# something else entirely. signal-cli is Java, so the tarball installs and the checksum
# passes on any architecture -- but the Signal protocol itself is a Rust library loaded
# through JNI, and the only Linux build inside libsignal-client is x86-64:
#
#   libsignal_jni_amd64.so        Linux, x86-64
#   libsignal_jni_amd64.dylib     macOS, x86-64
#   libsignal_jni_aarch64.dylib   macOS, arm64
#   signal_jni_amd64.dll          Windows, x86-64
#
# There is no Linux arm64. On a Raspberry Pi or an ARM NAS everything below would appear to
# work -- download verified, units written, services started -- and then signal-cli would die
# on its first message inside a Java stack trace about a missing native library. Better to
# say so now.
OS="$(uname -s)"
ARCH="$(uname -m)"

# macOS is fine for signal-cli -- the jar carries both an Intel and an Apple Silicon dylib --
# but this installer writes systemd units, which macOS does not have. Say which of those two
# things is the problem, because they have different answers.
if [ "$OS" != "Linux" ]; then
  die "This installer is Linux-only: it writes systemd units, and this is $OS.

signal-cli itself runs fine on $OS -- the Signal native library ships for macOS on both Intel
and Apple Silicon, and for Windows on x86-64. What is missing here is only the service
plumbing. Run the two pieces by hand, or under launchd, following \"Doing it by hand\" in
bridge/README.md. On Windows, WSL2 is x86-64 Linux and this script works there unchanged."
fi

case "$ARCH" in
  x86_64|amd64) ;;
  *)
    die "This machine is Linux on $ARCH, and signal-cli ships no Signal native library for it.

On Linux only x86-64 is covered by the official build. Everything here would install cleanly
on $ARCH and then fail at the first message, which is why this stops now rather than later.

Your options:
  - Run the bridge on an x86-64 machine instead. It does not need to be always on: messages
    queue on Signal's servers and arrive when it next connects.
  - Or build libsignal for $ARCH yourself from https://github.com/signalapp/libsignal and
    point signal-cli at it. That works, and it is yours to maintain across every upgrade.
    See https://github.com/AsamK/signal-cli/wiki/Provide-native-lib-for-libsignal"
    ;;
esac

[ "$(id -u)" -eq 0 ] || die "Run this with sudo: it installs into /opt, /usr/local/bin and /etc/systemd."

# --- everything this run will need, checked together ------------------------------------
#
# One list, before any work happens. Discovered one at a time these arrive at the worst
# moments -- qrencode, in particular, was only missed after signal-cli had been installed
# and the user had already said yes to linking.
missing=""
for t in curl sha256sum; do have "$t" || missing="$missing $t"; done
have java || missing="$missing default-jre-headless(java)"
# Only needed to link here, which is the usual path, so it is asked for now rather than
# halfway through. Go is deliberately absent: without it the bridge is downloaded instead.
have qrencode || missing="$missing qrencode"
if [ -n "$missing" ]; then
  pkgs=$(printf '%s' "$missing" | sed 's/default-jre-headless(java)/openjdk-21-jre-headless/')
  die "This needs a few things that are not installed:$missing

On Debian or Ubuntu:

    sudo apt install$pkgs

java must be 21 or newer. qrencode is only used to draw the QR code when you link this
computer to your Signal account here; if you link by hand elsewhere you can skip it."
fi

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

  if [ "$SIGNAL_CLI_VERSION" = "$SIGNAL_CLI_SHA256_FOR" ]; then
    have sha256sum || die "sha256sum is needed to verify the download."
    got=$(sha256sum "$tmp/signal-cli.tar.gz" | cut -d" " -f1)
    if [ "$got" != "$SIGNAL_CLI_SHA256" ]; then
      die "The signal-cli download does not match the checksum this script expects.
  expected $SIGNAL_CLI_SHA256
  got      $got
Nothing has been installed. Either the download was corrupted, or it is not the file this
script was written against -- and it would have been run as root, so it stops here."
    fi
    echo "  checksum verified"
  else
    echo "  !! SIGNAL_CLI_VERSION is $SIGNAL_CLI_VERSION, not the pinned $SIGNAL_CLI_SHA256_FOR."
    echo "     This download is NOT being verified. Check the .asc signature beside the"
    echo "     release yourself before trusting it: it becomes a root service."
  fi

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
  # No Go, no binary beside the script: fetch the one the release publishes. Go used to be a
  # hard prerequisite for something the project builds on every release anyway.
  #
  # The checksum is published beside the binary rather than pinned in here, because a hash
  # written into this file cannot know about a release made after it. That proves the bytes
  # arrived intact, not that they are the bytes you would get by compiling -- which is why
  # building from source is still preferred, and still what happens whenever Go is present.
  have curl || die "curl is needed to download the bridge, and Go is not installed to build it."
  url="https://github.com/wanderwildwood/kotozute/releases/latest/download/kotozute-bridge-linux-amd64"
  echo "  Go is not installed; downloading the published bridge instead"
  tmpb=$(mktemp -d); trap 'rm -rf "$tmpb"' RETURN 2>/dev/null || true
  curl -fsSL "$url" -o "$tmpb/kotozute-bridge" \
    || die "Could not download $url
Install Go and run this again to build it from the source you already have, or build it
elsewhere with CGO_ENABLED=0 go build -o kotozute-bridge . and put the result beside this
script."
  if curl -fsSL "$url.sha256" -o "$tmpb/sum" 2>/dev/null && have sha256sum; then
    want=$(awk '{print $1}' "$tmpb/sum")
    got=$(sha256sum "$tmpb/kotozute-bridge" | awk '{print $1}')
    [ "$want" = "$got" ] || die "The downloaded bridge does not match its published checksum.
  expected $want
  got      $got
Nothing has been installed."
    echo "  checksum verified"
  else
    echo "  WARNING: could not check the download against a published checksum"
  fi
  install -m0755 "$tmpb/kotozute-bridge" "$BRIDGE_BIN"
  rm -rf "$tmpb"
fi
mkdir -p "$BRIDGE_DATA"; chmod 700 "$BRIDGE_DATA"
echo "  installed to $BRIDGE_BIN"

# --- the account ---------------------------------------------------------------------------
#
# Linking and registering are not interchangeable and this script will not choose. The
# consequences are PRINTED to the user below, not left in a comment here: an earlier version
# put the warning in comments and then told the reader to "read the warning above first",
# pointing at something they could never see.
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
  # Linking happens here rather than being described and deferred. This used to end the run:
  # you were told to link, you linked in another terminal, you ran the script again. Three
  # invocations to install one thing. Now it offers, does it, and carries straight on.
  #
  # LINK=1 skips the question, for anyone scripting this.
  do_link=""
  if [ -n "${LINK:-}" ]; then
    do_link=1
  elif [ -t 0 ]; then
    say "No Signal account on this machine yet"
    cat <<'EOF'
  The usual choice is to LINK: this computer becomes an extra device on the Signal account
  your phone already has, exactly like Signal Desktop. Nothing about your existing Signal
  changes, and you can unlink it later from the phone.

  The other way is to REGISTER, which makes this computer BECOME the account -- Signal's own
  app stops working for that number and there is no phone app afterwards. That is not
  something to pick by accident, so this only offers to link.

EOF
    printf '  Link this computer to your Signal account now? [y/N] '
    read -r reply </dev/tty || reply=""
    case "$reply" in [yY]*) do_link=1 ;; esac
  fi

  if [ -n "$do_link" ]; then
    have qrencode || die "Linking needs qrencode to draw the QR code for your phone to scan.
  On Debian or Ubuntu: apt install qrencode. Then run this again."
    say "Linking this computer to your Signal account"
    cat <<'EOF'
  A QR code will appear below. On your phone: Signal -> Settings -> Linked devices -> +,
  and scan it. The code expires after a few minutes; if that happens, run this again.

EOF
    # signal-cli prints the URI and then blocks until the scan completes, so the URI has to be
    # picked out of the stream as it goes rather than waited for. PIPESTATUS, because the exit
    # status that matters belongs to signal-cli and not to the loop reading its output.
    set +e
    "$SIGNAL_CLI" --config "$SIGNAL_CLI_DATA" link -n "kotozute" 2>&1 | while IFS= read -r line; do
      case "$line" in
        sgnl://*|tsdevice:*) printf '\n'; qrencode -t utf8 "$line"; printf '\n  Waiting for the scan...\n' ;;
        *) printf '  %s\n' "$line" ;;
      esac
    done
    rc=${PIPESTATUS[0]}
    set -e
    [ "$rc" -eq 0 ] || die "Linking did not complete. Nothing else has been changed; run this again to retry."

    ACCOUNT=$(grep -oE '"number"[[:space:]]*:[[:space:]]*"\+[0-9]+"' \
                "$SIGNAL_CLI_DATA/data/accounts.json" 2>/dev/null \
              | grep -oE '\+[0-9]+' | head -1 || true)
    [ -n "$ACCOUNT" ] || die "Linking reported success but no account appeared in $SIGNAL_CLI_DATA.
  Run this again; if it persists, link by hand and re-run."
    say "Linked as $ACCOUNT -- carrying on"
  fi
fi

if [ -z "$ACCOUNT" ]; then

  say "Nothing installed yet -- the Signal account comes first"
  cat <<EOF
  Set the account up, then run this script again and it will finish in one go.

  There are two ways in and they are NOT interchangeable. Read both before choosing.

  ---------------------------------------------------------------------------
  LINK -- the usual choice. This computer becomes an additional device on a
  Signal account your phone already holds. Nothing about your existing Signal
  changes. If that phone ever stops being registered, this stops working too.

  Run this script again with LINK=1 and it does the whole thing: starts the
  link, draws the QR in this terminal, and waits while you scan it with Signal
  on your phone (Settings -> Linked devices -> +).

      sudo LINK=1 $0

  By hand instead, if you would rather see each part:

      $SIGNAL_CLI --config $SIGNAL_CLI_DATA link -n "kotozute"

  and turn the sgnl:// URI it prints into a QR to scan:

      qrencode -t utf8 '<the sgnl:// URI it printed>'
  ---------------------------------------------------------------------------
  REGISTER -- this computer BECOMES the account. Understand what that means:

    * Signal's own app STOPS WORKING for that number, immediately, on
      verification.
    * Signal's Android app cannot be a linked secondary device, so afterwards
      there is NO Signal app on any phone for that number.
    * Everyone you talk to sees a "safety number changed" warning.
    * Nothing carries over from Signal's servers: threads start empty.
    * $SIGNAL_CLI_DATA becomes the account itself. Lose that directory and the
      account goes with it, recoverable only by re-registering, which needs the
      registration-lock PIN if one is set.

  Only if you mean all of that.

  Get a captcha token first -- register will not run without one. Open

      https://signalcaptchas.org/registration/generate.html

  solve it, and copy the link the page hands back. It begins
  signalcaptcha:// and is long. The token is everything after that prefix,
  and it expires in a few minutes, so have it ready before you run:

      $SIGNAL_CLI --config $SIGNAL_CLI_DATA -a +15551234567 \\
          register --captcha '<the whole signalcaptcha:// link>'

  Signal then sends a six-digit code to that number, by SMS. Enter it:

      $SIGNAL_CLI --config $SIGNAL_CLI_DATA -a +15551234567 verify 123456

  If the code does not arrive, --voice on the register command asks for a
  phone call instead. Do not run register again to retry: a second register
  invalidates the session the first one opened, so a code already on its way
  stops working and you wait for a new one.

  Both commands need root, because $SIGNAL_CLI_DATA is mode 700 and
  signal-cli is not on PATH -- run them with sudo, exactly as printed.
  ---------------------------------------------------------------------------
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
# enable --now starts a stopped service and does nothing at all to a running one. On a
# re-run -- which is how an upgrade happens -- that left the OLD binary running under the
# NEW unit file and reported success. Restart explicitly.
systemctl enable signal-cli >/dev/null 2>&1
systemctl restart signal-cli
echo "  signal-cli: $(systemctl is-active signal-cli)"
sleep 8
systemctl enable kotozute-bridge >/dev/null 2>&1
systemctl restart kotozute-bridge
sleep 4
echo "  kotozute-bridge: $(systemctl is-active kotozute-bridge)"

# The binary that is now running, not the one that was installed. A restart that silently
# failed would otherwise read as an upgrade.
running=$(systemctl show -p ExecMainStartTimestamp --value kotozute-bridge 2>/dev/null)
echo "  bridge started: ${running:-unknown}"

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
