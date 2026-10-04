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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

// The date picker works in UTC days ("midnight UTC of the chosen date"), the app in local time.
private val utc = java.util.TimeZone.getTimeZone("UTC")
private val pickerDateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply { timeZone = utc }
private fun dayKey(tz: java.util.TimeZone) = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }

/** Today's local date as the picker's UTC-midnight value (BD after midnight is still yesterday in UTC). */
private fun pickerDay(now: Long): Long = dayKey(utc).parse(dayKey(java.util.TimeZone.getDefault()).format(Date(now)))!!.time

/**
 * Timestamp for an entry on the picked day: now when it is today, so it counts as newer than
 * balances stated earlier today; otherwise local noon of that day.
 */
private fun entryTime(picked: Long): Long {
    val now = System.currentTimeMillis()
    if (picked == pickerDay(now)) return now
    return dayKey(java.util.TimeZone.getDefault()).parse(dayKey(utc).format(Date(picked)))!!.time + 12 * 3_600_000L
}
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
    val transferred by remember(from) { dao.transferred(from, to) }.collectAsStateWithLifecycle(0L)
    val unparsed by dao.unparsedCount().collectAsStateWithLifecycle(0)
    var selected by remember { mutableStateOf<TxnRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { offset-- }) { Text("‹") }
            Text(monthFormat.format(Date(from)), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("›") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { adding = true }) { Text("Add") }
            TextButton(onClick = onAccounts) { Text("Accounts") }
        }
        Text("Spent", style = MaterialTheme.typography.labelLarge)
        Text(money(spent, "BDT"), style = MaterialTheme.typography.headlineMedium)
        if (transferred > 0) {
            Text("Moved between your accounts: ${money(transferred, "BDT")}", style = MaterialTheme.typography.bodySmall)
        }
        if (unparsed > 0) Text("$unparsed SMS could not be read", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.padding(top = 16.dp)) {
            items(rows, key = { it.id }) { TxnItem(it, onClick = { selected = it }); HorizontalDivider() }
        }
    }

    // Manual override for movements the automatic matching misses or gets wrong.
    selected?.let { t ->
        val linked = t.transferOf != null
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(t.merchant ?: t.provider) },
            text = {
                Text(
                    if (linked) "Counted as a transfer between your accounts, not as spending or income."
                    else "Counted as ${if (t.amount < 0) "spending" else "income"}. Mark it as a transfer if the money " +
                        "only moved between your own accounts."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    scope.launch(Dispatchers.IO) {
                        if (linked) dao.unlink(t.id, t.transferOf!!) else dao.markTransfer(t.id)
                    }
                }) { Text(if (linked) "Not a transfer" else "Mark as transfer") }
            },
            dismissButton = {
                Row {
                    if (t.source == "MANUAL") TextButton(onClick = {
                        selected = null
                        scope.launch(Dispatchers.IO) { dao.unlinkFrom(t.id); dao.deleteTxn(t.id) }
                    }) { Text("Delete") }
                    TextButton(onClick = { selected = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (adding) AddTxnDialog(dao, onDone = { adding = false })
}

/** Manual entry for money the SMS never mentioned: cash, app-only payments, back-dated fixes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTxnDialog(dao: MoneyDao, onDone: () -> Unit) {
    val accounts by dao.visibleAccounts().collectAsStateWithLifecycle(emptyList())
    var spent by remember { mutableStateOf(true) }
    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // null = Cash wallet (created on first use if it does not exist yet).
    var accountId by remember { mutableStateOf<Long?>(null) }
    val datePicker = rememberDatePickerState(initialSelectedDateMillis = pickerDay(System.currentTimeMillis()))
    var pickingDate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val amount = amountText.replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }
    val cash = accounts.firstOrNull { it.provider == Ingest.CASH && it.number.isEmpty() }
    val choices = listOf<Pair<Long?, String>>((cash?.id) to Ingest.CASH) +
        accounts.filter { it.id != cash?.id }.map { it.id to label(it.provider, it.number) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(spent, { spent = true }); Text("Spent")
                    RadioButton(!spent, { spent = false }); Text("Received")
                }
                OutlinedTextField(amountText, { amountText = it }, label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true)
                TextButton(onClick = { pickingDate = true }) {
                    Text("Date: ${pickerDateFormat.format(Date(datePicker.selectedDateMillis ?: pickerDay(System.currentTimeMillis())))}")
                }
                Text("Account", style = MaterialTheme.typography.labelLarge)
                choices.forEach { (id, name) ->
                    val chosen = accountId == id || (accountId == null && id == cash?.id)
                    Row(Modifier.fillMaxWidth().clickable { accountId = id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen, { accountId = id }); Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = amount != null, onClick = {
                val minor = amount!!.movePointRight(2).toLong() * if (spent) -1 else 1
                val timestamp = entryTime(datePicker.selectedDateMillis ?: pickerDay(System.currentTimeMillis()))
                onDone()
                scope.launch(Dispatchers.IO) {
                    Ingest.addManual(dao, accountId, minor, description.trim().ifEmpty { null }, timestamp)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )

    if (pickingDate) {
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = { TextButton(onClick = { pickingDate = false }) { Text("OK") } },
        ) { DatePicker(datePicker) }
    }
}

@Composable
private fun TxnItem(t: TxnRow, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
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
    var selected by remember { mutableStateOf<Account?>(null) }
    var merging by remember { mutableStateOf<Account?>(null) }
    var confirmReimport by remember { mutableStateOf(false) }
    var reimporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Accounts", style = MaterialTheme.typography.headlineSmall)
        Text("Tap an account to correct its balance or merge it into another, e.g. a debit card into its bank account.",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.padding(top = 16.dp)) {
            items(accounts, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { selected = a }.padding(vertical = 12.dp)) {
                    Text(label(a.provider, a.number), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    a.balance?.let {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(it, a.currency), style = MaterialTheme.typography.bodyLarge)
                            // The balance is only as fresh as the last SMS that stated it.
                            Text("as of ${dateFormat.format(Date(a.balanceAt))}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        TextButton(onClick = { confirmReimport = true }, enabled = !reimporting, modifier = Modifier.padding(top = 16.dp)) {
            Text(if (reimporting) "Re-importing…" else "Re-import from SMS")
        }
    }

    if (confirmReimport) {
        AlertDialog(
            onDismissRequest = { confirmReimport = false },
            title = { Text("Re-import from SMS?") },
            text = { Text("Deletes everything imported from SMS, plus merges, transfer marks and corrected balances, then reads your SMS inbox again. Transactions you added by hand are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReimport = false
                    reimporting = true
                    scope.launch(Dispatchers.IO) {
                        try { InboxScan.reimport(context) } finally { reimporting = false }
                    }
                }) { Text("Re-import") }
            },
            dismissButton = { TextButton(onClick = { confirmReimport = false }) { Text("Cancel") } },
        )
    }

    selected?.let { a ->
        var text by remember(a.id) { mutableStateOf("") }
        val value = text.replace(",", "").toBigDecimalOrNull()
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(label(a.provider, a.number)) },
            text = {
                Column {
                    Text("Set the current balance if it differs from the last SMS. A newer SMS updates it again.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, label = { Text("Current balance (${a.currency})") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { selected = null; merging = a }) { Text("Merge into another account…") }
                }
            },
            confirmButton = {
                TextButton(enabled = value != null && value.scale() <= 2, onClick = {
                    selected = null
                    val minor = value!!.movePointRight(2).toLong()
                    scope.launch(Dispatchers.IO) { dao.setBalance(a.id, minor, System.currentTimeMillis()) }
                }) { Text("Save balance") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
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
