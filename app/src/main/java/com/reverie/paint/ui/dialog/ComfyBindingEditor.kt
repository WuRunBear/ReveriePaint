/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.comfy.BindNote
import com.reverie.paint.core.comfy.BindNoteKey
import com.reverie.paint.core.comfy.BindRole
import com.reverie.paint.core.comfy.ComfyBinding
import com.reverie.paint.core.comfy.ComfyNode
import com.reverie.paint.core.comfy.EditableBindings
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.painting.panels.ToolFloatChip
import com.reverie.paint.ui.theme.Morandi

/** 绑定角色的中文/英文名 (中英双份在 strings.xml) */
@Composable
internal fun comfyRoleLabel(role: BindRole): String =
    when (role) {
        BindRole.PROMPT -> stringResource(R.string.comfy_bind_role_prompt)
        BindRole.NEGATIVE -> stringResource(R.string.comfy_bind_role_negative)
        BindRole.IMAGE -> stringResource(R.string.comfy_bind_role_image)
        BindRole.OUTPUT -> stringResource(R.string.comfy_bind_role_output)
        BindRole.STEPS -> stringResource(R.string.comfy_bind_role_steps)
        BindRole.CFG -> stringResource(R.string.comfy_bind_role_cfg)
        BindRole.SEED -> stringResource(R.string.comfy_bind_role_seed)
    }

/** 识别提示的语义 key → 文案; **唯一的翻译接缝**, 加 key 时编译器会兜住漏的那支 */
@Composable
internal fun comfyNoteText(note: BindNote): String {
    fun arg(i: Int): String = note.args.getOrElse(i) { "?" }
    return when (note.key) {
        BindNoteKey.IMAGE_NODE_NO_FIELD -> stringResource(R.string.comfy_note_image_no_field, arg(0))
        BindNoteKey.MASK_CONVENTION -> stringResource(R.string.comfy_note_mask_convention, arg(0))
        BindNoteKey.ONLY_ONE_IMAGE_INPUT -> stringResource(R.string.comfy_note_only_one_image)
        BindNoteKey.NO_SAMPLER -> stringResource(R.string.comfy_note_no_sampler)
        BindNoteKey.DENOISE_NOT_LITERAL -> stringResource(R.string.comfy_note_denoise_not_literal, arg(0))
        BindNoteKey.DENOISE_ZERO -> stringResource(R.string.comfy_note_denoise_zero, arg(0))
        BindNoteKey.REFERENCE_WITHOUT_IMAGE_INPUT -> stringResource(R.string.comfy_note_ref_without_image)
        BindNoteKey.SAME_POSITIVE_NEGATIVE -> stringResource(R.string.comfy_note_same_pos_neg, arg(0))
        BindNoteKey.POSITIVE_TARGET_MISSING -> stringResource(R.string.comfy_note_positive_missing, arg(0), arg(1))
        BindNoteKey.POSITIVE_TARGET_NOT_PROMPT ->
            stringResource(R.string.comfy_note_positive_not_prompt, arg(0), arg(1))
        BindNoteKey.NEGATIVE_TARGET_NOT_PROMPT ->
            stringResource(R.string.comfy_note_negative_not_prompt, arg(0), arg(1))
        BindNoteKey.NO_PROMPT_NODE -> stringResource(R.string.comfy_note_no_prompt_node)
        BindNoteKey.NO_OUTPUT_NODE -> stringResource(R.string.comfy_note_no_output)
        BindNoteKey.OUTPUT_LINK_MISSING -> stringResource(R.string.comfy_note_output_link_missing, arg(0), arg(1))
        BindNoteKey.STEPS_FIELD_MISSING -> stringResource(R.string.comfy_note_steps_missing, arg(0))
        BindNoteKey.CFG_FIELD_MISSING -> stringResource(R.string.comfy_note_cfg_missing, arg(0))
        BindNoteKey.SEED_FIELD_MISSING -> stringResource(R.string.comfy_note_seed_missing, arg(0))
    }
}

/** 识别提示列表 —— 空列表时不占位 */
@Composable
internal fun ComfyNotesList(
    notes: List<BindNote>,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.comfy_import_unresolved_title),
            color = Morandi.subText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        notes.forEach { note ->
            Text(
                text = "· ${comfyNoteText(note)}",
                color = Morandi.subText,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

/**
 * 各角色的可编辑绑定行。
 *
 * 每行: `角色 · #id ClassType · 字段 [更改/设置]`。点右侧按钮由调用方弹节点选择器。
 * `Morandi.border` 是 Transparent, 层级一律靠明度差, 不画 1dp 描边。
 *
 * @param onRestoreAuto 非 null 且 [manual] 为 true 时显示「恢复自动识别」
 */
@Composable
internal fun ComfyBindingRows(
    nodes: List<ComfyNode>,
    bindings: EditableBindings,
    manual: Boolean,
    onEditRole: (BindRole) -> Unit,
    onRestoreAuto: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val classById = remember(nodes) { nodes.associate { it.id to it.classType } }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        BindingRow(
            label = comfyRoleLabel(BindRole.PROMPT),
            nodeId = bindings.prompt?.nodeId,
            classType = bindings.prompt?.let { classById[it.nodeId] },
            fieldName = bindings.prompt?.fieldName,
            onEdit = { onEditRole(BindRole.PROMPT) },
        )
        BindingRow(
            label = comfyRoleLabel(BindRole.NEGATIVE),
            nodeId = bindings.negative?.nodeId,
            classType = bindings.negative?.let { classById[it.nodeId] },
            fieldName = bindings.negative?.fieldName,
            onEdit = { onEditRole(BindRole.NEGATIVE) },
        )
        BindingRow(
            label = comfyRoleLabel(BindRole.IMAGE),
            nodeId = bindings.image?.nodeId,
            classType = bindings.image?.let { classById[it.nodeId] },
            fieldName = bindings.image?.fieldName,
            onEdit = { onEditRole(BindRole.IMAGE) },
        )
        BindingRow(
            label = comfyRoleLabel(BindRole.OUTPUT),
            nodeId = bindings.outputNodeId,
            classType = bindings.outputNodeId?.let { classById[it] },
            fieldName = null,
            onEdit = { onEditRole(BindRole.OUTPUT) },
        )
        BindingRow(
            label = comfyRoleLabel(BindRole.STEPS),
            nodeId = bindings.steps?.nodeId,
            classType = bindings.steps?.let { classById[it.nodeId] },
            fieldName = bindings.steps?.fieldName,
            onEdit = { onEditRole(BindRole.STEPS) },
        )
        BindingRow(
            label = comfyRoleLabel(BindRole.CFG),
            nodeId = bindings.cfg?.nodeId,
            classType = bindings.cfg?.let { classById[it.nodeId] },
            fieldName = bindings.cfg?.fieldName,
            onEdit = { onEditRole(BindRole.CFG) },
        )
        BindingRow(
            label = comfyRoleLabel(BindRole.SEED),
            nodeId = bindings.seed?.nodeId,
            classType = bindings.seed?.let { classById[it.nodeId] },
            fieldName = bindings.seed?.fieldName,
            onEdit = { onEditRole(BindRole.SEED) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (manual) Morandi.accent.copy(alpha = 0.16f) else Morandi.panelHi)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(if (manual) R.string.comfy_bind_manual else R.string.comfy_bind_auto),
                    color = if (manual) Morandi.text else Morandi.subText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            if (manual && onRestoreAuto != null) {
                ToolFloatChip(
                    label = stringResource(R.string.comfy_bind_restore_auto),
                    onClick = onRestoreAuto,
                )
            }
        }
    }
}

@Composable
private fun BindingRow(
    label: String,
    nodeId: String?,
    classType: String?,
    fieldName: String?,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = Morandi.subText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(88.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nodeId == null) {
                Text(
                    text = stringResource(R.string.comfy_bind_unset),
                    color = Morandi.subText.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            } else {
                Text(text = "#$nodeId", color = Morandi.subText.copy(alpha = 0.55f), fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = classType ?: "?",
                    color = Morandi.text,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                fieldName?.let {
                    Text(text = " · $it", color = Morandi.subText, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        ToolFloatChip(
            label = stringResource(if (nodeId == null) R.string.comfy_bind_set else R.string.comfy_bind_change),
            onClick = onEdit,
        )
    }
}

// ---------------------------------------------------------------------------
// 节点选择器
// ---------------------------------------------------------------------------

/**
 * 节点选择器。
 *
 * 顶部写明正在为哪个角色选; 节点按 `class_type` 分组, 每行 `#id ClassType · 字段` (`#id` 弱化,
 * 用户认的是 class_type)。点节点: 输出角色直接定下; 其余角色只有唯一候选字段时直接定下,
 * 多个则原地展开字段供选。顶部搜索框同时匹配 class_type 与字段名。
 *
 * [allowClear] 为 true (图片输入 / 负面提示词 / 步数 / CFG / 种子) 时提供「不使用」,
 * 支持这些角色不注入的合法情形。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComfyNodePickerDialog(
    nodes: List<ComfyNode>,
    role: BindRole,
    allowClear: Boolean,
    onPick: (nodeId: String, fieldName: String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var expandedNode by remember { mutableStateOf<String?>(null) }

    val base = remember(nodes, role, showAll) {
        if (showAll) nodes else roleCandidates(nodes, role)
    }
    val filtered = remember(base, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            base
        } else {
            base.filter { node ->
                node.classType.contains(q, ignoreCase = true) ||
                    node.inputNames.any { it.contains(q, ignoreCase = true) }
            }
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.classType } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Morandi.panel,
            shadowElevation = 16.dp,
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(0.94f),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.comfy_bind_picker_title, comfyRoleLabel(role)),
                    color = Morandi.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Morandi.panelHi)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.comfy_bind_picker_search),
                                color = Morandi.subText.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Morandi.text, fontSize = 12.sp),
                            cursorBrush = SolidColor(Morandi.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (allowClear) {
                        ToolFloatChip(
                            label = stringResource(R.string.comfy_bind_picker_clear),
                            onClick = onClear,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolFloatChip(
                        label = stringResource(R.string.comfy_bind_picker_candidates),
                        selected = !showAll,
                        onClick = { showAll = false },
                    )
                    ToolFloatChip(
                        label = stringResource(R.string.comfy_bind_picker_all),
                        selected = showAll,
                        onClick = { showAll = true },
                    )
                }

                if (grouped.isEmpty()) {
                    Text(
                        text =
                            stringResource(
                                if (base.isEmpty()) {
                                    R.string.comfy_bind_picker_no_candidates
                                } else {
                                    R.string.comfy_bind_picker_no_match
                                },
                            ),
                        color = Morandi.subText,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        grouped.forEach { (classType, group) ->
                            item(key = "h_$classType") {
                                Text(
                                    text = classType,
                                    color = Morandi.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                )
                            }
                            items(group, key = { it.id }) { node ->
                                PickerNodeRow(
                                    node = node,
                                    role = role,
                                    expanded = expandedNode == node.id,
                                    onClick = {
                                        val fields = pickerFields(node, role)
                                        when {
                                            role == BindRole.OUTPUT -> onPick(node.id, null)
                                            fields.size == 1 -> onPick(node.id, fields[0])
                                            fields.isEmpty() -> Unit
                                            else ->
                                                expandedNode =
                                                    if (expandedNode == node.id) null else node.id
                                        }
                                    },
                                    onField = { field -> onPick(node.id, field) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerNodeRow(
    node: ComfyNode,
    role: BindRole,
    expanded: Boolean,
    onClick: () -> Unit,
    onField: (String) -> Unit,
) {
    val fields = pickerFields(node, role)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (expanded) Morandi.accent.copy(alpha = 0.10f) else Morandi.panelHi.copy(alpha = 0.55f))
            .noRippleClickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "#${node.id}", color = Morandi.subText.copy(alpha = 0.55f), fontSize = 10.sp)
            Text(
                text = node.classType,
                color = Morandi.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (fields.isNotEmpty()) {
                Text(
                    text = fields.joinToString(" · "),
                    color = Morandi.subText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (expanded && fields.isNotEmpty()) {
            Text(
                text = stringResource(R.string.comfy_bind_picker_field),
                color = Morandi.subText,
                fontSize = 10.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                fields.forEach { field ->
                    ToolFloatChip(label = field, onClick = { onField(field) })
                }
            }
        }
    }
}

/** 筛选模式下的候选节点 (严格匹配角色语义) */
private fun roleCandidates(
    nodes: List<ComfyNode>,
    role: BindRole,
): List<ComfyNode> = when (role) {
    BindRole.OUTPUT -> nodes.filter { isOutputCandidate(it.classType) }
    else -> nodes.filter { roleFieldMatches(it, role).isNotEmpty() }
}

/** 角色语义字段 (严格); 用于筛选 */
private fun roleFieldMatches(
    node: ComfyNode,
    role: BindRole,
): List<String> = when (role) {
    BindRole.PROMPT,
    BindRole.NEGATIVE,
    -> node.inputNames.filter { it.contains("text", true) || it.contains("prompt", true) }

    BindRole.IMAGE -> node.inputNames.filter { it.contains("image", true) }
    BindRole.STEPS -> node.inputNames.filter { it.contains("step", true) }
    BindRole.CFG -> node.inputNames.filter { it.contains("cfg", true) }
    BindRole.SEED -> node.inputNames.filter { it.contains("seed", true) }
    BindRole.OUTPUT -> emptyList()
}

/** 点开某节点后能选的字段: 优先角色语义字段, 没有则放开为该节点的全部输入 (自救) */
private fun pickerFields(
    node: ComfyNode,
    role: BindRole,
): List<String> = when (role) {
    BindRole.OUTPUT -> emptyList()
    else -> roleFieldMatches(node, role).ifEmpty { node.inputNames }
}

private fun isOutputCandidate(classType: String): Boolean =
    classType.contains("Save", ignoreCase = true) ||
        classType.contains("Preview", ignoreCase = true) ||
        classType.startsWith("VAEDecode")

/** 绑定集合里某个角色的展示值; 供调用方按角色更新草稿 (放在这里集中, 免得两处各写一遍) */
internal fun EditableBindings.withRole(
    role: BindRole,
    nodeId: String,
    fieldName: String?,
): EditableBindings {
    val binding = fieldName?.let { ComfyBinding(nodeId, it) }
    return when (role) {
        BindRole.PROMPT -> copy(prompt = binding)
        BindRole.NEGATIVE -> copy(negative = binding)
        BindRole.IMAGE -> copy(image = binding)
        BindRole.OUTPUT -> copy(outputNodeId = nodeId)
        BindRole.STEPS -> copy(steps = binding)
        BindRole.CFG -> copy(cfg = binding)
        BindRole.SEED -> copy(seed = binding)
    }
}

/** 清空某个可省略的角色绑定 (输出 / 提示词这类必填角色不进这里) */
internal fun EditableBindings.clearRole(role: BindRole): EditableBindings = when (role) {
    BindRole.NEGATIVE -> copy(negative = null)
    BindRole.IMAGE -> copy(image = null)
    BindRole.STEPS -> copy(steps = null)
    BindRole.CFG -> copy(cfg = null)
    BindRole.SEED -> copy(seed = null)
    BindRole.PROMPT, BindRole.OUTPUT -> this
}

/** 该角色是否允许「不使用」(可省略的绑定角色) */
internal fun BindRole.isClearable(): Boolean = when (this) {
    BindRole.PROMPT, BindRole.OUTPUT -> false
    BindRole.NEGATIVE, BindRole.IMAGE, BindRole.STEPS, BindRole.CFG, BindRole.SEED -> true
}
