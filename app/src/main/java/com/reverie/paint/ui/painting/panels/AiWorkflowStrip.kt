/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.openComfyWorkflowDetail
import com.reverie.paint.core.openComfyWorkflowImport
import com.reverie.paint.core.requestDeleteComfyWorkflow
import com.reverie.paint.core.selectComfyWorkflow
import com.reverie.paint.ui.theme.Morandi

/**
 * AI 面板的工作流横条。
 *
 * 显示已导入的工作流, 点 chip 切换当前; 右侧是导入入口, 当前项左侧露一个删除入口。
 * **还没导入任何工作流时**不摆生成控件 (调用方 [AiGenerateIntent] 会 return), 这里给出
 * 一句引导 + 导入按钮 —— 不允许"看起来能生成却什么都不发生"。
 *
 * 用横向滚动而不是删减: 工作流数量由用户决定, 没有"合理的最大条数"。
 */
@Composable
internal fun AiWorkflowStrip(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val state = vm.comfyWorkflows
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_layers),
                contentDescription = null,
                tint = Morandi.subText,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = stringResource(R.string.ai_workflow_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Morandi.text,
            )
            Spacer(Modifier.weight(1f))
            state.current?.let { current ->
                ToolActionButton(
                    iconRes = R.drawable.ic_sliders,
                    label = stringResource(R.string.ai_workflow_bind),
                    active = state.detailTarget?.id == current.id,
                    onClick = { vm.openComfyWorkflowDetail(current) },
                )
                ToolActionButton(
                    iconRes = R.drawable.ic_trash,
                    label = stringResource(R.string.ai_workflow_manage_delete),
                    danger = true,
                    onClick = { vm.requestDeleteComfyWorkflow(current) },
                )
            }
            ToolActionButton(
                iconRes = R.drawable.ic_import,
                label = stringResource(R.string.ai_workflow_import),
                active = state.importOpen,
                onClick = { vm.openComfyWorkflowImport() },
            )
        }

        if (state.workflows.isEmpty()) {
            Text(
                text = stringResource(R.string.ai_workflow_empty),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Morandi.text,
            )
            Text(
                text = stringResource(R.string.ai_workflow_empty_desc),
                fontSize = 11.sp,
                color = Morandi.subText.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.workflows.forEach { workflow ->
                    ToolFloatChip(
                        label = workflow.name,
                        selected = workflow.id == state.currentId,
                        onClick = { vm.selectComfyWorkflow(workflow.id) },
                    )
                }
            }
        }
    }
}
