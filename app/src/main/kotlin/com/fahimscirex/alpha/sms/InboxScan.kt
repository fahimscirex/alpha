package com.fahimscirex.alpha.sms

import android.content.Context
import android.provider.Telephony
import com.fahimscirex.alpha.data.AppDb

/**
 * Catches up on SMS the receiver missed (app force-stopped, or sent before install).
 * Reads only messages newer than the last scan; the first scan goes back [FIRST_SCAN_DAYS].
 */
object InboxScan {
    private const val FIRST_SCAN_DAYS = 180L
    private const val PREFS = "scan"
    private const val KEY_LAST = "last"

    /**
     * Deletes everything imported from SMS, plus merges, transfer marks and balances, then
     * imports the inbox again. Transactions entered by hand are kept.
     */
    suspend fun reimport(context: Context) {
        val dao = AppDb.get(context).dao()
        Ingest.exclusive {
            dao.deleteSmsTxns()
            dao.clearLinks()
            dao.deleteUnparsedAll()
            dao.deleteUnusedAccounts()
            dao.resetAccounts()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
        run(context)
    }

    suspend fun run(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST, System.currentTimeMillis() - FIRST_SCAN_DAYS * 86_400_000L)
        val dao = AppDb.get(context).dao()
        // Retry earlier failures first: an app update may have taught the parser their format.
        // A row that still fails is re-added by Ingest.
        for (sms in dao.unparsed()) {
            dao.deleteUnparsed(sms.id)
            Ingest.sms(dao, sms.sender, sms.body, sms.timestamp)
        }
        var last = since
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                last = c.getLong(2)
                Ingest.sms(dao, sender, body, last)
            }
        }
        prefs.edit().putLong(KEY_LAST, last).apply()
    }
}
