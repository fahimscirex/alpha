package com.fahimscirex.alpha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fahimscirex.alpha.data.Account
import com.fahimscirex.alpha.data.MoneyDao
import com.fahimscirex.alpha.sms.InboxScan
import com.fahimscirex.alpha.sms.Ingest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date

private val banks = setOf("Eastern Bank", "City Bank", "BRAC Bank", "Mutual Trust Bank")

private fun iconFor(a: Account): ImageVector = when {
    a.provider == Ingest.CASH -> Icons.Filled.Payments
    a.provider in banks -> Icons.Filled.AccountBalance
    else -> Icons.Filled.Smartphone // mobile wallets: bKash, Nagad, Tap, Rocket, Upay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(dao: MoneyDao, onBack: () -> Unit) {
    val accounts by dao.visibleAccounts().collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableStateOf<Account?>(null) }
    var merging by remember { mutableStateOf<Account?>(null) }
    var confirmReimport by remember { mutableStateOf(false) }
    var reimporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val total = accounts.filter { it.currency == "BDT" }.sumOf { it.balance ?: 0L }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Total balance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(money(total, "BDT"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text("Tap an account to correct its balance or merge it into another.",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(accounts, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { selected = a }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar { Icon(iconFor(a), null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    Text(label(a.provider, a.number), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(a.balance?.let { money(it, a.currency) } ?: "—", style = MaterialTheme.typography.bodyLarge)
                        // The balance is only as fresh as the last SMS (or correction) that stated it.
                        if (a.balance != null) Text("as of ${dateFormat.format(Date(a.balanceAt))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                OutlinedButton(onClick = { confirmReimport = true }, enabled = !reimporting, modifier = Modifier.padding(top = 24.dp)) {
                    Icon(Icons.Filled.Sync, null)
                    Text(if (reimporting) "  Re-importing…" else "  Re-import from SMS")
                }
            }
        }
    }

    if (confirmReimport) {
        fun start(keep: Boolean) {
            confirmReimport = false
            reimporting = true
            scope.launch(Dispatchers.IO) {
                try { InboxScan.reimport(context, keepEdits = keep) } finally { reimporting = false }
            }
        }
        AlertDialog(
            onDismissRequest = { confirmReimport = false },
            icon = { Icon(Icons.Filled.Sync, null) },
            title = { Text("Re-import from SMS") },
            text = {
                Column {
                    Text("Reads your SMS inbox again with the latest rules. Transactions you added by hand and your categories are always kept.")
                    Text("Keep the changes you made yourself — categories you picked, transfers you marked or unlinked, merged accounts and corrected balances — or overwrite them with what the SMS say?",
                        Modifier.padding(top = 12.dp))
                    TextButton(onClick = { start(keep = false) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Overwrite my changes", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { start(keep = true) }) { Text("Keep my changes") } },
            dismissButton = { TextButton(onClick = { confirmReimport = false }) { Text("Cancel") } },
        )
    }

    selected?.let { a ->
        var text by remember(a.id) { mutableStateOf("") }
        val value = text.replace(",", "").toBigDecimalOrNull()
        AlertDialog(
            onDismissRequest = { selected = null },
            icon = { Icon(iconFor(a), null) },
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
                        Row(Modifier.fillMaxWidth().clickable {
                            merging = null
                            scope.launch(Dispatchers.IO) { Ingest.merge(dao, from, into) }
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconFor(into), null)
                            Text("  " + label(into.provider, into.number), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } },
        )
    }
}
