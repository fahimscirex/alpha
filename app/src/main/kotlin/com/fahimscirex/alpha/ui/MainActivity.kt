package com.fahimscirex.alpha.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.fahimscirex.alpha.data.AppDb
import com.fahimscirex.alpha.data.MoneyDao
import com.fahimscirex.alpha.data.TxnRow
import com.fahimscirex.alpha.sms.InboxScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val smsPermissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { granted -> granted }) scanInbox()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDb.get(this).dao()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MonthScreen(dao) }
            }
        }
        if (smsPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) scanInbox()
        else askPermissions.launch(smsPermissions)
    }

    private fun scanInbox() {
        lifecycleScope.launch(Dispatchers.IO) { InboxScan.run(applicationContext) }
    }
}

private val dayFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

/** Shortcut: shows minor units as major with 2 decimals; real formatting comes with multi-currency. */
private fun money(minor: Long, currency: String) =
    String.format(Locale.US, "%s %,.2f", if (currency == "BDT") "৳" else currency, minor / 100.0)

@Composable
private fun MonthScreen(dao: MoneyDao) {
    val (from, to) = remember { currentMonth() }
    val rows by dao.txns(from, to).collectAsStateWithLifecycle(emptyList())
    val spent by dao.spent(from, to).collectAsStateWithLifecycle(0L)
    val unparsed by dao.unparsedCount().collectAsStateWithLifecycle(0)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Spent this month", style = MaterialTheme.typography.labelLarge)
        Text(money(spent, "BDT"), style = MaterialTheme.typography.headlineMedium)
        if (unparsed > 0) Text("$unparsed SMS could not be read", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.padding(top = 16.dp)) {
            items(rows, key = { it.id }) { TxnItem(it); HorizontalDivider() }
        }
    }
}

@Composable
private fun TxnItem(t: TxnRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(t.merchant ?: t.provider, style = MaterialTheme.typography.bodyLarge)
            val account = if (t.number.isEmpty()) t.provider else "${t.provider} ··${t.number}"
            Text("$account · ${dayFormat.format(Date(t.timestamp))}", style = MaterialTheme.typography.bodySmall)
        }
        Text(money(t.amount, t.currency), style = MaterialTheme.typography.bodyLarge,
            color = if (t.amount < 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
    }
}

private fun currentMonth(): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to c.timeInMillis
}
