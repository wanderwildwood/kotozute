package com.wanderwildwood.kotozute.repository

/**
 * The message went, and this phone could not write it down.
 *
 * ⚠ **Its whole purpose is to stop somebody sending the same thing twice.** A send here is two
 * operations -- hand it to Signal, then file the row -- and only the first is visible to the
 * person on the other end. If the second throws, every other failure path says "it did not
 * send", the text is still sitting in the composer, and the obvious response is to press send
 * again. The recipient gets it twice and nothing in the app explains why.
 *
 * This is deliberately *not* swallowed. Swallowing it would leave the send reported as a plain
 * success with no row in the conversation -- a silent gap, which is the fault this rail keeps
 * finding rather than one worth adding.
 *
 * Rare, not impossible: the write is a Realm transaction, and a full disk is the ordinary way
 * for one to fail on a phone.
 */
class SentButNotFiled(cause: Throwable) : Exception(cause)
