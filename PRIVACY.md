# Messaging — Privacy Policy

Version 1.35.0. Last reviewed 4 November 2026.

This is the policy for Messaging (kotozute, 言伝), the app published from this repository.

## The short version

The developer collects nothing about you. There is no account, no sign-up, no analytics, no advertising and no crash reporting, and no server belonging to the developer that the app talks to. It is a messaging app, so it necessarily reads and stores your messages and contacts on your phone, and it sends them where you tell it to: texts and picture messages go through your carrier, and Signal messages go to Signal's servers, end-to-end encrypted. Everything else the app contacts is listed below, and each happens because you used the feature.

## Where it comes from

Messaging continues QKSMS (Moez Bhatti) by way of QUIK (Marcos Jones) and InkMessage (lamdanAmiti). The Signal half uses libsignal, RingRTC and Signal's service layer, all by Signal Messenger, LLC, which the source includes or links. It is an unofficial third-party Signal client and has no connection to the Signal Foundation or Signal Messenger.

Where the app processes personal data, that processing happens on your own device, under your control.

## What the app stores, and where

On the phone only:

- **Texts and picture messages** are kept by Android itself, in its own message store, because the app is the phone's default SMS app. The app also keeps a copy of them, with your contacts' names and numbers, in its own database.
- **Signal messages, attachments, groups, contacts and receipts** are kept in the app's own database. Signal's cryptographic keys and sessions are kept in a second, separate database.
- **Settings, scheduled messages, and blocked numbers.**

The app's own database is encrypted on disk with a random key sealed by a key in the phone's keystore, and so is the Signal key database. That protects the files if the storage is lifted from the phone. It does not protect them from code running as this app on an unlocked phone, because the keystore key cannot require your fingerprint or PIN: the app has to open its databases at start-up and in the background, with nobody there to unlock anything.

The manifest sets `allowBackup="false"`, so Android does not copy the app into a device backup or a cloud backup. There is no cloud copy and no copy held by the developer.

**Uninstalling the app deletes its own database, its Signal keys and the Signal account on this phone. It does not delete your texts**, which stay in Android's message store.

Five things leave the app's storage only when you ask:

- **Backup and restore** writes your SMS texts (not MMS, settings or Signal) to a plain, unencrypted file in a place you choose. A restore skips messages already on the phone.
- **Save**, on a picture, video or file in a conversation, writes a copy where you choose, through Android's own save screen.
- **Write a copy out** (Settings, Signal, Chat history) writes your Signal messages to a folder you pick, encrypted. Import reads a Signal Desktop export or such a copy from a folder you pick.
- **Desktop Sync**, below.
- **A crash record.** If the app closes itself unexpectedly, it writes what went wrong to a file in its own private storage, so that you can read it in Settings, clear it there, or choose to send it: the screen shows you the whole text first. Nothing is sent unless you do. A crash record can contain the text of a message, because the failure may have happened while handling one. Separately, a **debug log** is off by default; you turn it on by pressing and holding the version line in About. It records what the app does, **including the text of messages**, in a file in the app's own storage (`Android/data/com.wanderwildwood.kotozute/files/`, not the Downloads folder). It is never sent anywhere; it leaves the phone only if you copy it out yourself, and you turn it off the same way.

## Permissions, and what each is for

From the app's manifests (`presentation/src/main/AndroidManifest.xml` and `data/src/main/AndroidManifest.xml`), each of which is asked for when the matching feature is used:

- `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `RECEIVE_MMS`: receiving, reading and sending texts and picture messages as the default SMS app.
- `READ_CONTACTS`: names and numbers for conversations, and, only if you agree to "Find contacts on Signal", the numbers that are sent to Signal as described below.
- `READ_PHONE_STATE`: to know which SIMs the phone has, so a text goes out on the one you choose.
- `CALL_PHONE`: the call button in a text conversation places a call.
- `RECORD_AUDIO`: voice messages and Signal voice calls. `MODIFY_AUDIO_SETTINGS` and `BLUETOOTH`: routing a call's sound to the earpiece, speaker, wired or Bluetooth headset.
- `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, and `WRITE_EXTERNAL_STORAGE` (capped below the Kompakt's Android version, so never granted on it): attaching and saving files.
- `CAMERA`: declared in the manifest. The app takes no pictures itself: the camera button hands the job to the phone's camera app, and the app generates a QR code for linking Signal but never scans one. The manifest's own comment on this line (that it is for scanning a pairing code) is out of date.
- `INTERNET`: Signal, GIF search and Desktop Sync, all below.
- `ACCESS_NETWORK_STATE`: whether a connection is there.
- `POST_NOTIFICATIONS`: message and call notifications. `USE_FULL_SCREEN_INTENT`: the Signal call screen over the lock screen.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: the services that keep Desktop Sync running, hold the Signal connection open while "Keep Signal connected" is on, keep a call going with the screen off, and play a voice note with the screen locked.
- `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `VIBRATE`: delivering and sending on time, restarting what you had switched on after a restart, scheduled messages, and vibration.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: the one request Signal makes on a phone without push, so Android does not defer the only thing that brings messages in.

The store lists more entries than these because the app is built with ShortcutBadger, a library that puts an unread count on launcher icons and carries the private badge permissions of other makers' launchers (several are named `READ_SETTINGS` and `WRITE_SETTINGS` in those makers' own namespaces). Each is that launcher's badge provider and nothing else, and none of those launchers is on this phone. The last is `com.wanderwildwood.kotozute.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX declares for its own internal receivers; it is signature-level and namespaced to this app, so nothing else can hold it.

## Who else receives anything

Signal and your carrier are independent controllers for what they receive.

**Your carrier**, for texts and picture messages. SMS is not encrypted. A picture message is fetched from, and sent to, your carrier's MMS server.

**Signal's servers**, for Signal messages and calls, once you link or register the phone. Messages are end-to-end encrypted, and use sealed sender where the recipient's profile allows it. The app talks to Signal directly, with nothing in between. Hosts: `chat.signal.org` (messages, account, keys, receipts), `cdn.signal.org`, `cdn2.signal.org` and `cdn3.signal.org` (attachments, encrypted), `storage.signal.org` (your account's encrypted contact and settings records), `svr2.signal.org` (Signal's secure value recovery), `cdsi.signal.org` (contact discovery, below) and `signalcaptchas.org` (the captcha page, shown only when you register a number). Signal sees your IP address, your phone number, who you message and when, as any Signal client does; for what it keeps, see Signal's own policy. Voice calls go through Signal's relay servers rather than between the two phones, so the person you call does not learn your IP address. Pictures sent over Signal are decoded and saved again as JPEG, which drops their EXIF data, including location. Read receipts and typing indicators are Signal account-wide settings, which the app follows and shows in Settings. The app fetches no link previews.

**Contact discovery.** If you answer yes to "Ask Signal about your contacts?" (in setup or in Settings, Find contacts on Signal), the phone numbers in your address book are sent to Signal's lookup service, scrambled so the service cannot read them, to learn which are on Signal. Once you have agreed, a number you start a new message to may be looked up the same way. Nothing is looked up before you agree.

**GIF search.** The Signal GIF picker asks GIPHY, through Signal's content proxy (`contentproxy.signal.org`), so GIPHY sees the proxy and not you, and the proxy sees only that you connected to GIPHY. Only when you open the picker.

**Updates.** The app never looks for updates, downloads one or installs one, and holds no permission to install apps. Messaging is updated through GitHub (with Obtainium, for instance) or through the InkApps store, never by itself.

**Desktop Sync** is off by default. Once you switch it on it stays on, through reboots and app updates, until you switch it off. When on, the app runs a small web server on the phone that serves your messages, texts and Signal, to a browser on your computer that you pair by typing a six-digit code the phone shows; each browser keeps its own secret token, and the phone keeps only a hash of it. By default it refuses anyone not connected through a VPN; if you switch that off it also accepts connections from private networks, never from a public address. The connection is plain http unless you turn on its TLS option, which uses a certificate the phone makes for itself. Nothing is sent to the developer or any third party. Your messages are visible to any paired browser that can reach the phone; Settings lists them, and can forget any one or all of them.

**Other apps on the phone.** Only the numbers of unread texts and Signal messages go to Glance (`com.wanderwildwood.hitome`), if installed and Settings, Unread on the lock screen, is on; never a name or a word of a message. "Send where I am" asks Whereabouts (`com.wanderwildwood.ibasho`), if installed, for one location, which Whereabouts asks you about first; the app then sends a map link (`openstreetmap.org`) and the coordinates in the message itself, and has no location permission of its own. If you use a call-blocking app the manifest names (Call Control, Should I Answer and the like), the app asks it whether a number should be blocked and sends it that number, under that app's own policy. The text-to-speech and speech-recognition engines of the phone are used when you ask a message to be read aloud or dictate a quick reply; what they do is their own business.

In About, "feed the llamas" opens a donation page (square.link) and wanderthe.dev opens the author's website, in your browser and only when you press them. The app does not fetch either page and learns nothing about whether you went.

No analytics, advertising or crash-reporting service is contacted. No data is sold, rented, or shared for advertising. There is no profiling, no personalization, and no automated decision-making about you.

## Legal basis (EU / EEA and UK)

Processing on the device is carried out for your own purposes and at your own direction. Where the app contacts an outside service, that request is made at your request and is necessary to provide the feature you asked for (Article 6(1)(b) GDPR); that operator is an independent controller for what it then does, under its own policy.

## Children

The app is suitable for all ages and does not knowingly collect anything from anyone, of any age, because the developer does not collect anything.

<!-- shared-about privacy section -->
## Who we are

Messaging is made by wander wildwood, an independent developer: one person, not a company. wander wildwood is the data controller for anything the app itself handles, and that is very little. What you put in Messaging stays on your phone unless you send it somewhere yourself. The only things that go out are the ones described above: the texts and picture messages you send, with the numbers they go to, to your carrier; your Signal messages, attachments, receipts and calls, end-to-end encrypted, to Signal's servers, and your contacts' numbers, scrambled, to Signal's lookup only if you agree to Find contacts on Signal; the words of a GIF search, to GIPHY through Signal's content proxy; and, only while Desktop Sync is on, your messages to the browsers you pair. The developer receives nothing, so the developer holds no data about you.

## How to contact us

Write to email@wanderthe.dev about your data, this policy or anything else. The same address is in the app, under About.

## Your rights

If you are in the EU, the EEA or the UK, the GDPR gives you the right to:

- see the personal data held about you (access)
- have it corrected (rectification) or deleted (erasure)
- limit how it is used (restriction)
- object to its use (objection)
- get a copy to take elsewhere (portability)
- withdraw your consent at any time, where something rests on it
- complain to a data protection supervisory authority, for example the one where you live

Similar laws elsewhere, such as the CCPA in California, give you the right to know, delete and correct, and to opt out of the sale or sharing of your data. Nothing from Messaging is sold or shared.

Since Messaging keeps what it stores only on your phone, you can exercise most of these rights yourself: delete messages in the app (your texts also stay in Android's message store until you delete them there), clear the app's data in Android's settings, or uninstall the app, which deletes all of it. For anything you cannot do yourself, write to the address above.

Messaging connects to your carrier, Signal and GIPHY. They are separate from this app and from its developer, and what they receive is covered by their own privacy policies; ask them about what they hold.
<!-- /shared-about privacy section -->

## Changes

If a later version changes what the app stores or which hosts it contacts, this policy is updated in the same release and the change is described in the release notes. The source is public, so any claim here can be checked against the code: <https://github.com/wanderwildwood/kotozute>. GitHub reports its license as AGPL-3.0.
