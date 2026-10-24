package com.mauriciojuanes.pomodoro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.mauriciojuanes.pomodoro.ui.Bg
import com.mauriciojuanes.pomodoro.ui.Card1
import com.mauriciojuanes.pomodoro.ui.Card2
import com.mauriciojuanes.pomodoro.ui.DistractingAppsScreen
import com.mauriciojuanes.pomodoro.ui.Muted
import com.mauriciojuanes.pomodoro.ui.PomodoroTheme
import com.mauriciojuanes.pomodoro.ui.RemindersScreen
import com.mauriciojuanes.pomodoro.ui.ScheduleScreen
import com.mauriciojuanes.pomodoro.ui.SettingsScreen
import com.mauriciojuanes.pomodoro.ui.TextMain
import com.mauriciojuanes.pomodoro.ui.TimerScreen
import com.mauriciojuanes.pomodoro.ui.Ui
import com.mauriciojuanes.pomodoro.ui.observe
import com.mauriciojuanes.pomodoro.ui.permissions
import kotlinx.coroutines.delay

/** Cuatro pestañas: Pomodoro, Horario, Recordatorios y Ajustes (lo mismo que en la compu). */
class MainActivity : ComponentActivity() {
    private val refresh = { Ui.bump() }

    private data class Tab(val label: String, val icon: ImageVector)

    private val tabs = listOf(
        Tab("Pomodoro", Icons.Filled.Home), Tab("Horario", Icons.Filled.DateRange),
        Tab("Recordatorios", Icons.Filled.Notifications), Tab("Ajustes", Icons.Filled.Settings),
    )

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        PomodoroService.start(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            PomodoroTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var appsScreen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    while (true) {
                        Ui.bump()
                        delay(500)
                    }
                }
                BackHandler(enabled = appsScreen || tab != 0) { if (appsScreen) appsScreen = false else tab = 0 }
                Scaffold(
                    containerColor = Bg,
                    bottomBar = {
                        if (!appsScreen) NavigationBar(containerColor = Card1) {
                            tabs.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = TextMain, selectedTextColor = TextMain,
                                        indicatorColor = Card2, unselectedIconColor = Muted, unselectedTextColor = Muted),
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        observe()
                        when {
                            appsScreen -> DistractingAppsScreen { appsScreen = false }
                            tab == 0 -> TimerScreen(permissions(this@MainActivity).count { !it.ok }) { tab = 3 }
                            tab == 1 -> ScheduleScreen()
                            tab == 2 -> RemindersScreen()
                            else -> SettingsScreen { appsScreen = true }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.listen(refresh)
    }

    override fun onPause() {
        Engine.unlisten(refresh)
        super.onPause()
    }
}
