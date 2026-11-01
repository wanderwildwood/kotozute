package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.einkDialog
import com.wanderwildwood.kotozute.repository.AddDeviceResult
import com.wanderwildwood.kotozute.repository.SignalDevice
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The other devices on this Signal account, for a phone that is the account's primary:
 * add one by scanning the QR code Signal Desktop shows, or unlink one.
 *
 * Upstream's Linked devices screen, kept small. Only a primary may add or remove devices, so
 * a phone that is itself linked never reaches this: the Settings row is hidden for it, and
 * the repository refuses again underneath.
 */
class SignalDevicesActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var add: Button
    private var busy: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        title = getString(R.string.signal_devices_title)
        showBackButton(true)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        TextView(this).apply {
            setText(R.string.signal_devices_intro)
            column.addView(this)
        }
        add = Button(this).apply {
            setText(R.string.signal_devices_add)
            setOnClickListener { scan() }
            column.addView(this)
        }
        status = TextView(this).apply { column.addView(this) }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            column.addView(this)
        }
        setContentView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun scan() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(getString(R.string.signal_devices_scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val scanned = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanned == null) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        // A cancelled scan says nothing; there is nothing to report.
        val text = scanned.contents ?: return
        add.isEnabled = false
        status.setText(R.string.signal_devices_adding)
        busy = scope.launch {
            val result = signalRepo.addLinkedDevice(text)
            add.isEnabled = true
            status.text = getString(
                when (result) {
                    AddDeviceResult.Linked -> R.string.signal_devices_added
                    AddDeviceResult.BadCode -> R.string.signal_devices_bad_code
                    AddDeviceResult.LimitReached -> R.string.signal_devices_limit
                    AddDeviceResult.NotPrimary -> R.string.signal_devices_not_primary
                    AddDeviceResult.Failed -> R.string.signal_devices_failed
                }
            )
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val devices = signalRepo.linkedDevices()
            list.removeAllViews()
            if (devices == null) {
                list.addView(TextView(this@SignalDevicesActivity).apply { setText(R.string.signal_devices_unread) })
                return@launch
            }
            if (devices.isEmpty()) {
                list.addView(TextView(this@SignalDevicesActivity).apply { setText(R.string.signal_devices_none) })
                return@launch
            }
            devices.forEach { d -> list.addView(row(d)) }
        }
    }

    private fun row(d: SignalDevice): View {
        val name = d.name.ifBlank { getString(R.string.signal_account_device_unnamed) }
        val seen = android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(d.lastSeen))
        return Button(this).apply {
            text = getString(R.string.signal_devices_row, name, seen)
            setOnClickListener { confirmRemove(d, name) }
        }
    }

    private fun confirmRemove(d: SignalDevice, name: String) {
        einkDialog()
            .setTitle(getString(R.string.signal_devices_remove_title, name))
            .setMessage(R.string.signal_devices_remove_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.signal_devices_remove) { _, _ ->
                scope.launch {
                    status.setText(
                        if (signalRepo.removeLinkedDevice(d.id)) R.string.signal_devices_removed
                        else R.string.signal_devices_remove_failed
                    )
                    refresh()
                }
            }
            .show()
    }

    companion object {
        fun intent(context: Context) = Intent(context, SignalDevicesActivity::class.java)
    }
}
