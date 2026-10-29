package com.focusmate.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusmate.agent.Proactivity
import com.focusmate.runtime.AgentRuntime
import kotlinx.coroutines.launch

private data class Provider(val label: String, val baseUrl: String, val model: String)

private val PROVIDERS = listOf(
    Provider("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    Provider("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    Provider("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyOk by AgentRuntime.keyConfigured.collectAsState()
    val initial = remember { AgentRuntime.prefs }

    var keyInput by remember { mutableStateOf("") }
    var keyHint by remember { mutableStateOf(AgentRuntime.apiKeyHint()) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var name by remember { mutableStateOf(initial.name) }
    var style by remember { mutableStateOf(initial.style) }
    var proactivity by remember { mutableStateOf(initial.proactivity) }
    var quietStart by remember { mutableIntStateOf(initial.quietStartMinute / 60) }
    var quietEnd by remember { mutableIntStateOf(initial.quietEndMinute / 60) }
    var speak by remember { mutableStateOf(initial.speakAloud) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun save() {
        AgentRuntime.savePrefs(
            AgentRuntime.prefs.copy(
                name = name.ifBlank { initial.name },
                style = style.ifBlank { initial.style },
                proactivity = proactivity,
                quietStartMinute = quietStart * 60,
                quietEndMinute = quietEnd * 60,
                speakAloud = speak,
                baseUrl = baseUrl,
                model = model,
            ),
        )
        saved = true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Section("模型") {
            Line(if (keyOk) "API Key：已保存 ${keyHint.orEmpty()}（加密存放在本机）" else "API Key：还没有")
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (keyOk) "换一个 Key" else "粘贴 API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = keyInput.isNotBlank(), onClick = {
                    AgentRuntime.setApiKey(keyInput)
                    keyInput = ""
                    keyHint = AgentRuntime.apiKeyHint()
                }) { Text("保存 Key") }
                if (keyOk) OutlinedButton(onClick = {
                    AgentRuntime.setApiKey(null)
                    keyHint = null
                }) { Text("删除 Key") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PROVIDERS.forEach { p ->
                    FilterChip(
                        selected = baseUrl == p.baseUrl,
                        onClick = { baseUrl = p.baseUrl; model = p.model; saved = false },
                        label = { Text(p.label) },
                    )
                }
            }
            OutlinedTextField(baseUrl, { baseUrl = it; saved = false }, label = { Text("接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it; saved = false }, label = { Text("模型名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !testing && keyOk, onClick = {
                    save()
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = AgentRuntime.testConnection()
                        testing = false
                    }
                }) { Text(if (testing) "测试中…" else "测试连接") }
            }
            testResult?.let { Line(it) }
            Line("需要支持工具调用（function calling）的模型。DeepSeek 用 deepseek-chat。")
        }

        Section("伙伴") {
            OutlinedTextField(name, { name = it; saved = false }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(style, { style = it; saved = false }, label = { Text("性格与说话方式") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text("主动程度", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Proactivity.entries.forEach { p ->
                    FilterChip(selected = proactivity == p, onClick = { proactivity = p; saved = false }, label = { Text(p.label) })
                }
            }
            Line("${proactivity.label}：没约定时每天最多主动找你 ${proactivity.dailyCap} 次，至少隔 ${proactivity.minGapMinutes} 分钟；你没回就自动放慢。提醒、回访、专注复盘不受这个限制。")
            Text("安静时段（不闲聊、不回访；你设的提醒照常）", fontSize = 13.sp)
            HourStepper("从", quietStart) { quietStart = it; saved = false }
            HourStepper("到", quietEnd) { quietEnd = it; saved = false }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("朗读它说的话（系统语音）", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = speak, onCheckedChange = { speak = it; saved = false })
            }
            if (speak) Line("语音状态：${AgentRuntime.speakerStatus()}。安静时段不朗读。")
            Button(onClick = { save() }) { Text(if (saved) "已保存" else "保存设置") }
        }

        Section("让它能准时找到你") {
            val notifOk = AgentRuntime.notificationsEnabled()
            PermissionRow("通知", notifOk) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            val exactOk = AgentRuntime.canExactAlarm()
            PermissionRow("闹钟和提醒（准点）", exactOk) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            val pm = context.getSystemService(PowerManager::class.java)
            val batteryOk = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            PermissionRow("不限制电池", batteryOk) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Line("vivo / iQOO：还要在 i 管家里允许「自启动」和「后台高耗电」，并在最近任务里锁住本 APP。系统强行停止后，提醒要等你再打开 APP 才会恢复。")
        }

        Section("数据") {
            Line("聊天、记忆、安排都只存在这台手机的应用私有目录。发给模型的是最近的对话、记忆和安排。")
            if (!confirmClear) {
                OutlinedButton(onClick = { confirmClear = true }) { Text("清空聊天记录") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AgentRuntime.clearChat(); confirmClear = false }) { Text("确定清空（记忆和安排保留）") }
                    TextButton(onClick = { confirmClear = false }) { Text("算了") }
                }
            }
        }
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ", fontSize = 14.sp)
        TextButton(onClick = { onChange((hour + 23) % 24) }) { Text("−") }
        Text(String.format("%02d:00", hour), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onChange((hour + 1) % 24) }) { Text("+") }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (ok) "✓ " else "✗ ") + label,
            fontSize = 14.sp,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        if (!ok) TextButton(onClick = onFix) { Text("去开启") }
    }
}
