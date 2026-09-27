package com.wanderwildwood.kotozute.feature.signalcall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.databinding.SignalCallActivityBinding
import com.wanderwildwood.kotozute.repository.SignalCallState
import com.wanderwildwood.kotozute.repository.SignalCallState.EndReason
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import javax.inject.Inject

/**
 * The call screen. Comes up over the lock screen when a Signal call rings, as a phone call does,
 * and stays while the call is up.
 */
class SignalCallActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository

    private lateinit var binding: SignalCallActivityBinding
    private var watching: Disposable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var shown: SignalCallState = SignalCallState.Idle
    private var answerAsked = false

    /** Whether this screen has seen a call yet. */
    private var seenCall = false

    /** Whether the call on screen was placed from here, for how its ending is worded. */
    private var placing = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) answer() else {
            // Nothing can be said without the microphone. Declining says so to the caller,
            // which is better than a call that connects to silence.
            binding.status.setText(R.string.signal_call_needs_microphone)
            signalRepo.calls().decline()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = SignalCallActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.answer.setOnClickListener { askToAnswer() }
        binding.decline.setOnClickListener { signalRepo.calls().decline() }
        binding.hangUp.setOnClickListener { signalRepo.calls().hangUp() }
        binding.mute.setOnClickListener {
            (shown as? SignalCallState.Connected)?.let { signalRepo.calls().setMuted(!it.muted) }
        }
        binding.speaker.setOnClickListener {
            (shown as? SignalCallState.Connected)?.let { signalRepo.calls().setSpeaker(!it.speaker) }
        }
        handleAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    /** The notification's Answer comes here, so the answer is given with the screen in front. */
    private fun handleAction(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) askToAnswer()
    }

    override fun onStart() {
        super.onStart()
        watching = signalRepo.calls().state()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { render(it) }
    }

    override fun onStop() {
        watching?.dispose()
        watching = null
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun askToAnswer() {
        if (answerAsked) return
        if (signalRepo.calls().current() !is SignalCallState.Ringing) return
        answerAsked = true
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) answer() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun answer() {
        // The call service asks for the microphone while this screen is in front, which is the
        // only moment Android lets it keep the microphone once the screen goes off.
        SignalCallService.start(this, SignalCallService.ACTION_MICROPHONE)
        signalRepo.calls().accept()
    }

    private fun render(state: SignalCallState) {
        // The call being cleared away just after it ended: let "Call ended" stay up for its
        // moment rather than closing on it.
        if (state is SignalCallState.Idle && shown is SignalCallState.Ended) return
        shown = state
        handler.removeCallbacksAndMessages(null)
        when (state) {
            is SignalCallState.Ringing -> {
                placing = false
                binding.name.text = SignalCallService.nameOf(this, state.peer)
                binding.status.setText(R.string.signal_call_incoming)
                binding.ringing.visibility = View.VISIBLE
                binding.inCall.visibility = View.GONE
            }
            is SignalCallState.Calling -> {
                placing = true
                binding.name.text = SignalCallService.nameOf(this, state.peer)
                binding.status.setText(if (state.ringing) R.string.signal_call_remote_ringing else R.string.signal_call_calling)
                binding.ringing.visibility = View.GONE
                binding.inCall.visibility = View.VISIBLE
                // Nothing to mute or move until somebody answers.
                binding.inCallControls.visibility = View.GONE
            }
            is SignalCallState.Connecting -> {
                binding.name.text = SignalCallService.nameOf(this, state.peer)
                binding.status.setText(R.string.signal_call_connecting)
                binding.ringing.visibility = View.GONE
                binding.inCall.visibility = View.VISIBLE
                binding.inCallControls.visibility = View.GONE
            }
            is SignalCallState.Connected -> {
                binding.name.text = SignalCallService.nameOf(this, state.peer)
                binding.ringing.visibility = View.GONE
                binding.inCall.visibility = View.VISIBLE
                binding.inCallControls.visibility = View.VISIBLE
                binding.mute.setText(if (state.muted) R.string.signal_call_unmute else R.string.signal_call_mute)
                binding.speaker.setText(if (state.speaker) R.string.signal_call_earpiece else R.string.signal_call_speaker)
                tick(state)
            }
            is SignalCallState.Ended -> {
                answerAsked = false
                binding.ringing.visibility = View.GONE
                binding.inCall.visibility = View.GONE
                binding.status.setText(endedText(state.why))
                handler.postDelayed({ finishAndRemoveTask() }, ENDED_SHOWN_MS)
            }
            SignalCallState.Idle -> when {
                isFinishing -> {}
                // Opened for a call being placed a moment before the engine says so. Wait for it,
                // briefly, rather than closing on the caller.
                !seenCall -> handler.postDelayed({ if (!seenCall) finishAndRemoveTask() }, WAIT_FOR_CALL_MS)
                else -> finishAndRemoveTask()
            }
        }
        if (state !is SignalCallState.Idle) seenCall = true
    }

    /** The call's length, or that the connection is being found again. */
    private fun tick(state: SignalCallState.Connected) {
        if (state.reconnecting) {
            binding.status.setText(R.string.signal_call_reconnecting)
            return
        }
        val seconds = (System.currentTimeMillis() - state.since) / 1000
        binding.status.text = String.format("%d:%02d", seconds / 60, seconds % 60)
        handler.postDelayed({ tick(state) }, 1000)
    }

    private fun endedText(why: EndReason): Int = when (why) {
        EndReason.DECLINED -> R.string.signal_call_ended_declined
        EndReason.ANSWERED_ELSEWHERE -> R.string.signal_call_ended_answered_elsewhere
        EndReason.DECLINED_ELSEWHERE -> R.string.signal_call_ended_declined_elsewhere
        EndReason.BUSY -> R.string.signal_call_ended_busy
        EndReason.FAILED -> R.string.signal_call_ended_failed
        EndReason.TIMED_OUT -> if (placing) R.string.signal_call_ended_no_answer else R.string.signal_call_ended
        EndReason.THEY_HUNG_UP, EndReason.HUNG_UP -> R.string.signal_call_ended
    }

    companion object {
        const val ACTION_ANSWER = "com.wanderwildwood.kotozute.signalcall.ANSWER"
        private const val ENDED_SHOWN_MS = 2000L
        private const val WAIT_FOR_CALL_MS = 3000L

        fun intent(context: Context): Intent =
            Intent(context, SignalCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
