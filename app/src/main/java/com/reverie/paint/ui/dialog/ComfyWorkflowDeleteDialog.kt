/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi

/**
 * 删除已导入工作流的二次确认。
 *
 * 删除会连正文文件一起清掉, 因此不静默 —— 用户需要时可重新导入, 但要先说清楚。
 */
@Composable
fun ComfyWorkflowDeleteDialog(
    workflowName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_workflow_delete_title), color = Morandi.text) },
        text = { Text(stringResource(R.string.ai_workflow_delete_desc, workflowName), color = Morandi.subText) },
        confirmButton = {
            ReTextButton(
                text = stringResource(R.string.common_delete),
                onClick = onConfirm,
                containerColor = Morandi.error,
                contentColor = Color.White,
            )
        },
        dismissButton = {
            ReTextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                textColor = Morandi.subText,
            )
        },
        containerColor = Morandi.panelHi,
    )
}
