package com.wanderwildwood.kotozute.repository

/**
 * A copy was not written because the stored attachments are encrypted and the keystore will
 * not release the secret that opens them just now.
 *
 * Written anyway, the copy would hold the messages and none of the pictures, voice notes or
 * files, and the count of attachments it could not include would read as files that are
 * simply gone. The keystore's refusal can pass (a restart often clears it), so the honest
 * answer is to write nothing and say why.
 */
class AttachmentsLockedForExport : Exception(
    "the stored attachments are locked; a copy now would leave every one of them out"
)
