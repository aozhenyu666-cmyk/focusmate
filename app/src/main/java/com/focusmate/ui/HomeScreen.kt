package com.focusmate.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focusmate.core.Enforcement
import com.focusmate.core.FocusSession
import com.focusmate.core.Presets
import com.focusmate.core.SessionState
import com.focusmate.core.SessionStates
import com.focusmate.platform.Permissions
import com.focusmate.runtime.GuardRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(runtime: GuardRuntime, transientMessage: MutableState<String>) {
    val context = LocalContext.current
    var session by remember { mutableStateOf(runtime.session()) }
    var overlayOk by remember { mutableStateOf(Permissions.hasOverlay(context)) }
    var a11yOk by remember { mutableStateOf(Permissions.isAccessibilityEnabled(context)) }
    val geminiPackage = remember {
        Presets.GEMINI_CANDIDATES.firstOrNull { isInstalled(context, it) } ?: Presets.GEMINI_CANDIDATES.first()
    }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 每次回到前台重读权限与状态，不缓存旧值（权限可能刚在系统设置里改过）。
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayOk = Permissions.hasOverlay(context)
                a11yOk = Permissions.isAccessibilityEnabled(context)
                session = runtime.session()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        runtime.onSessionChanged = { session = it }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("专注伙伴", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("一期只做一件事：限制真实生效、到点真的恢复。", fontSize = 13.sp)

            transientMessage.value.takeIf { it.isNotBlank() }?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(it, Modifier.padding(14.dp), fontSize = 13.sp)
                }
            }

            errorText?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text("没能开始：" + it, Modifier.padding(14.dp), fontSize = 13.sp)
                }
            }

            PermissionCard(
                overlayOk = overlayOk,
                a11yOk = a11yOk,
                onOverlay = { context.startActivity(Permissions.overlaySettingsIntent(context)) },
                onA11y = { context.startActivity(Permissions.accessibilitySettingsIntent()) },
            )

            val s = session
            if (s != null && SessionStates.isActive(s.state)) {
                ActiveCard(s, onEnd = { runtime.endByUser("界面主动结束") })
            } else {
                StartCard(
                    geminiLabel = labelOf(context, geminiPackage),
                    onDock = {
                        errorText = null
                        val built = Presets.geminiDock(
                            geminiPackage = geminiPackage,
                            nowMillis = System.currentTimeMillis(),
                            enforcement = Enforcement.OVERLAY_PLUS_A11Y,
                        )
                        runtime.startSession(built) { errorText = it }
                        session = runtime.session()
                    },
                    onSingle = {
                        errorText = null
                        val built = Presets.singleApp(
                            targetPackage = geminiPackage,
                            durationMillis = 5L * 60L * 1000L,
                            nowMillis = System.currentTimeMillis(),
                            enforcement = Enforcement.OVERLAY_PLUS_A11Y,
                        )
                        runtime.startSession(built) { errorText = it }
                        session = runtime.session()
                    },
                )
            }

            CapabilityCard(runtime.capabilityNotes())

            val lastFailure = s?.failure
            if (lastFailure != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        "最近一次失败：" + lastFailure.code.name + " · " + lastFailure.detail,
                        Modifier.padding(14.dp),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(overlayOk: Boolean, a11yOk: Boolean, onOverlay: () -> Unit, onA11y: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("权限现状", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (overlayOk) "✓ 悬浮窗" else "✗ 悬浮窗", fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (!overlayOk) TextButton(onClick = onOverlay) { Text("去开启") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (a11yOk) "✓ 无障碍" else "✗ 无障碍", fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (!a11yOk) TextButton(onClick = onA11y) { Text("去开启") }
            }
        }
    }
}

@Composable
private fun StartCard(geminiLabel: String, onDock: () -> Unit, onSingle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("和 " + geminiLabel + " 待十分钟", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "点下去会切到 " + geminiLabel +
                    "。接下来约十分钟只有 " + geminiLabel +
                    "、通话和微信可用，其它应用会被拦住。到点自动恢复。",
                fontSize = 13.sp,
            )
            Button(onClick = onDock, modifier = Modifier.fillMaxWidth()) { Text("进入沉浸舱 · 10 分钟") }
            OutlinedButton(onClick = onSingle, modifier = Modifier.fillMaxWidth()) { Text("先用 5 分钟单应用试一次") }
        }
    }
}

@Composable
private fun ActiveCard(session: FocusSession, onEnd: () -> Unit) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stateLabel(session.state), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("目标：" + session.targetPackage, fontSize = 13.sp)
            Text("到期：" + fmt.format(Date(session.expiresAtMillis)), fontSize = 13.sp)
            Text("规则版本：v" + session.ruleVersion + "　会话：" + session.id.take(8), fontSize = 12.sp)
            if (session.exceptions.isNotEmpty()) {
                Text("放行：" + session.exceptions.joinToString("、") { a -> a.label }, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth()) { Text("结束专注") }
        }
    }
}

@Composable
private fun CapabilityCard(notes: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("能力边界（不美化）", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            notes.forEach { Text("· " + it, fontSize = 12.sp) }
        }
    }
}

private fun stateLabel(s: SessionState): String = when (s) {
    SessionState.PREPARING -> "正在进入目标应用…"
    SessionState.ENFORCING -> "专注进行中"
    SessionState.AWAY_ALLOWED -> "在允许的例外应用里（仍在守护）"
    SessionState.BLOCKING -> "已拦截：请回到目标应用"
    SessionState.RELEASING -> "正在解除限制…"
    SessionState.COMPLETED -> "已结束并恢复"
    SessionState.USER_ENDED -> "已由你结束"
    SessionState.FAILED -> "执行失败，需处理"
}

private fun isInstalled(context: Context, pkg: String): Boolean = try {
    context.packageManager.getApplicationInfo(pkg, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

private fun labelOf(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Throwable) {
    "Gemini"
}
