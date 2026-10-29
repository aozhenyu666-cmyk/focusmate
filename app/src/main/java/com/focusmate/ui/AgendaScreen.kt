package com.focusmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusmate.agent.Agenda
import com.focusmate.agent.AgendaItem
import com.focusmate.agent.AgendaKind
import com.focusmate.agent.AgendaStatus
import com.focusmate.agent.FactSource
import com.focusmate.runtime.AgentRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgendaScreen() {
    val state by AgentRuntime.state.collectAsState()
    val status by AgentRuntime.status.collectAsState()
    val name = AgentRuntime.prefs.name
    val now = System.currentTimeMillis()
    val quietUntil = state.proactive.quietUntilMillis

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("安排与记忆", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Section("$name 的主动状态") {
            val next = AgentRuntime.nextWakeAt()
            Line("主动程度：${AgentRuntime.prefs.proactivity.label}（每天最多主动找你 ${AgentRuntime.prefs.proactivity.dailyCap} 次，提醒和回访不算）")
            Line("下一次醒来：" + (next?.let { full(it) } ?: "没有安排"))
            Line("现在能不能主动开口：" + (AgentRuntime.spontaneousBlocker() ?: "可以"))
            Line("连续没回应：${state.proactive.unanswered} 条（越多它越克制，你一回话就复原）")
            if (status.lastOkAtMillis > 0) Line("模型最近一次成功：${full(status.lastOkAtMillis)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { AgentRuntime.chatNow() }, enabled = !status.thinking) { Text("现在聊一句") }
                if (quietUntil > now) {
                    OutlinedButton(onClick = { AgentRuntime.endQuiet() }) { Text("取消安静（到 ${clock(quietUntil)}）") }
                } else {
                    OutlinedButton(onClick = { AgentRuntime.quietFor(2 * 60 * 60_000L) }) { Text("安静 2 小时") }
                }
            }
        }

        val pending = Agenda.pending(state.agenda)
        Section("待办（${pending.size}）") {
            if (pending.isEmpty()) Line("没有。跟 $name 说「X 点提醒我…」，或者聊聊你接下来要做的事。")
            pending.forEach { AgendaRow(it, cancellable = true) }
        }

        val done = state.agenda.filter { it.status != AgendaStatus.PENDING }
            .sortedByDescending { it.doneAtMillis ?: it.createdAtMillis }.take(12)
        if (done.isNotEmpty()) {
            Section("最近处理过") { done.forEach { AgendaRow(it, cancellable = false) } }
        }

        Section("$name 记得的事（${state.facts.size}）") {
            if (state.facts.isEmpty()) Line("还没有。聊天中提到的稳定信息（目标、计划、偏好）会被记在这里。")
            state.facts.sortedByDescending { it.createdAtMillis }.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.text, fontSize = 14.sp)
                        Text(
                            (if (f.source == FactSource.USER_SAID) "你说的" else "它推断的，可能不对") + " · " + full(f.createdAtMillis),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { AgentRuntime.deleteFact(f.id) }) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(item: AgendaItem, cancellable: Boolean) {
    val kind = when (item.kind) {
        AgendaKind.REMINDER -> "提醒"
        AgendaKind.FOLLOW_UP -> "回访"
        AgendaKind.FOCUS_REVIEW -> "复盘"
    }
    val st = when (item.status) {
        AgendaStatus.PENDING -> ""
        AgendaStatus.DELIVERED -> " · 已送达"
        AgendaStatus.CANCELLED -> " · 已取消"
        AgendaStatus.DROPPED -> " · 没有发"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$kind · ${full(item.dueAtMillis)}$st", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Text(item.text, fontSize = 14.sp)
            if (item.reason.isNotBlank()) Text("因为：${item.reason}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.note?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (cancellable) TextButton(onClick = { AgentRuntime.cancelAgenda(item.id) }) { Text("取消") }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            content()
        }
    }
}

@Composable
internal fun Line(text: String) {
    Text(text, fontSize = 13.sp)
}

private fun full(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
private fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
