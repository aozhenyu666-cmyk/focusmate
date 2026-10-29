package com.focusmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusmate.companion.CompanionHolder
import com.focusmate.companion.CompanionState
import com.focusmate.companion.FactStatus
import com.focusmate.companion.FollowUpStatus
import com.focusmate.companion.ModelSettings
import com.focusmate.companion.Utterance
import com.focusmate.companion.label
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 伙伴页。四个子页用"胶囊切换"而不是 TabRow，和顶部一致。
 *
 * 上一版最大的问题：对话页在没有消息时是 2000px 空白，只有底部一个输入框。
 * 空屏不解释、不给下一步，是最容易让人卸载的地方，所以每页都补了空状态。
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
            messages.addAll(s.dialogue.takeLast(60))
        }
        runtime.onProblem = { notice = it }
        runtime.onUtterance = { u, trace ->
            val notifier = com.focusmate.notify.Notifier(context)
            if (notifier.companionSays(u.text)) trace.shown(System.currentTimeMillis())
        }
        state = runtime.current()
        messages.clear()
        messages.addAll(runtime.current().dialogue.takeLast(60))
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("伙伴", style = MaterialTheme.typography.headlineMedium)
            Text(
                "只在你说过、或到点有事时开口。改了会留痕，忘了就不会再提。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        SubSwitcher(
            labels = listOf("对话", "他记得的", "排着的事", "设置"),
            selected = tab,
            onSelect = { tab = it },
            counts = listOf(
                0,
                state?.facts?.count { it.status == FactStatus.ACTIVE } ?: 0,
                state?.followUps?.count { it.open } ?: 0,
                0,
            ),
        )

        notice?.let {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { notice = null }) { Text("知道了", style = MaterialTheme.typography.labelSmall) }
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatTab(
                    messages = messages,
                    draft = draft,
                    onDraft = { draft = it },
                    onSend = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            draft = ""
                            CompanionHolder.runtime()?.userSays(text) { }
                        }
                    },
                )
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
}

@Composable
private fun SubSwitcher(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, counts: List<Int>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            val count = counts.getOrElse(index) { 0 }
            Box(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large)
                    .background(if (active) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 0) label + " " + count else label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.sp,
                )
            }
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
    val listState = rememberLazyListState()

    // 新消息进来滚到底部。没有这一条，发完看不到自己刚说的话。
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "还没说过话",
                    hint = "说点什么开始。\n说「十分钟后再聊」会排一条跟进，" +
                        "但不会改变专注时长。",
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { u ->
                    MessageBubble(u, fmt.format(Date(u.at)))
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么，比如「十分钟后再聊」", style = MaterialTheme.typography.bodyMedium) },
                maxLines = 4,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Button(
                onClick = onSend,
                modifier = Modifier.height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) { Text("发送", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/**
 * 消息气泡。
 * 用户靠右、伙伴靠左，颜色区分；主动说的额外标出来——
 * 用户需要能一眼看出"这句是我问出来的"还是"他自己开口的"。
 */
@Composable
private fun MessageBubble(u: Utterance, timeText: String) {
    val mine = u.role == Utterance.Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (u.proactive) {
                    StatusBadge("他主动说的", Tone.Active, Modifier.padding(end = 6.dp))
                }
                Text(
                    (if (mine) "你" else "他") + " · " + timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    u.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
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

    if (active.isEmpty() && retired.isEmpty()) {
        EmptyState(
            title = "他还没记住什么",
            hint = "聊过的话如果像一条可用信息，会自动记下来。\n每条都会标来源。",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "只有「有效」的会进入他的上下文。更正或忘掉的不会消失，但不会再被提到。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(active, key = { it.id }) { f ->
            val tone = when (f.source.name) {
                "USER_STATED" -> Tone.Active
                "SYSTEM_OBSERVED" -> Tone.Success
                else -> Tone.Warning
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.text, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBadge(f.source.label(), tone)
                        StatusBadge("重要度 " + f.importance, Tone.Neutral)
                    }
                    val ref = f.sourceRef
                    if (ref != null) {
                        Text(
                            "依据 " + ref,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (editingId == f.id) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            label = { Text("改成什么", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        )
                        Row {
                            TextButton(onClick = {
                                onCorrect(f.id, editText)
                                editingId = null
                                editText = ""
                            }) { Text("保存更正", style = MaterialTheme.typography.labelLarge) }
                            TextButton(onClick = { editingId = null }) { Text("取消") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editingId = f.id; editText = f.text }) { Text("更正") }
                            TextButton(onClick = { onForget(f.id) }) { Text("忘掉") }
                        }
                    }
                }
            }
        }
        if (retired.isNotEmpty()) {
            item {
                Text(
                    "已退出上下文 " + retired.size + " 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(retired, key = { "retired-" + it.id }) { f ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            f.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (f.status == FactStatus.SUPERSEDED) "已被更正" else "已按要求遗忘",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
    val items = state?.followUps?.sortedWith(
        compareBy({ it.status != FollowUpStatus.PENDING }, { it.dueAtMillis }),
    ) ?: emptyList()

    if (items.isEmpty()) {
        EmptyState(
            title = "没有排着的事",
            hint = "跟他说「十分钟后再聊」，这里就会出现一条。" +
                "\n同一话题再约定一次，旧的会变成已取消。",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }) { f ->
            val (tone, labelText) = when (f.status) {
                FollowUpStatus.PENDING -> Tone.Success to "等着"
                FollowUpStatus.DELIVERING -> Tone.Active to "正在说"
                FollowUpStatus.AWAITING_REPLY -> Tone.Warning to "等你回应"
                FollowUpStatus.DONE -> Tone.Neutral to "已说过"
                FollowUpStatus.CANCELLED -> Tone.Neutral to "已取消"
                FollowUpStatus.EXPIRED -> Tone.Neutral to "已过期"
                FollowUpStatus.FAILED -> Tone.Danger to "失败"
            }
            val dim = f.status != FollowUpStatus.PENDING && f.status != FollowUpStatus.AWAITING_REPLY
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (dim) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.topic,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        StatusBadge(labelText, tone)
                    }
                    MetaRow("时间", fmt.format(Date(f.dueAtMillis)))
                    Text(
                        "依据 " + f.evidence,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (f.attempts > 0) {
                        Text(
                            "已尝试 " + f.attempts + " 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (f.status == FollowUpStatus.PENDING || f.status == FollowUpStatus.AWAITING_REPLY) {
                        Hairline()
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                onReschedule(f.id, System.currentTimeMillis() + 10 * 60_000L)
                            }) { Text("推迟十分钟") }
                            TextButton(onClick = { onCancel(f.id) }) { Text("取消") }
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
    val c = current?.config

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection("模型") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        snapshot.config.describe(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusBadge(
                        if (snapshot.config.usable) "可用" else "未配置",
                        if (snapshot.config.usable) Tone.Success else Tone.Warning,
                    )
                }
                Text(
                    "Key 只存在本机私有目录，不写日志、不进仓库。没配置时只有对话不可用，专注限制照常。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it },
                    label = { Text("接口地址", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型名", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("API Key（留空则不修改）", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            ModelSettings.save(context, endpoint, model, key)
                            key = ""
                            saved = "已保存到本机"
                        },
                        shape = MaterialTheme.shapes.large,
                    ) { Text("保存") }
                    saved?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 12.dp),
                            color = LocalSemantic.current.success,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection("主动交流") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("没有约定也主动闲聊", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (c?.allowSmallTalk == true) "会从已有记忆里找话题；没有真实依据仍然不说话"
                            else "现在只按约定和没说完的事开口",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { CompanionHolder.runtime()?.setAllowSmallTalk(!(c?.allowSmallTalk ?: false)) },
                        shape = MaterialTheme.shapes.extraLarge,
                    ) { Text(if (c?.allowSmallTalk == true) "关掉" else "打开") }
                }
                if (c != null) {
                    Hairline()
                    MetaRow("间隔", (c.gapMinMillis / 60000).toString() + " ~ " + (c.gapMaxMillis / 60000).toString() + " 分钟")
                    MetaRow("每天最多", c.dailyMax.toString() + " 次")
                    MetaRow("免打扰", clockText(c.quietStartMinute) + " ~ " + clockText(c.quietEndMinute))
                    MetaRow("失败重试", (c.retryMillis / 60000).toString() + " 分钟，最多 " + c.maxAttempts.toString() + " 次")
                }
                Text(
                    "这些是保守默认值，还没按你的偏好定过。改这里会立刻生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection("后台的真实边界") {
                Text(
                    "Android 不保证准点：息屏、省电、厂商后台清理都会推迟这句话。" +
                        "「强行停止」之后不会自动恢复，要重新打开 APP。" +
                        "到期判定读磁盘，所以迟到会被纠正，但可能晚几分钟。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun clockText(minuteOfDay: Int): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return (if (h < 10) "0" else "") + h + ":" + (if (m < 10) "0" else "") + m
}
