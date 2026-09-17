package com.wanderwildwood.kotozute.repository

/**
 * A copy could not be written because the account has not sent the key one is locked with.
 *
 * ⚠ **This is not a folder problem, and it was reported as one.** Every failure of the export
 * collapsed into "Choose a folder this phone can write to", so the one failure that has nothing
 * to do with the folder sent people into the file picker to fix something that was never broken.
 * A message that names the wrong cause is worse than a vague one: it is confidently actionable
 * in the wrong direction.
 *
 * The key is derived from the account entropy pool, which a linked device only gets by asking
 * the primary for it. [asked] says whether that request went out just now, because the two
 * situations need different sentences: one is "wait a moment and try again", the other is "your
 * Signal did not answer".
 */
class AccountKeyNotSent(val asked: Boolean) : Exception(
    if (asked) "the account has not sent its keys yet; asked for them just now"
    else "the account has not sent its keys yet, and the request did not go out"
)
