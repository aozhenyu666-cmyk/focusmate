package com.focusmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusmate.companion.CompanionHolder
import com.focusmate.companion.CompanionState
import com.focusmate.companion.FactStatus
import com.focusmate.companion.FollowUpStatus
import com.focusmate.companion.ModelSettings
import com.focusmate.companion.label
import com.focusmate.companion.Utterance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话页。刻意把"证据"放在明面上：
 * - 事实表要显示来源（你说的 / 我看到的 / 我猜的）；
 * - 事项表要显示状态与依据，改期/取消后旧条目不消失，而是标成已取消；
 * - 投递记录只写"生成 / 展示 / TTS"，不写"已读"。
 */
@Composable
fun CompanionScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<CompanionState?>(null) }
    val messages = remember { mutableStateListOf<Utterance>() }
    var draft by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val runtime = CompanionHolder.ensureStarted(context)
        runtime.onChanged = { s ->
            state = s
            messages.clear()
            messages.addAll(s.dialogue.takeLast(40))
        }
        runtime.onProblem = { notice = it }
        runtime.onUtterance = { u, trace ->
            // 主动发言同时走通知：文字先落地，语音失败也不影响这条消息可见。
            val notifier = com.focusmate.notify.Notifier(context)
            val shown = notifier.companionSays(u.text)
            if (shown) trace.shown(System.currentTimeMillis())
        }
        state = runtime.current()
        messages.clear()
        messages.addAll(runtime.current().dialogue.takeLast(40))
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("伙伴", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "他只在你说话、或到点有事时开口。改了会留痕，忘了就不会再提。",
            fontSize = 12.sp,
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("对话") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("他记得的") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("排着的事") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("设置") })
        }

        notice?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(10.dp)) {
                    Text(it, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notice = null }) { Text("知道了") }
                }
            }
        }

        when (tab) {
            0 -> ChatTab(messages, draft, onDraft = { draft = it }, onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    draft = ""
                    CompanionHolder.runtime()?.userSays(text) { }
                }
            })
            1 -> FactsTab(
                state = state,
                onCorrect = { id, text -> CompanionHolder.runtime()?.correctFact(id, text) },
                onForget = { id -> CompanionHolder.runtime()?.forgetFact(id) },
            )
            2 -> FollowUpsTab(
                state = state,
                onCancel = { id -> CompanionHolder.runtime()?.cancelFollowUp(id) },
                onReschedule = { id, due -> CompanionHolder.runtime()?.rescheduleFollowUp(id, due) },
            )
            else -> SettingsTab(state)
        }
    }
}

@Composable
private fun ChatTab(
    messages: List<Utterance>,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { u ->
                val mine = u.role == Utterance.Role.USER
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (mine) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row {
                            Text(if (mine) "你" else "他", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "  " + fmt.format(Date(u.at)) + if (u.proactive) " · 主动说的" else "",
                                fontSize = 10.sp,
                            )
                        }
                        Text(u.text, fontSize = 14.sp)
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f),
            placeholder = { Text("说点什么，比如「十分钟后再聊」", fontSize = 12.sp) },
            maxLines = 3,
        )
        Button(onClick = onSend) { Text("发送") }
    }
}

@Composable
private fun FactsTab(
    state: CompanionState?,
    onCorrect: (String, String) -> Boolean?,
    onForget: (String) -> Boolean?,
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    val active = state?.facts?.filter { it.status == FactStatus.ACTIVE } ?: emptyList()
    val retired = state?.facts?.filter { it.status != FactStatus.ACTIVE } ?: emptyList()
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "只有「当前有效」的会进入他的上下文。更正或忘掉的条目不会消失，但不会再被提到。",
                    fontSize = 11.sp,
                )
            }
            if (active.isEmpty()) {
                item { Text("还没有记下任何事。", fontSize = 13.sp) }
            }
            items(active) { f ->
                Card {
                    Column(Modifier.padding(10.dp)) {
                        Text(f.text, fontSize = 14.sp)
                        Text("来源：" + f.source.label() + "　重要度 " + f.importance, fontSize = 11.sp)
                        val ref = f.sourceRef
                        if (ref != null) Text("依据：" + ref, fontSize = 10.sp)
                        if (editingId == f.id) {
                            OutlinedTextField(
                                value = editText,
                                onValueChange = { editText = it },
                                label = { Text("改成什么") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row {
                                TextButton(onClick = {
                                    onCorrect(f.id, editText)
                                    editingId = null
                                    editText = ""
                                }) { Text("保存更正") }
                                TextButton(onClick = { editingId = null }) { Text("取消") }
                            }
                        } else {
                            Row {
                                TextButton(onClick = { editingId = f.id; editText = f.text }) { Text("更正") }
                                TextButton(onClick = { onForget(f.id) }) { Text("忘掉") }
                            }
                        }
                    }
                }
            }
            if (retired.isNotEmpty()) {
                item { Text("已退出上下文的（更正/遗忘）：" + retired.size + " 条", fontSize = 11.sp) }
                items(retired) { f ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(f.text, fontSize = 12.sp)
                            Text(
                                if (f.status == FactStatus.SUPERSEDED) "已被更正" else "已按要求遗忘",
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowUpsTab(
    state: CompanionState?,
    onCancel: (String) -> Boolean?,
    onReschedule: (String, Long) -> Boolean?,
) {
    val fmt = remember { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()) }
    val items = state?.followUps?.sortedByDescending { it.updatedAt } ?: emptyList()
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("同一个话题再约定一次，旧的会变「已取消」，不会两条并存。", fontSize = 11.sp) }
            if (items.isEmpty()) {
                item { Text("现在没有排着的事。", fontSize = 13.sp) }
            }
            items(items) { f ->
                Card {
                    Column(Modifier.padding(10.dp)) {
                        Text(f.topic, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("状态：" + statusLabel(f.status) + "　版本 v" + f.version, fontSize = 11.sp)
                        Text("到：" + fmt.format(Date(f.dueAtMillis)), fontSize = 11.sp)
                        Text("依据：" + f.evidence, fontSize = 11.sp)
                        if (f.attempts > 0) Text("已尝试 " + f.attempts + " 次", fontSize = 10.sp)
                        if (f.status == FollowUpStatus.PENDING || f.status == FollowUpStatus.AWAITING_REPLY) {
                            Row {
                                TextButton(onClick = {
                                    onReschedule(f.id, System.currentTimeMillis() + 10 * 60_000L)
                                }) { Text("推迟 10 分钟") }
                                TextButton(onClick = { onCancel(f.id) }) { Text("取消") }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SettingsTab(current: CompanionState?) {
    val context = LocalContext.current
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }
    val snapshot = remember { ModelSettings.load(context) }
    // 配置会被开关改动：直接读上层传下来的当前状态，避免读到过期快照。
    val liveState = current

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(snapshot.config.describe(), fontSize = 12.sp)
        Text("API Key 只存在本机私有目录，不写日志、不进仓库。", fontSize = 11.sp)
        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("接口地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key（留空表示不修改）") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            ModelSettings.save(context, endpoint, model, key)
            key = ""
            saved = "已保存（本机）"
        }) { Text("保存") }
        saved?.let { Text(it, fontSize = 12.sp) }

        Text("主动交流规则（当前为保守默认，等待你确认）", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        val state = liveState
        val c = state?.config
        if (c != null) {
            Text("两次之间至少 " + (c.gapMinMillis / 60000) + " 分钟；每天最多 " + c.dailyMax + " 次", fontSize = 12.sp)
            Text("免打扰：" + clockText(c.quietStartMinute) + " 至 " + clockText(c.quietEndMinute), fontSize = 12.sp)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (c.allowSmallTalk) "允许没有约定也主动闲聊" else "只按约定和未说完的事开口（不主动闲聊）",
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    CompanionHolder.runtime()?.setAllowSmallTalk(!c.allowSmallTalk)
                }) { Text(if (c.allowSmallTalk) "关掉" else "打开") }
            }
            if (c.allowSmallTalk) {
                Text(
                    "打开后会从已有记忆里找话题，但只在有真实依据时才说；没有依据仍然不说话。",
                    fontSize = 11.sp,
                )
            }
            Text("失败重试 " + (c.retryMillis / 60000) + " 分钟，最多 " + c.maxAttempts + " 次", fontSize = 12.sp)
        }
        Text(
            "后台限制：Android 不保证准点。息屏/省电可能让这句话晚几分钟；" +
                "强行停止 APP 后不会自动恢复。",
            fontSize = 11.sp,
        )
    }
}

private fun statusLabel(s: FollowUpStatus): String = when (s) {
    FollowUpStatus.PENDING -> "等着"
    FollowUpStatus.DELIVERING -> "正在说"
    FollowUpStatus.AWAITING_REPLY -> "已说出，等你回应"
    FollowUpStatus.DONE -> "已经说过"
    FollowUpStatus.CANCELLED -> "已取消"
    FollowUpStatus.EXPIRED -> "已过期"
    FollowUpStatus.FAILED -> "失败"
}

private fun clockText(minuteOfDay: Int): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
}
