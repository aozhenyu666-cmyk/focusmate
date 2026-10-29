package com.focusmate.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusmate.agent.ChatEntry
import com.focusmate.agent.FocusMode
import com.focusmate.agent.FocusProposal
import com.focusmate.agent.Origin
import com.focusmate.agent.ProposalStatus
import com.focusmate.agent.Role
import com.focusmate.runtime.AgentRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val state by AgentRuntime.state.collectAsState()
    val status by AgentRuntime.status.collectAsState()
    val keyOk by AgentRuntime.keyConfigured.collectAsState()
    val name = AgentRuntime.prefs.name
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val entries = state.chat

    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "说吧")
    }
    val canSpeak = remember { context.packageManager.queryIntentActivities(speechIntent, 0).isNotEmpty() }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) input = (input + heard).trim()
        }
    }

    LaunchedEffect(entries.size, status.thinking) {
        val count = entries.size + if (status.thinking) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    status.thinking -> "正在想…"
                    else -> AgentRuntime.focusInfo().description
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!keyOk) {
            Notice("还没有填模型的 API Key，$name 暂时说不了话。提醒和专注锁不受影响。", "去设置", onOpenSettings)
        } else if (status.error != null && !status.thinking) {
            Notice("上一次没成功：${status.error}", null, null)
        }
        status.storeError?.let { Notice(it, null, null) }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isEmpty()) {
                item { EmptyHint(name) }
            }
            items(entries, key = { it.id }) { e -> EntryRow(e, name) }
            if (status.thinking) {
                item(key = "thinking") { Bubble("……", mine = false, label = null) }
            }
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canSpeak) {
                TextButton(onClick = { runCatching { speech.launch(speechIntent) } }) { Text("语音") }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("和 $name 说点什么") },
                maxLines = 4,
            )
            IconButton(
                enabled = input.isNotBlank(),
                onClick = {
                    AgentRuntime.sendUser(input)
                    input = ""
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun EmptyHint(name: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("可以这样开始", fontWeight = FontWeight.SemiBold)
            Text("· 「半小时后提醒我喝水」", fontSize = 13.sp)
            Text("· 「我去复习高数了」——$name 会自己约时间回来问你进度", fontSize = 13.sp)
            Text("· 「帮我专注 25 分钟写报告」——点卡片上的开始，手机才会真的锁上", fontSize = 13.sp)
            Text("· 「我下周三考六级」——$name 会记住；记下的事在「安排」页能看、能删", fontSize = 13.sp)
        }
    }
}

@Composable
private fun Notice(text: String, action: String?, onAction: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EntryRow(e: ChatEntry, name: String) {
    when (e.role) {
        Role.EVENT -> Text(
            "${time(e.atMillis)} · ${e.text}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        Role.USER -> Bubble(e.text, mine = true, label = null)
        Role.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Bubble(e.text, mine = false, label = originLabel(e.origin)?.let { "$it · ${time(e.atMillis)}" })
            e.proposal?.let { ProposalCard(it) }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, label: String?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (label != null) {
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                tonalElevation = if (mine) 0.dp else 1.dp,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ProposalCard(p: FocusProposal) {
    Card(
        modifier = Modifier.widthIn(max = 320.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val mode = when (p.mode) {
                FocusMode.GEMINI -> "沉浸舱：只用 Gemini"
                FocusMode.DEEP -> "深度专注：只留在专注伙伴"
            }
            Text("$mode · ${p.minutes} 分钟", fontWeight = FontWeight.SemiBold)
            Text("目标：${p.goal}", fontSize = 13.sp)
            Text("期间只放行通话和微信，其它应用会被拦住；到点自动解除。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (p.status) {
                ProposalStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AgentRuntime.acceptProposal(p.id) }) { Text("开始") }
                    OutlinedButton(onClick = { AgentRuntime.declineProposal(p.id) }) { Text("先不了") }
                }
                ProposalStatus.ACCEPTED -> Text("已点开始。是否真的生效以上面的系统事件为准。", fontSize = 12.sp)
                ProposalStatus.DECLINED -> Text("没有开始。", fontSize = 12.sp)
                ProposalStatus.FAILED -> Text(
                    "没能开始：${p.detail ?: "原因未知"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

private fun originLabel(o: Origin): String? = when (o) {
    Origin.REMINDER -> "提醒"
    Origin.FOLLOW_UP -> "回访"
    Origin.FOCUS_REVIEW -> "复盘"
    Origin.SPONTANEOUS -> "主动找你"
    else -> null
}

private fun time(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
