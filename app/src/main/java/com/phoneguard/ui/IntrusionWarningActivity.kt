package com.phoneguard.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.phoneguard.R
import com.phoneguard.antitheft.SecurityService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.phoneguard.util.IntentUtils

@AndroidEntryPoint
class IntrusionWarningActivity : Activity() {
    @Inject
    lateinit var securityService: SecurityService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.intrusion_warning_title)
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.intrusion_warning_message)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(android.graphics.Color.RED)
        }
        val messageView = TextView(this).apply {
            text = message
            textSize = 16f
        }
        val stopButton = Button(this).apply {
            text = getString(R.string.intrusion_stop_alarm)
            setOnClickListener {
                MainScope().launch { securityService.stopAlarm() }
                finish()
            }
        }
        layout.addView(titleView)
        layout.addView(messageView)
        layout.addView(stopButton)
        setContentView(layout)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"

        fun start(context: Context, title: String, message: String) {
            val intent = Intent(context, IntrusionWarningActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            // Явный интент, но активити может быть отключена политикой устройства.
            IntentUtils.open(context, intent)
        }
    }
}
