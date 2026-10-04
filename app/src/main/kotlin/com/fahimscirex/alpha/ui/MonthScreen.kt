package com.fahimscirex.alpha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fahimscirex.alpha.data.CategoryTotal
import com.fahimscirex.alpha.data.MoneyDao
import com.fahimscirex.alpha.data.TxnRow
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthScreen(dao: MoneyDao, onNavigate: (Screen) -> Unit) {
    // 0 = this month, -1 = last month, ...
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val (from, to) = remember(offset) { month(offset) }
    val rows by remember(from) { dao.txns(from, to) }.collectAsStateWithLifecycle(emptyList())
    val spent by remember(from) { dao.spent(from, to) }.collectAsStateWithLifecycle(0L)
    val received by remember(from) { dao.received(from, to) }.collectAsStateWithLifecycle(0L)
    val transferred by remember(from) { dao.transferred(from, to) }.collectAsStateWithLifecycle(0L)
    val byCategory by remember(from) { dao.spentByCategory(from, to) }.collectAsStateWithLifecycle(emptyList())
    val unparsed by dao.unparsedCount().collectAsStateWithLifecycle(0)
    var selected by remember { mutableStateOf<TxnRow?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { offset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                        Text(monthFormat.format(Date(from)), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { offset++ }, enabled = offset < 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate(Screen.CATEGORIES) }) { Icon(Icons.Filled.Category, "Categories") }
                    IconButton(onClick = { onNavigate(Screen.ACCOUNTS) }) { Icon(Icons.Outlined.AccountBalanceWallet, "Accounts") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add transaction") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
        ) {
            item { Summary(spent, received, transferred) }
            if (unparsed > 0) item {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" $unparsed SMS could not be read", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (byCategory.isNotEmpty() && spent > 0) item { CategoryBreakdown(byCategory, spent) }
            if (rows.isEmpty()) item {
                Text("No transactions this month", Modifier.padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Rows arrive newest first; a header starts each day.
            rows.forEachIndexed { i, t ->
                if (i == 0 || dayKey(rows[i - 1].timestamp) != dayKey(t.timestamp)) item(key = "day-" + dayKey(t.timestamp)) {
                    Text(dayHeaderFormat.format(Date(t.timestamp)), Modifier.padding(top = 20.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                item(key = t.id) { TxnItem(t, onClick = { selected = t }) }
            }
        }
    }

    selected?.let { TxnDialog(dao, it, onDone = { selected = null }) }
    if (adding) AddTxnDialog(dao, onDone = { adding = false })
}

@Composable
private fun Summary(spent: Long, received: Long, transferred: Long) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Spent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(money(spent, "BDT"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Received", money(received, "BDT"))
                if (transferred > 0) Stat("Moved between accounts", money(transferred, "BDT"))
            }
        }
    }
}

@Composable
private fun Stat(name: String, value: String) {
    Column {
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun CategoryBreakdown(totals: List<CategoryTotal>, spent: Long) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) totals else totals.take(4)
    Column(Modifier.padding(top = 20.dp)) {
        Text("Where it went", style = MaterialTheme.typography.titleSmall)
        shown.forEach { c ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.emoji ?: "❔", Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(c.name ?: "Uncategorized", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(money(c.total, "BDT"), style = MaterialTheme.typography.bodyMedium)
                    }
                    LinearProgressIndicator(
                        progress = { (c.total.toFloat() / spent).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        drawStopIndicator = {},
                    )
                }
            }
        }
        if (totals.size > 4) Text(
            if (expanded) "Show less" else "Show all ${totals.size}",
            Modifier.clickable { expanded = !expanded }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
fun Avatar(content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TxnItem(t: TxnRow, onClick: () -> Unit) {
    val transfer = t.transferOf != null && !t.reversed
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar {
            when {
                transfer -> Icon(Icons.Filled.SwapHoriz, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                t.reversed -> Icon(Icons.AutoMirrored.Filled.Undo, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                else -> Text(t.emoji ?: (t.merchant ?: t.provider).take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.merchant ?: t.provider, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = when {
                transfer -> "Transfer"
                t.reversed -> "Reversed"
                else -> t.categoryName
            }
            Text(listOfNotNull(kind, label(t.provider, t.number), timeFormat.format(Date(t.timestamp))).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(t.amount, t.currency, signed = true), style = MaterialTheme.typography.bodyLarge,
                color = when {
                    transfer || t.reversed -> MaterialTheme.colorScheme.onSurfaceVariant
                    t.amount > 0 -> incomeColor
                    else -> MaterialTheme.colorScheme.onSurface
                })
            t.bdtAmount?.let {
                Text("≈ " + money(it, "BDT", signed = true), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
