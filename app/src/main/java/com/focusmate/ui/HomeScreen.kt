package com.focusmate.ui

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
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
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopSwitcher(selected = tab, onSelect = { tab = it })
        if (tab == 0) FocusTab(runtime, transientMessage) else CompanionScreen()
    }
}

/**
 * 顶部两段式切换。刻意不用 TabRow：
 * Material 的 TabRow 会带一条通栏底线和等宽暗示，在这个两页的场景里显得笨重。
 * 这里做成"胶囊里的两段"，选中态用实心，视觉上更轻。
 */
@Composable
private fun TopSwitcher(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("专注", "伙伴").forEachIndexed { index, label ->
            val active = selected == index
            Box(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        if (active) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun FocusTab(runtime: GuardRuntime, transientMessage: MutableState<String>) {
    val context = LocalContext.current
    var session by remember { mutableStateOf(runtime.session()) }
    var overlayOk by remember { mutableStateOf(Permissions.hasOverlay(context)) }
    var a11yOk by remember { mutableStateOf(Permissions.isAccessibilityEnabled(context)) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val targets = remember { detectTargets(context) }
    var selectedTarget by remember { mutableStateOf(targets.firstOrNull()?.packageName ?: Presets.GEMINI_CANDIDATES.first()) }

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
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text("专注", style = MaterialTheme.typography.headlineMedium)
            Text(
                "限制真实生效，到点真的恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            transientMessage.value.takeIf { it.isNotBlank() }?.let {
                NoticeCard(it, Tone.Neutral)
            }
            errorText?.let {
                NoticeCard("没能开始：" + it, Tone.Danger)
            }

            val s = session
            if (s != null && SessionStates.isActive(s.state)) {
                ActiveSessionCard(s, context, onEnd = { runtime.endByUser("界面主动结束") })
            } else {
                if (!overlayOk || !a11yOk) {
                    PermissionCard(overlayOk, a11yOk, context)
                } else {
                    ReadyBanner()
                }
                StartCard(
                    context = context,
                    targets = targets,
                    selected = selectedTarget,
                    onSelect = { selectedTarget = it },
                    onDock = {
                        errorText = null
                        runtime.startSession(
                            Presets.geminiDock(
                                geminiPackage = selectedTarget,
                                nowMillis = System.currentTimeMillis(),
                                enforcement = Enforcement.OVERLAY_PLUS_A11Y,
                            ),
                        ) { errorText = it }
                        session = runtime.session()
                    },
                    onSingle = {
                        errorText = null
                        runtime.startSession(
                            Presets.singleApp(
                                targetPackage = selectedTarget,
                                durationMillis = 5L * 60L * 1000L,
                                nowMillis = System.currentTimeMillis(),
                                enforcement = Enforcement.OVERLAY_PLUS_A11Y,
                            ),
                        ) { errorText = it }
                        session = runtime.session()
                    },
                )
            }

            CapabilityCard(runtime.capabilityNotes())

            val lastFailure = s?.failure
            if (lastFailure != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(LocalSemantic.current.danger))
                            Text(
                                "上次失败",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 8.dp).weight(1f),
                            )
                            TextButton(onClick = { runtime.clearFailureRecord() }) {
                                Text("知道了", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            lastFailure.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            lastFailure.code.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 统一的通知条。四种语气用同一套结构，只换颜色，避免页面里出现三种"提示样式"。 */
@Composable
private fun NoticeCard(text: String, tone: Tone) {
    val semantic = LocalSemantic.current
    val accent = when (tone) {
        Tone.Danger -> semantic.danger
        Tone.Warning -> semantic.warning
        Tone.Success -> semantic.success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(accent))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PermissionCard(overlayOk: Boolean, a11yOk: Boolean, context: Context) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("还差两步", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusBadge("未就绪", Tone.Warning)
            }
            Text(
                "这两项没开，就没法识别前台应用、也没法拦住别的应用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PermissionRow(
                title = "显示在其他应用上层",
                detail = "用于显示拦截层",
                granted = overlayOk,
                onGrant = { context.startActivity(Permissions.overlaySettingsIntent(context)) },
            )
            PermissionRow(
                title = "无障碍服务",
                detail = "用于识别当前前台",
                granted = a11yOk,
                onGrant = { context.startActivity(Permissions.accessibilitySettingsIntent()) },
            )
        }
    }
}

/**
 * 权限齐全时的确认条。
 * 之前这里什么都不显示，用户看不出"已经准备好了"还是"应用坏了"——
 * 一个只有出错才给反馈的界面，会让人反复去设置里确认。
 */
@Composable
private fun ReadyBanner() {
    val semantic = LocalSemantic.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = semantic.successContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(semantic.success))
            Text(
                "权限已就绪",
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.success,
                modifier = Modifier.weight(1f),
            )
            Text(
                "可以开始了",
                style = MaterialTheme.typography.labelSmall,
                color = semantic.success,
            )
        }
    }
}

@Composable
private fun PermissionRow(title: String, detail: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            StatusBadge("已开启", Tone.Success)
        } else {
            OutlinedButton(onClick = onGrant, shape = MaterialTheme.shapes.extraLarge) {
                Text("去开启", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StartCard(
    context: Context,
    targets: List<TargetApp>,
    selected: String,
    onSelect: (String) -> Unit,
    onDock: () -> Unit,
    onSingle: () -> Unit,
) {
    val label = labelOf(context, selected)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("沉浸舱", style = MaterialTheme.typography.titleLarge)
            Text(
                "接下来十分钟只留 " + label + "、通话和微信，其它应用会被拦住。到点自动解除。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 目标应用可换。只列出这台设备上真的装了的，不列出候选清单里不存在的包。
            if (targets.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "选择目标",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targets.forEach { t ->
                            val active = t.packageName == selected
                            Box(
                                Modifier
                                    .clip(MaterialTheme.shapes.extraLarge)
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .clickable { onSelect(t.packageName) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    t.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onDock,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text("进入沉浸舱 · 十分钟", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onSingle,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text("先用 5 分钟试一次", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "第二种会限制同一个应用，但只有 5 分钟，用来验证限制是否真的生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 专注中的主卡。
 *
 * 之前只有一行"到期 22:15"——这是静态文字，用户想知道的其实是"还剩多久"。
 * 现在每秒重算一次剩余时间，并给一条进度条：
 * 专注类应用最核心的信息就是这两样，缺了它界面再好看也没用。
 */
@Composable
private fun ActiveSessionCard(session: FocusSession, context: Context, onEnd: () -> Unit) {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val semantic = LocalSemantic.current
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(session.id) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L)
        }
    }

    val total = (session.expiresAtMillis - session.startedAtMillis).coerceAtLeast(1L)
    val remaining = (session.expiresAtMillis - nowMillis).coerceAtLeast(0L)
    val progress = (1f - remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val actionable = SessionStates.isActive(session.state)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stateLabel(session.state),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    when (session.state) {
                        SessionState.BLOCKING -> "已拦截"
                        SessionState.AWAY_ALLOWED -> "放行中"
                        SessionState.FAILED -> "失败"
                        else -> "守护中"
                    },
                    when (session.state) {
                        SessionState.BLOCKING -> Tone.Danger
                        SessionState.AWAY_ALLOWED -> Tone.Warning
                        SessionState.FAILED -> Tone.Danger
                        else -> Tone.Success
                    },
                )
            }
            if (actionable) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            formatRemaining(remaining),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "还剩",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 进度条：不用 Material 的 LinearProgressIndicator，
                    // 它默认会带动画且高度固定，这里要一条更细、更稳的横线。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(6.dp)
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
            Hairline()
            MetaRow("目标", labelOf(context, session.targetPackage))
            MetaRow("到期", fmt.format(Date(session.expiresAtMillis)))
            if (session.exceptions.isNotEmpty()) {
                MetaRow("同时放行", session.exceptions.joinToString("、") { it.label })
            }
            MetaRow("规则版本", "v" + session.ruleVersion + " · 会话 " + session.id.take(6))
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onEnd,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = semantic.danger),
            ) {
                Text("结束专注", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 能力边界。
 * 这些字以前是一整块密集小字，读起来像免责声明。现在拆成"可做/不做"两组，
 * 至少让人一眼知道哪些是真的、哪些别期待。
 */
@Composable
private fun CapabilityCard(notes: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("能做到 / 做不到", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                notes.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "·",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    "点开看具体边界。这里不写漂亮话，避免你以为它比实际更强。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 剩余时间。超过一小时给 "1:05:30"，否则 "09:12"。 */
private fun formatRemaining(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val sec = totalSeconds % 60
    fun pad(v: Long) = if (v < 10) "0" + v else v.toString()
    return if (h > 0) h.toString() + ":" + pad(m) + ":" + pad(sec) else pad(m) + ":" + pad(sec)
}

private fun stateLabel(s: SessionState): String = when (s) {
    SessionState.PREPARING -> "正在进入目标应用"
    SessionState.ENFORCING -> "专注进行中"
    SessionState.AWAY_ALLOWED -> "在例外应用里"
    SessionState.BLOCKING -> "已拦住"
    SessionState.RELEASING -> "正在解除"
    SessionState.COMPLETED -> "已结束并恢复"
    SessionState.USER_ENDED -> "已由你结束"
    SessionState.FAILED -> "执行失败"
}

/** 设备上真实存在的候选目标。用来替代"写死只认 Gemini"——不是每台机器都装了它。 */
data class TargetApp(val packageName: String, val label: String)

private fun detectTargets(context: Context): List<TargetApp> {
    val found = Presets.GEMINI_CANDIDATES.mapNotNull { pkg ->
        if (!isInstalled(context, pkg)) null else TargetApp(pkg, labelOf(context, pkg))
    }.distinctBy { it.label }
    return found.ifEmpty { listOf(TargetApp(Presets.GEMINI_CANDIDATES.first(), "Gemini")) }
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
