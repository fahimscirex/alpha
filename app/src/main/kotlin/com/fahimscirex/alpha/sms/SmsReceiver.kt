package com.fahimscirex.alpha.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.fahimscirex.alpha.data.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Woken by the system only when an SMS arrives; parses it and lets the process die. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val first = parts.firstOrNull() ?: return
        // Long SMS arrive as several parts from one sender.
        val sender = first.originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val timestamp = first.timestampMillis

        val pending = goAsync()
        val dao = AppDb.get(context).dao()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Ingest.sms(dao, sender, body, timestamp)
            } finally {
                pending.finish()
            }
        }
    }
}
