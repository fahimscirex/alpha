package com.fahimscirex.alpha.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.fahimscirex.alpha.data.AppDb
import com.fahimscirex.alpha.sms.InboxScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class Screen { MONTH, ACCOUNTS, CATEGORIES }

// Money-green accent; everything else follows Material defaults.
private val light = lightColorScheme(primary = Color(0xFF1E6B52), primaryContainer = Color(0xFFA6F2D1), onPrimaryContainer = Color(0xFF002117))
private val dark = darkColorScheme(primary = Color(0xFF8BD6B6), primaryContainer = Color(0xFF00513B), onPrimaryContainer = Color(0xFFA6F2D1))

/** Income amounts read green on both themes. */
val incomeColor @androidx.compose.runtime.Composable get() = MaterialTheme.colorScheme.primary

class MainActivity : ComponentActivity() {

    private val smsPermissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { granted -> granted }) scanInbox()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDb.get(this).dao()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light) {
                var screen by rememberSaveable { mutableStateOf(Screen.MONTH) }
                val back = { screen = Screen.MONTH }
                when (screen) {
                    Screen.MONTH -> MonthScreen(dao, onNavigate = { screen = it })
                    Screen.ACCOUNTS -> { BackHandler(onBack = back); AccountsScreen(dao, onBack = back) }
                    Screen.CATEGORIES -> { BackHandler(onBack = back); CategoriesScreen(dao, onBack = back) }
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
