package com.focusmate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.focusmate.runtime.AgentRuntime
import com.focusmate.runtime.GuardRuntime

class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardRuntime.install(applicationContext)
        AgentRuntime.install(applicationContext)
        val transient = mutableStateOf("")
        // 状态变化 → 把运行时留下的一条提示交给界面显示；界面自己不做状态解释。
        GuardRuntime.onSessionChanged = {
            GuardRuntime.message()?.let { msg -> transient.value = msg }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            FocusMateTheme { AppShell(transient) }
        }
    }

    override fun onResume() {
        super.onResume()
        GuardRuntime.checkExpiry()
        AgentRuntime.onUiVisible(true)
        // 打开 APP 时补处理到期事项（闹钟被系统推迟或进程被杀过）。前台不需要前台服务。
        AgentRuntime.kick()
    }

    override fun onPause() {
        AgentRuntime.onUiVisible(false)
        super.onPause()
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("对话", Icons.Filled.Face),
    Tab("专注", Icons.Filled.Lock),
    Tab("安排", Icons.Filled.DateRange),
    Tab("设置", Icons.Filled.Settings),
)

@androidx.compose.runtime.Composable
private fun AppShell(transient: MutableState<String>) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                0 -> ChatScreen(onOpenSettings = { tab = 3 })
                1 -> HomeScreen(runtime = GuardRuntime, transientMessage = transient)
                2 -> AgendaScreen()
                else -> SettingsScreen()
            }
        }
    }
}
