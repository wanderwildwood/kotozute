package com.wanderwildwood.kotozute.feature.settings

/**
 * Whether Settings shows "Paired browsers" and "Forget all browsers". Both follow one rule: shown
 * while Desktop Sync is on, and also while it is off as long as a browser is still paired, so a
 * browser can be forgotten without turning the server on. Off with nothing paired, there is
 * nothing to list or forget, and the pairing that would add one needs the server on.
 */
internal fun desktopSyncBrowserRowsShown(enabled: Boolean, paired: Int): Boolean = enabled || paired > 0
