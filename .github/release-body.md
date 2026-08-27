## Before you install

**Uninstall inkMessage+ first. Everyone. There is no upgrade path from it.**

This app was called inkMessage+ up to v1.0.14. It now has a different application ID, so Android
treats it as unrelated software rather than a newer version — installing over the old one stops
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and if you have both, you have two SMS apps.

Your messages are safe. They live in Android's own message store, not in this app, and it reads
them back the first time it runs — on a long history that takes several minutes, showing
"Syncing messages…" over an empty list until it finishes. What uninstalling clears is the app's
own settings: you will need to make it your default SMS app again, and to turn Desktop Sync back
on if you use it. **Desktop Sync's address changes too**, so re-bookmark it from Settings; the
old link will not work.

If you track this app in Obtainium, point it at this repository — the old one publishes nothing
further.

---
