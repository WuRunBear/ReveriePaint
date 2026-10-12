/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.ComfyWorkflowEdit
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.comfy.BindRole
import com.reverie.paint.core.comfy.EditableBindings
import com.reverie.paint.core.comfy.StoredWorkflow
import com.reverie.paint.core.loadComfyWorkflowEdit
import com.reverie.paint.core.saveComfyWorkflowBinding
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi

/**
 * 已导入工作流的绑定详情。
 *
 * 与导入预览共用 [ComfyBindingRows]: 同样四行, 点「更改」弹 [ComfyNodePickerDialog]。
 * 每次改动**立刻落盘** (生成链路读的就是这份绑定), 不是等一个「保存」按钮 ——
 * 节点绑定是低频操作, 少一步就少一次"改了没生效"的困惑。
 */
@Composable
internal fun ComfyWorkflowDetailDialog(
    vm: PaintViewModel,
    workflow: StoredWorkflow,
    onDismiss: () -> Unit,
) {
    var edit by remember(workflow.id) { mutableStateOf<ComfyWorkflowEdit?>(null) }
    var bindings by remember(workflow.id) { mutableStateOf<EditableBindings?>(null) }
    var attempted by remember(workflow.id) { mutableStateOf(false) }
    var picking by remember(workflow.id) { mutableStateOf<BindRole?>(null) }

    LaunchedEffect(workflow.id) {
        val loaded = vm.loadComfyWorkflowEdit(workflow.id)
        edit = loaded
        bindings = loaded?.current
        attempted = true
    }

    val auto = edit?.auto
    val current = bindings
    val manual = auto != null && current != null && !current.sameRolesAs(auto)

    fun commit(next: EditableBindings) {
        bindings = next
        val base = auto ?: return
        vm.saveComfyWorkflowBinding(workflow.id, next, !next.sameRolesAs(base))
    }

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
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.comfy_bind_detail_title),
                    color = Morandi.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = workflow.name,
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    maxLines = 1,
                )

                val e = edit
                val b = current
                if (e != null && b != null) {
                    ComfyBindingRows(
                        nodes = e.nodes,
                        bindings = b,
                        manual = manual,
                        onEditRole = { role -> picking = role },
                        onRestoreAuto = { commit(e.auto) },
                    )
                    if (!b.usable) {
                        Text(
                            text = stringResource(R.string.comfy_import_not_usable),
                            color = Morandi.error,
                            fontSize = 11.sp,
                        )
                    }
                    ComfyNotesList(notes = e.autoNotes)
                } else if (attempted) {
                    Text(
                        text = stringResource(R.string.comfy_bind_load_failed),
                        color = Morandi.error,
                        fontSize = 11.sp,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(Modifier.weight(1f))
                    ReTextButton(
                        text = stringResource(R.string.common_close),
                        textColor = Morandi.subText,
                        containerColor = Color.Transparent,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }

    val pickRole = picking
    val e = edit
    if (pickRole != null && e != null) {
        ComfyNodePickerDialog(
            nodes = e.nodes,
            role = pickRole,
            allowClear = pickRole.isClearable(),
            onPick = { nodeId, field -> commit(bindings!!.withRole(pickRole, nodeId, field)); picking = null },
            onClear = {
                commit(bindings!!.clearRole(pickRole))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}
