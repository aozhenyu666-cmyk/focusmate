package com.focusmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 空状态。必须写"下一步做什么"，不能只写"暂无数据"——空屏是最容易让人放弃的地方。 */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("·", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** 状态徽章：一个圆点 + 文本。比整块染色克制，也不会把卡片压得太重。 */
@Composable
fun StatusBadge(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemantic.current
    val (fg, bg) = when (tone) {
        Tone.Success -> semantic.success to semantic.successContainer
        Tone.Warning -> semantic.warning to semantic.warningContainer
        Tone.Danger -> semantic.danger to semantic.dangerContainer
        Tone.Active -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant to semantic.neutralContainer
    }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

enum class Tone { Success, Warning, Danger, Active, Neutral }

/** 细横线。用极低对比度的线分隔区块，而不是靠阴影，避免页面显得脏。 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .size(1.dp)
            .background(LocalSemantic.current.hairline),
    )
}

/** 键值行：左侧标签固定弱化，右侧内容右对齐。用于"到期时间""规则版本"这类元信息。 */
@Composable
fun MetaRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}
