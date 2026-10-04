package com.fahimscirex.alpha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fahimscirex.alpha.data.Category
import com.fahimscirex.alpha.data.CategoryRule
import com.fahimscirex.alpha.data.MoneyDao
import com.fahimscirex.alpha.data.TxnRow
import com.fahimscirex.alpha.sms.Ingest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date

/** Category chips; null selects "none". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(categories: List<Category>, selected: Long?, onSelect: (Long?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        categories.forEach { c ->
            FilterChip(selected = c.id == selected, onClick = { onSelect(if (c.id == selected) null else c.id) },
                label = { Text("${c.emoji} ${c.name}") })
        }
    }
}

/** Details of one transaction: category, transfer status, and description/delete for manual ones. */
@Composable
fun TxnDialog(dao: MoneyDao, t: TxnRow, onDone: () -> Unit) {
    val categories by dao.categories().collectAsStateWithLifecycle(emptyList())
    var categoryId by remember { mutableStateOf(t.categoryId) }
    var always by remember { mutableStateOf(t.merchant != null) }
    val manual = t.source == "MANUAL"
    var description by remember { mutableStateOf(t.merchant.orEmpty()) }
    val linked = t.transferOf != null
    val scope = rememberCoroutineScope()
    fun io(block: suspend () -> Unit) { onDone(); scope.launch(Dispatchers.IO) { block() } }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(money(t.amount, t.currency, signed = true)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(t.merchant ?: t.provider, style = MaterialTheme.typography.titleMedium)
                Text("${label(t.provider, t.number)} · ${dateFormat.format(Date(t.timestamp))}, ${timeFormat.format(Date(t.timestamp))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (manual) OutlinedTextField(description, { description = it }, label = { Text("Description") },
                    singleLine = true, modifier = Modifier.padding(top = 12.dp))
                Text(
                    if (linked) "Counted as a transfer between your accounts, not as spending or income."
                    else "Counted as ${if (t.amount < 0) "spending" else "income"}.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(onClick = { io { if (linked) dao.unlink(t.id, t.transferOf!!) else dao.markTransfer(t.id) } }) {
                        Text(if (linked) "Not a transfer" else "Mark as transfer")
                    }
                    if (manual) TextButton(onClick = { io { dao.unlinkFrom(t.id); dao.deleteTxn(t.id) } }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text("Category", Modifier.padding(top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
                CategoryPicker(categories, categoryId) { categoryId = it }
                if (t.merchant != null && !manual) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(always, { always = it })
                    Text("Always use for “${t.merchant}”", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                io {
                    if (manual) dao.renameManual(t.id, description.trim().ifEmpty { null })
                    dao.setCategory(t.id, categoryId)
                    val merchant = t.merchant
                    if (!manual && always && merchant != null && categoryId != null) {
                        // Learn it: this merchant's past and future transactions get the category.
                        dao.insert(CategoryRule(pattern = "=" + merchant.lowercase(), categoryId = categoryId!!, user = true))
                        dao.setCategoryForMerchant(merchant, categoryId!!)
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** Manual entry for money the SMS never mentioned: cash, app-only payments, back-dated fixes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTxnDialog(dao: MoneyDao, onDone: () -> Unit) {
    val accounts by dao.visibleAccounts().collectAsStateWithLifecycle(emptyList())
    val categories by dao.categories().collectAsStateWithLifecycle(emptyList())
    var spent by remember { mutableStateOf(true) }
    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
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
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(spent, { spent = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Spent") }
                    SegmentedButton(!spent, { spent = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Received") }
                }
                OutlinedTextField(amountText, { amountText = it }, label = { Text("Amount (৳)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true)
                TextButton(onClick = { pickingDate = true }) {
                    Icon(Icons.Filled.CalendarMonth, null)
                    Text("  " + pickerDateFormat.format(Date(datePicker.selectedDateMillis ?: pickerDay(System.currentTimeMillis()))))
                }
                Text("Category", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
                CategoryPicker(categories, categoryId) { categoryId = it }
                Text("Account", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
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
                    Ingest.addManual(dao, accountId, minor, description.trim().ifEmpty { null }, timestamp, categoryId)
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
