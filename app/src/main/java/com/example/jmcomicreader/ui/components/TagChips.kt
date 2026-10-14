package com.example.jmreader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.jmreader.ui.theme.JmShape

/**
 * v6.0.0 MD3 标签系统：
 *  - 同义词组：Surface(组外壳圆角矩形) + 组内多颗 FilterChip，形成「几何图」
 *  - 单独标签：直接使用 Material3 FilterChip
 *  - 选中态：MD3 规范 — leadingIcon ✓ + elevatedContainerColor + 描边
 *  - 未选中态：tonal surface + 弱描边，与 MD3 FilterChip 默认规范一致
 *  - 整体 FlowRow，间距 8dp / 6dp 更一致
 *
 *  保留原有调用接口：tags / groups / onTagClick / isSelected / label，
 *  严禁修改上层业务逻辑与数据结构。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SynonymGroupChips(
    tags: List<String>,
    groups: Map<String, List<String>>,
    onTagClick: (String) -> Unit,
    isSelected: (String) -> Boolean = { false },
    label: @Composable (String) -> Unit = { Text(it) },
    modifier: Modifier = Modifier
) {
    val aliasToCanon = remember(groups) {
        groups.flatMap { (canon, aliases) -> aliases.map { it to canon } }.toMap()
    }
    val buckets = remember(tags, aliasToCanon) {
        LinkedHashMap<String, MutableList<String>>().also { b ->
            for (t in tags) {
                val canon = aliasToCanon[t] ?: t
                b.getOrPut(canon) { mutableListOf() }.add(t)
            }
        }
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for ((_, members) in buckets) {
            if (members.size >= 2) {
                // 同义词组：MD3 tonal Surface 外壳 + 组内多颗 FilterChip
                val anySelected = members.any { isSelected(it) }
                Surface(
                    shape = JmShape.cornerBadge,
                    color = if (anySelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    border = if (anySelected) BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    ) else null,
                    tonalElevation = 1.dp
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(
                            horizontal = 6.dp,
                            vertical = 4.dp
                        )
                    ) {
                        for (m in members) {
                            CompactFilterChip(
                                selected = isSelected(m),
                                onClick = { onTagClick(m) },
                                label = { label(m) }
                            )
                        }
                    }
                }
            } else {
                val only = members.first()
                CompactFilterChip(
                    selected = isSelected(only),
                    onClick = { onTagClick(only) },
                    label = { label(only) }
                )
            }
        }
    }
}

/**
 * 紧凑版 MD3 FilterChip：高度收窄，适合标签库 / 详情页密集展示。
 * 选中态 = elevated + leading checkmark；未选中 = outlined + tonal。
 */
@Composable
private fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(vertical = 1.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) { label() }
            }
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else null,
        shape = JmShape.pill,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.primary,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        elevation = if (selected) FilterChipDefaults.elevatedFilterChipElevation(
            elevation = 1.dp
        ) else FilterChipDefaults.filterChipElevation()
    )
}

/**
 * 单个 SuggestionChip（不带选中态）：详情页「作者」「更多同类」等展示性标签。
 */
@Composable
fun TagSuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SuggestionChip(
        onClick = onClick,
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        },
        shape = JmShape.pill,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(
            enabled = true,
            borderColor = Color.Transparent,
            borderWidth = 0.dp
        ),
        modifier = modifier
    )
}

/**
 * 单个 AssistChip（说明/辅助标签）：章节数、页数等元信息展示。
 */
@Composable
fun TagAssistChip(
    icon: (@Composable () -> Unit)? = null,
    label: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onClick ?: {},
        enabled = onClick != null,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        },
        leadingIcon = icon,
        shape = JmShape.pill,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconContentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = onClick != null,
            borderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            borderWidth = 0.dp
        ),
        modifier = modifier
    )
}
