package com.blakeriding.upright

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blakeriding.upright.ui.AlarmsScreen
import com.blakeriding.upright.ui.HistoryScreen
import com.blakeriding.upright.ui.MedsScreen
import com.blakeriding.upright.ui.SettingsScreen
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    /** Card taps while the app is open, for registering NFC tags. */
    private val nfc = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Tab requested by a notification or the widget. */
    private val requestedTab = mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTab.intValue = intent.getIntExtra(EXTRA_TAB, -1)
        setContent {
            UprightTheme {
                val state by Store.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(if (state.alarms.isEmpty() && !state.settings.protectionOn) 3 else 0) }
                val asked = requestedTab.intValue
                androidx.compose.runtime.LaunchedEffect(asked) {
                    if (asked >= 0) {
                        tab = asked
                        requestedTab.intValue = -1
                    }
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                            }
                        }
                    },
                ) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        val session = state.session
                        if (session != null && session.phase != Phase.WAITING) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    startActivity(Intent(this@MainActivity, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                },
                            ) { Text("Alarm in progress. Tap to finish it.", Modifier.padding(16.dp)) }
                        }
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> AlarmsScreen(state)
                                1 -> MedsScreen(state, nfc)
                                2 -> HistoryScreen(state)
                                else -> SettingsScreen(state, nfc)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.intValue = intent.getIntExtra(EXTRA_TAB, -1)
    }

    override fun onResume() {
        super.onResume()
        startCardReader { nfc.tryEmit(it) }
    }

    override fun onPause() {
        stopCardReader()
        super.onPause()
    }

    companion object {
        const val EXTRA_TAB = "tab"

        private val TABS = listOf(
            "Alarms" to Icons.Default.Notifications,
            "Meds" to Icons.Default.Favorite,
            "History" to Icons.Default.DateRange,
            "Settings" to Icons.Default.Settings,
        )
    }
}
