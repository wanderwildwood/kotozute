package com.wanderwildwood.kotozute.repository

/**
 * A [SendFailure] as kept on a message marked not sent, and back.
 *
 * The kind is stored rather than the sentence, so the reason reads in whatever language the
 * screen is in when it is drawn, not the one it was in when the send failed. One line: the
 * kind's name, then a newline and the one value it carries, if any.
 *
 * Anything that will not decode -- a kind a later build added, then a downgrade -- reads as no
 * reason, and the message still says it was not sent.
 */
object SendFailureCodec {

    private const val SEP = '\n'

    /** The reason for [t], as the send path saw it. */
    fun of(t: Throwable): SendFailure = when (t) {
        is NotSent -> t.failure
        is SendRefused -> t.failure
        is SafetyNumberChanged -> SendFailure.SafetyNumberChanged(t.name)
        else -> SendFailure.Unexplained(t.message ?: t::class.java.simpleName)
    }

    fun encode(f: SendFailure): String = when (f) {
        is SendFailure.ProofRequired -> "ProofRequired$SEP${f.retryAfterSeconds}"
        is SendFailure.RateLimited -> "RateLimited$SEP${f.retryAfterSeconds}"
        SendFailure.Unlinked -> "Unlinked"
        SendFailure.Unregistered -> "Unregistered"
        SendFailure.VersionRefused -> "VersionRefused"
        SendFailure.ServerRejected -> "ServerRejected"
        SendFailure.TheyLeft -> "TheyLeft"
        is SendFailure.Unexplained -> "Unexplained$SEP${f.detail}"
        is SendFailure.SafetyNumberChanged -> named("SafetyNumberChanged", f.name)
        is SendFailure.NotOnSignal -> named("NotOnSignal", f.name)
        SendFailure.Unreachable -> "Unreachable"
        is SendFailure.KeyUnusable -> named("KeyUnusable", f.name)
        is SendFailure.TooFast -> f.waitMillis?.let { "TooFast$SEP$it" } ?: "TooFast"
        SendFailure.ProofNeeded -> "ProofNeeded"
        SendFailure.ServerSilent -> "ServerSilent"
        SendFailure.NoLongerHeld -> "NoLongerHeld"
        SendFailure.NoReachableMembers -> "NoReachableMembers"
        is SendFailure.NobodyReached -> "NobodyReached$SEP${f.count}"
        SendFailure.PrimaryRefusedRequest -> "PrimaryRefusedRequest"
        SendFailure.TooLong -> "TooLong"
        is SendFailure.AttachmentUnprepared -> "AttachmentUnprepared$SEP${f.detail}"
        SendFailure.KeysStale -> "KeysStale"
        SendFailure.NoCertificate -> "NoCertificate"
        SendFailure.NotInGroup -> "NotInGroup"
        SendFailure.GroupEnded -> "GroupEnded"
        SendFailure.GroupUnreachable -> "GroupUnreachable"
        SendFailure.AdminsOnly -> "AdminsOnly"
        SendFailure.NumberOnly -> "NumberOnly"
        SendFailure.NoGroupKey -> "NoGroupKey"
        SendFailure.NotSetUp -> "NotSetUp"
        SendFailure.SignalOff -> "SignalOff"
    }

    /** The reason [stored] names, or null for none, or for one this build does not know. */
    fun decode(stored: String): SendFailure? {
        if (stored.isEmpty()) return null
        val kind = stored.substringBefore(SEP)
        val value: String? = if (SEP in stored) stored.substringAfter(SEP) else null
        return when (kind) {
            "ProofRequired" -> value?.toLongOrNull()?.let { SendFailure.ProofRequired(it) }
            "RateLimited" -> value?.toLongOrNull()?.let { SendFailure.RateLimited(it) }
            "Unlinked" -> SendFailure.Unlinked
            "Unregistered" -> SendFailure.Unregistered
            "VersionRefused" -> SendFailure.VersionRefused
            "ServerRejected" -> SendFailure.ServerRejected
            "TheyLeft" -> SendFailure.TheyLeft
            "Unexplained" -> value?.let { SendFailure.Unexplained(it) }
            "SafetyNumberChanged" -> SendFailure.SafetyNumberChanged(value)
            "NotOnSignal" -> SendFailure.NotOnSignal(value)
            "Unreachable" -> SendFailure.Unreachable
            "KeyUnusable" -> SendFailure.KeyUnusable(value)
            "TooFast" -> SendFailure.TooFast(value?.toLongOrNull())
            "ProofNeeded" -> SendFailure.ProofNeeded
            "ServerSilent" -> SendFailure.ServerSilent
            "NoLongerHeld" -> SendFailure.NoLongerHeld
            "NoReachableMembers" -> SendFailure.NoReachableMembers
            "NobodyReached" -> value?.toIntOrNull()?.let { SendFailure.NobodyReached(it) }
            "PrimaryRefusedRequest" -> SendFailure.PrimaryRefusedRequest
            "TooLong" -> SendFailure.TooLong
            "AttachmentUnprepared" -> value?.let { SendFailure.AttachmentUnprepared(it) }
            "KeysStale" -> SendFailure.KeysStale
            "NoCertificate" -> SendFailure.NoCertificate
            "NotInGroup" -> SendFailure.NotInGroup
            "GroupEnded" -> SendFailure.GroupEnded
            "GroupUnreachable" -> SendFailure.GroupUnreachable
            "AdminsOnly" -> SendFailure.AdminsOnly
            "NumberOnly" -> SendFailure.NumberOnly
            "NoGroupKey" -> SendFailure.NoGroupKey
            "NotSetUp" -> SendFailure.NotSetUp
            "SignalOff" -> SendFailure.SignalOff
            else -> null
        }
    }

    private fun named(kind: String, name: String?) = if (name == null) kind else "$kind$SEP$name"
}
