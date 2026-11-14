// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.simoneluconi.gccommon.R as C
import com.simoneluconi.gcgallery.R
import com.simoneluconi.gcgallery.transfer.Transfer
import com.simoneluconi.gcgallery.transfer.TransferAuth
import java.util.concurrent.Executors

/** Wi-Fi transfer: the address to open on the computer, the optional password, and the switch. */
class TransferActivity : Activity() {

    private lateinit var card: View
    private lateinit var status: TextView
    private lateinit var url: TextView
    private lateinit var ip: TextView
    private lateinit var hint: TextView
    private lateinit var passwordState: TextView
    private lateinit var password: TextView
    private lateinit var passwordRemove: TextView
    private lateinit var toggle: TextView
    private lateinit var auth: TransferAuth
    /** PBKDF2 off the main thread. */
    private val hasher = Executors.newSingleThreadExecutor { r -> Thread(r, "transfer-password") }

    private val onChange: () -> Unit = { show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer)
        auth = TransferAuth(this)
        card = findViewById(R.id.card)
        status = findViewById(R.id.status)
        url = findViewById(R.id.url)
        ip = findViewById(R.id.ip)
        hint = findViewById(R.id.hint)
        passwordState = findViewById(R.id.password_state)
        password = findViewById(R.id.password)
        passwordRemove = findViewById(R.id.password_remove)
        toggle = findViewById(R.id.toggle)
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        toggle.setOnClickListener {
            if (Transfer.state.phase == Transfer.Phase.OFF) start() else Transfer.stop(this)
        }
        password.setOnClickListener { askPassword() }
        passwordRemove.setOnClickListener { savePassword(null) }
        fitCard()
    }

    override fun onStart() {
        super.onStart()
        Transfer.addListener(onChange)
        show()
    }

    override fun onStop() {
        Transfer.removeListener(onChange)
        super.onStop()
    }

    override fun onDestroy() {
        // A password being hashed still gets saved
        hasher.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fitCard()
    }

    /** A card of at most 460 dp, centred, held level or vertically. */
    private fun fitCard() {
        val lp = card.layoutParams as FrameLayout.LayoutParams
        lp.width = minOf(dp(460), resources.displayMetrics.widthPixels - dp(32))
        card.layoutParams = lp
    }

    /**
     * No permission prompt: POST_NOTIFICATIONS comes with the ROM's default grants for this
     * preinstalled app. Without it the transfer still runs; only its notification is hidden.
     */
    private fun start() = Transfer.start(this)

    /** No old password asked: whoever holds the camera is its owner. */
    private fun askPassword() {
        val input = EditText(this).apply {
            // Single line first: setSingleLine replaces the password mask
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.transfer_new_password)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.transfer_set_password)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.transfer_save) { _, _ -> input.text.toString().takeIf { it.isNotEmpty() }?.let { savePassword(it) } }
            .show()
        input.requestFocus()
    }

    private fun savePassword(value: String?) {
        password.isEnabled = false
        passwordRemove.isEnabled = false
        hasher.execute {
            auth.setPassword(value)
            runOnUiThread { if (!isDestroyed) show() }
        }
    }

    private fun show() {
        val s = Transfer.state
        val on = s.phase == Transfer.Phase.ON
        status.setText(
            when (s.phase) {
                Transfer.Phase.OFF -> R.string.transfer_off
                Transfer.Phase.STARTING -> R.string.transfer_starting
                Transfer.Phase.ON -> R.string.transfer_on
            },
        )
        status.setTextColor(getColor(if (on) C.color.ok else C.color.on_surface_muted))
        url.text = if (on) s.url else getString(R.string.transfer_title)
        ip.text = if (on) getString(R.string.transfer_or_ip, s.ipUrl) else ""
        ip.visibility = if (on) View.VISIBLE else View.GONE
        hint.text = when {
            on && s.files > 0 -> getString(R.string.transfer_sent, s.files, Formatter.formatShortFileSize(this, s.bytes))
            on -> getString(R.string.transfer_hint)
            s.reason != 0 -> getString(s.reason)
            else -> getString(R.string.transfer_about)
        }
        val locked = auth.hasPassword
        passwordState.setText(if (locked) R.string.transfer_password_on else R.string.transfer_password_off)
        password.setText(if (locked) R.string.transfer_change_password else R.string.transfer_set_password)
        passwordRemove.visibility = if (locked) View.VISIBLE else View.GONE
        password.isEnabled = true
        passwordRemove.isEnabled = true
        toggle.setText(if (s.phase == Transfer.Phase.OFF) R.string.transfer_start else R.string.transfer_stop)
        toggle.isEnabled = s.phase != Transfer.Phase.STARTING
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
