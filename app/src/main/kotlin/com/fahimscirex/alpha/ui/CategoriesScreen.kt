package com.fahimscirex.alpha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fahimscirex.alpha.data.Category
import com.fahimscirex.alpha.data.MoneyDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(dao: MoneyDao, onBack: () -> Unit) {
    val categories by dao.categories().collectAsStateWithLifecycle(emptyList())
    // A Category with id 0 means "new".
    var editing by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Category(name = "", emoji = "🏷️") }) { Icon(Icons.Filled.Add, "Add category") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
            item {
                Text("Transactions get a category from their merchant. Change one on a transaction and tick " +
                    "“Always use” to teach the app.", Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(categories, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { editing = c }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar { Text(c.emoji, style = MaterialTheme.typography.titleMedium) }
                    Text(c.name, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    editing?.let { CategoryDialog(dao, it, onDone = { editing = null }) }
}

@Composable
private fun CategoryDialog(dao: MoneyDao, c: Category, onDone: () -> Unit) {
    var name by remember { mutableStateOf(c.name) }
    var emoji by remember { mutableStateOf(c.emoji) }
    val scope = rememberCoroutineScope()
    val isNew = c.id == 0L
    val valid = name.isNotBlank() && emoji.isNotBlank()

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (isNew) "New category" else "Edit category") },
        text = {
            Column {
                OutlinedTextField(emoji, { emoji = it.take(8) }, label = { Text("Icon (any emoji)") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                if (!isNew) TextButton(onClick = {
                    onDone()
                    scope.launch(Dispatchers.IO) {
                        // Its transactions become uncategorized; its merchant rules go with it.
                        dao.uncategorize(c.id); dao.deleteRulesFor(c.id); dao.deleteCategoryRow(c.id)
                    }
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Delete category", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onDone()
                scope.launch(Dispatchers.IO) {
                    if (isNew) dao.insert(Category(name = name.trim(), emoji = emoji.trim()))
                    else dao.updateCategory(c.id, name.trim(), emoji.trim())
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
