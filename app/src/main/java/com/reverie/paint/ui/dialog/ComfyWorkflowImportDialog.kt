/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.ComfyImportParse
import com.reverie.paint.core.ComfyImportPreview
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PendingComfyImport
import com.reverie.paint.core.buildComfyImportPreview
import com.reverie.paint.core.comfy.BindRole
import com.reverie.paint.core.comfy.WorkflowKind
import com.reverie.paint.core.commitComfyImports
import com.reverie.paint.core.queryFileName
import com.reverie.paint.core.readComfyJsonText
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.painting.panels.ToolFloatChip
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.launch

/** 一条待导入内容的状态 (文件名 + 正文 + 解析结果) */
private data class ImportEntry(
    val fileName: String,
    val json: String,
    val parse: ComfyImportParse,
)

/** 正在为哪条待导入项的哪个角色选节点 */
private data class PickTarget(val entryIndex: Int, val role: BindRole)

/**
 * ComfyUI 工作流导入对话框。
 *
 * 两条输入路径共用一个对话框: 文件选择器 (API 格式 workflow.json) 与粘贴 JSON 文本。
 * 解析成功后**把自动绑定的结果摆在用户面前** —— 认出了哪些 (正向提示词 / 输出节点…)、
 * 哪些没认出来 ([ComfyImportParse]) —— 而不是静默导入。
 *
 * 文件读取走 [PaintViewModel.readComfyJsonText] (Dispatchers.IO), 不挂渲染线程。
 */
@Composable
fun ComfyWorkflowImportDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pasteMode by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ImportEntry>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var readFailed by remember { mutableStateOf(false) }
    var nameMissing by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf<PickTarget?>(null) }

    fun updateEntry(index: Int, preview: ComfyImportPreview) {
        val list = entries.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(parse = ComfyImportParse.Ok(preview))
        entries = list
    }

    fun applyPick(index: Int, role: BindRole, nodeId: String, fieldName: String?) {
        val preview = (entries.getOrNull(index)?.parse as? ComfyImportParse.Ok)?.preview ?: return
        val next = preview.bindings.withRole(role, nodeId, fieldName)
        updateEntry(index, preview.copy(bindings = next, manual = !next.sameRolesAs(preview.auto)))
    }

    fun applyClear(index: Int, role: BindRole) {
        if (!role.isClearable()) return
        val preview = (entries.getOrNull(index)?.parse as? ComfyImportParse.Ok)?.preview ?: return
        val next = preview.bindings.clearRole(role)
        updateEntry(index, preview.copy(bindings = next, manual = !next.sameRolesAs(preview.auto)))
    }

    fun restoreAuto(index: Int) {
        val preview = (entries.getOrNull(index)?.parse as? ComfyImportParse.Ok)?.preview ?: return
        updateEntry(index, preview.copy(bindings = preview.auto, manual = false))
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                readFailed = false
                val loaded =
                    uris.mapNotNull { uri ->
                        val text = vm.readComfyJsonText(uri)
                        if (text == null) {
                            readFailed = true
                            null
                        } else {
                            val fileName =
                                queryFileName(context, uri)
                                    ?: uri.lastPathSegment?.substringAfterLast('/')
                                    ?: "workflow.json"
                            ImportEntry(fileName, text, buildComfyImportPreview(text))
                        }
                    }
                entries = loaded
                if (loaded.size == 1) name = fileBaseName(loaded.first().fileName)
                busy = false
            }
        }

    val singleEntry = entries.size == 1
    val okEntries = entries.filter { it.parse is ComfyImportParse.Ok }
    val canImport = !busy && okEntries.isNotEmpty() && (!singleEntry || name.isNotBlank())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Morandi.panel,
            shadowElevation = 16.dp,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.92f),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- 标题 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Morandi.accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_magicwand),
                            contentDescription = null,
                            tint = Morandi.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.comfy_import_title),
                        color = Morandi.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = stringResource(R.string.comfy_import_desc),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ---- 输入方式 ----
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolFloatChip(
                        label = stringResource(R.string.comfy_import_pick_file),
                        selected = !pasteMode,
                        onClick = {
                            pasteMode = false
                            picker.launch(IMPORT_MIME_TYPES)
                        },
                    )
                    ToolFloatChip(
                        label = stringResource(R.string.comfy_import_paste),
                        selected = pasteMode,
                        onClick = { pasteMode = true },
                    )
                }

                if (pasteMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .heightIn(min = 110.dp, max = 190.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (pasteText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.comfy_import_paste_hint),
                                color = Morandi.subText.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                            )
                        }
                        BasicTextField(
                            value = pasteText,
                            onValueChange = { pasteText = it },
                            textStyle = TextStyle(color = Morandi.text, fontSize = 12.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Morandi.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row {
                        ToolFloatChip(
                            label = stringResource(R.string.comfy_import_parse),
                            onClick = {
                                val text = pasteText.trim()
                                entries =
                                    if (text.isEmpty()) {
                                        emptyList()
                                    } else {
                                        listOf(ImportEntry("", text, buildComfyImportPreview(text)))
                                    }
                            },
                        )
                    }
                }

                // ---- 预览 ----
                if (entries.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.comfy_import_preview_title),
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        entries.forEachIndexed { index, entry ->
                            ImportEntryCard(
                                entry = entry,
                                onEditRole = { role -> pickTarget = PickTarget(index, role) },
                                onRestoreAuto = { restoreAuto(index) },
                            )
                        }
                    }
                }

                if (readFailed) {
                    Text(
                        text = stringResource(R.string.comfy_import_read_failed),
                        color = Morandi.error,
                        fontSize = 11.sp,
                    )
                }

                // ---- 命名 ----
                if (singleEntry) {
                    Text(
                        text = stringResource(R.string.comfy_import_name),
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                text = stringResource(R.string.comfy_import_name_hint),
                                color = Morandi.subText.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                            )
                        }
                        BasicTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                nameMissing = false
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = Morandi.text, fontSize = 13.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Morandi.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (nameMissing) {
                        Text(
                            text = stringResource(R.string.comfy_import_error_name),
                            color = Morandi.error,
                            fontSize = 11.sp,
                        )
                    }
                } else if (entries.size > 1) {
                    Text(
                        text = stringResource(R.string.comfy_import_multi_hint, entries.size),
                        color = Morandi.subText,
                        fontSize = 11.sp,
                    )
                }

                // ---- 操作 ----
                ReTextButton(
                    text = stringResource(R.string.comfy_import_btn, okEntries.size),
                    enabled = canImport,
                    onClick = {
                        if (singleEntry && name.isBlank()) {
                            nameMissing = true
                            return@ReTextButton
                        }
                        val items =
                            if (singleEntry) {
                                val entry = okEntries.first()
                                listOf(PendingComfyImport(name.trim(), entry.json, (entry.parse as ComfyImportParse.Ok).preview))
                            } else {
                                okEntries.map { entry ->
                                    PendingComfyImport(
                                        name = fileBaseName(entry.fileName),
                                        json = entry.json,
                                        preview = (entry.parse as ComfyImportParse.Ok).preview,
                                    )
                                }
                            }
                        if (items.isEmpty()) return@ReTextButton
                        scope.launch {
                            vm.commitComfyImports(items)
                            Toast.makeText(
                                context,
                                context.getString(R.string.comfy_import_success, items.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = R.drawable.ic_import,
                    primary = true,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )

                ReTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Color.Transparent,
                    contentColor = Morandi.subText,
                    fontSize = 13.sp,
                )
            }
        }
    }

    // 节点选择器挂在对话框之外: 导入预览里的每一条都能改绑定
    pickTarget?.let { target ->
        val preview = (entries.getOrNull(target.entryIndex)?.parse as? ComfyImportParse.Ok)?.preview
        if (preview == null) {
            pickTarget = null
        } else {
            ComfyNodePickerDialog(
                nodes = preview.nodes,
                role = target.role,
                allowClear = target.role.isClearable(),
                onPick = { nodeId, field ->
                    applyPick(target.entryIndex, target.role, nodeId, field)
                    pickTarget = null
                },
                onClear = {
                    applyClear(target.entryIndex, target.role)
                    pickTarget = null
                },
                onDismiss = { pickTarget = null },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 单条预览卡片
// ---------------------------------------------------------------------------

@Composable
private fun ImportEntryCard(
    entry: ImportEntry,
    onEditRole: (BindRole) -> Unit,
    onRestoreAuto: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Morandi.panelHi.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (entry.fileName.isNotBlank()) {
            Text(
                text = entry.fileName,
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (val parse = entry.parse) {
            is ComfyImportParse.NotApiFormat -> PreviewError(stringResource(R.string.comfy_import_error_not_api))
            is ComfyImportParse.Invalid ->
                PreviewError(stringResource(R.string.comfy_import_error_invalid, parse.reason))
            is ComfyImportParse.Ok ->
                PreviewOk(
                    preview = parse.preview,
                    onEditRole = onEditRole,
                    onRestoreAuto = onRestoreAuto,
                )
        }
    }
}

@Composable
private fun PreviewOk(
    preview: ComfyImportPreview,
    onEditRole: (BindRole) -> Unit,
    onRestoreAuto: () -> Unit,
) {
    Text(
        text = comfyKindLabel(preview.kind),
        color = Morandi.accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
    ComfyBindingRows(
        nodes = preview.nodes,
        bindings = preview.bindings,
        manual = preview.manual,
        onEditRole = onEditRole,
        onRestoreAuto = onRestoreAuto,
    )
    if (!preview.usable) {
        PreviewError(stringResource(R.string.comfy_import_not_usable))
    }
    ComfyNotesList(notes = preview.unresolved)
}

@Composable
private fun PreviewError(text: String) {
    Text(
        text = text,
        color = Morandi.error,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

@Composable
private fun comfyKindLabel(kind: WorkflowKind): String =
    when (kind) {
        WorkflowKind.TXT2IMG -> stringResource(R.string.comfy_kind_txt2img)
        WorkflowKind.IMG2IMG -> stringResource(R.string.comfy_kind_img2img)
        WorkflowKind.INPAINT -> stringResource(R.string.comfy_kind_inpaint)
        WorkflowKind.UNKNOWN -> stringResource(R.string.comfy_kind_unknown)
    }

private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "*/*")

/** 去掉目录与扩展名的文件名, 用作默认工作流名 */
private fun fileBaseName(fileName: String): String {
    val last = fileName.substringAfterLast('/').substringAfterLast('\\')
    return last.substringBeforeLast('.').trim().ifBlank { last.ifBlank { "workflow" } }
}
