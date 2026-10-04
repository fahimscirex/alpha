package com.fahimscirex.alpha.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.fahimscirex.alpha.data.Account
import com.fahimscirex.alpha.data.AppDb
import com.fahimscirex.alpha.data.MoneyDao
import com.fahimscirex.alpha.data.TxnRow
import com.fahimscirex.alpha.sms.InboxScan
import com.fahimscirex.alpha.sms.Ingest
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
                Surface(Modifier.fillMaxSize()) {
                    var showAccounts by rememberSaveable { mutableStateOf(false) }
                    if (showAccounts) {
                        BackHandler { showAccounts = false }
                        AccountsScreen(dao)
                    } else {
                        MonthScreen(dao, onAccounts = { showAccounts = true })
                    }
                }
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
private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

/** Shortcut: shows minor units as major with 2 decimals; real formatting comes with multi-currency. */
private fun money(minor: Long, currency: String) =
    String.format(Locale.US, "%s %,.2f", if (currency == "BDT") "৳" else currency, minor / 100.0)

@Composable
private fun MonthScreen(dao: MoneyDao, onAccounts: () -> Unit) {
    // 0 = this month, -1 = last month, ...
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val (from, to) = remember(offset) { month(offset) }
    val rows by remember(from) { dao.txns(from, to) }.collectAsStateWithLifecycle(emptyList())
    val spent by remember(from) { dao.spent(from, to) }.collectAsStateWithLifecycle(0L)
    val unparsed by dao.unparsedCount().collectAsStateWithLifecycle(0)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { offset-- }) { Text("‹") }
            Text(monthFormat.format(Date(from)), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("›") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAccounts) { Text("Accounts") }
        }
        Text("Spent", style = MaterialTheme.typography.labelLarge)
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
            val account = label(t.provider, t.number)
            val transfer = when {
                t.transferOf == null -> ""
                t.reversed -> "Reversed · "
                else -> "Transfer · "
            }
            Text("$transfer$account · ${dayFormat.format(Date(t.timestamp))}", style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(t.amount, t.currency), style = MaterialTheme.typography.bodyLarge,
                color = if (t.amount < 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
            t.bdtAmount?.let { Text("≈ ${money(it, "BDT")}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun label(provider: String, number: String) = if (number.isEmpty()) provider else "$provider ··$number"

@Composable
private fun AccountsScreen(dao: MoneyDao) {
    val accounts by dao.visibleAccounts().collectAsStateWithLifecycle(emptyList())
    var merging by remember { mutableStateOf<Account?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Accounts", style = MaterialTheme.typography.headlineSmall)
        Text("Tap an account to merge it into another, e.g. a debit card into its bank account.",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.padding(top = 16.dp)) {
            items(accounts, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { merging = a }.padding(vertical = 12.dp)) {
                    Text(label(a.provider, a.number), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    a.balance?.let { Text(money(it, a.currency), style = MaterialTheme.typography.bodyLarge) }
                }
                HorizontalDivider()
            }
        }
    }

    merging?.let { from ->
        val targets = accounts.filter { it.id != from.id && it.currency == from.currency }
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Merge ${label(from.provider, from.number)} into") },
            text = {
                if (targets.isEmpty()) Text("No other ${from.currency} account to merge into.")
                else Column {
                    targets.forEach { into ->
                        Text(label(into.provider, into.number), Modifier.fillMaxWidth().clickable {
                            merging = null
                            scope.launch(Dispatchers.IO) { Ingest.merge(dao, from, into) }
                        }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } },
        )
    }
}

/** Start and end of the month [offset] months from now, in local time. */
private fun month(offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.MONTH, offset)
    }
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to c.timeInMillis
}
