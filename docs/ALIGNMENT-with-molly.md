# Staying in line with Molly, and the line not to cross

Molly is the finished version of what this branch is doing: a Signal client on an Android
phone, linked as a secondary device by scanning a QR, no computer involved. It already runs on
a Kompakt. So it is the right thing to steer by — with one boundary that has to be deliberate
rather than discovered in review.

## Follow Molly's architecture

Checked rather than assumed, and the alignment is already good:

| decision | Molly | this branch |
|---|---|---|
| Protocol store engine | **SQLCipher** (`SqlCipherLibraryLoader`, `SqlCipherErrorHandler`) | SQLCipher — decided independently in `DECISION-protocol-store.md`, same answer |
| Android lifecycle | foreground service, and UnifiedPush via MollySocket as the alternative | foreground service today; UnifiedPush noted as an option, not taken |
| At-rest protection | encrypted database, keystore-held key | same pattern, already shipped for the message store in v1.11.2 |

Where a question comes up that signal-cli answers badly because it is a headless JVM program —
Doze, foreground services, process death, battery — **Molly is the better reference**, because
it solved the same problem on the same platform.

## Do not copy Molly's code

**Molly and Signal-Android are AGPL-3.0. signal-cli and Turasa's libsignal-service-java are
GPL-3.0. kotozute is GPL-3.0-only, and it serves a web page over the network.**

Copying from signal-cli is a licence-compatible port between GPL-3.0 projects, and that is what
`SignalNetworkConfig.kt` is — marked as such in the file.

Copying from Molly or Signal-Android is *permitted* — AGPL §13 explicitly allows the
combination and the GPL part stays GPL — but it drags §13's network clause onto the result, and
Desktop Sync is exactly the network interaction that clause is written about. It would mean the
app must offer its corresponding source to everyone who opens that page. That is a footer link
and a README paragraph, not a catastrophe, **but it is a change to what the licence asks of
everyone who runs the app, and it should be a decision made on purpose rather than the
consequence of a convenient copy-paste.**

### It was raised, and the answer turns out not to cost anything

There is an open offer to treat Molly as a true upstream and accept what that implies. Worth checking
what it would actually buy before spending a licence obligation on it — and the answer is: much
less than it sounds.

Molly's `SignalIdentityKeyStore` is one file, and it imports `IdentityTable.VerifiedStatus`,
`IdentityRecordList`, `IdentityRecord`, `Recipient` and `RecipientId`. It is not a standalone
protocol store; it is a leaf of Signal-Android's recipient and database subsystem. Lifting it
means lifting that subsystem, which is most of the app. The same is true of the rest of its
Signal implementation: **Molly's code assumes Signal-Android's whole world.**

signal-cli's stores are the opposite shape — self-contained SQL over a small schema, 100–350
lines each, with CREATE statements that copy over nearly verbatim. They are the ones that fit.

So the boundary costs nothing, because the code on the far side of it is not the code we would
want. The rule stands, not as a sacrifice but as the same conclusion arrived at twice:

- **Read Molly freely, and steer by it** on anything Android-shaped — Doze, foreground
  services, process death, battery, SQLCipher. It solved these on this platform.
- **Port from signal-cli and libsignal-service-java.** Both GPL-3.0, both the reference
  implementations of a *linked device*, and both structured to be lifted.
- **Take no source from Molly or Signal-Android.** Not because it is forbidden — AGPL §13
  permits the combination — but because it would pull the network-source obligation onto
  Desktop Sync in exchange for code that does not fit this app anyway. If a specific piece ever
  does look worth it, that is a decision to make on its own merits, and the price is a source
  link on the relay page plus the licence notices, not a rewrite.

## One dependency divergence worth knowing

Molly does not use `org.signal:libsignal-client`. It uses **`im.molly:libsignal-client`**, its
own fork, currently 0.96.3-1. This branch uses Signal's own `org.signal:libsignal-android`
0.102.0, which is both newer and the artifact signal-cli builds against.

Do not switch to Molly's fork to "match Molly". It is AGPL, it lags, and the whole reason the
port reads from signal-cli is that signal-cli is the GPL-3.0 implementation of the thing being
built here.
