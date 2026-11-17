package com.wanderwildwood.kotozute.feature.compose

import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables

/**
 * Whether there is something to send: some text, or something attached.
 *
 * ⚠ Only `canSend` may be set from this. It used to write `scheduled` back into the state as
 * well, copied from the very state it was reading. A value fed back like that is always one
 * step old, and while the schedule changes under it the old and the new value replace each
 * other for ever: editing a scheduled message sets the time as the screen opens, so a stale 0
 * and the real time took turns on the main thread, renders piled up behind them, and the app
 * ran out of memory with the message already taken off the schedule.
 */
internal fun canSendChanges(
    text: Observable<CharSequence>,
    attachmentCounts: Observable<Int>
): Observable<Boolean> =
    Observables.combineLatest(text, attachmentCounts) { body, attachments ->
        body.isNotBlank() || attachments > 0
    }
