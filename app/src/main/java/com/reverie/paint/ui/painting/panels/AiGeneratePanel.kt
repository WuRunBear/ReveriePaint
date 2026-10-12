/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.AiPanelForm
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.aiButtonLabel
import com.reverie.paint.core.aiPanelVisible
import com.reverie.paint.core.applyAiResult
import com.reverie.paint.core.cancelAiGeneration
import com.reverie.paint.core.clearSelectionAction
import com.reverie.paint.core.copyOrCutSelection
import com.reverie.paint.core.dismissAi
import com.reverie.paint.core.invertSelectionAction
import com.reverie.paint.core.publishAiPanelBounds
import com.reverie.paint.core.regenerateAi
import com.reverie.paint.core.refreshAiSelectionBounds
import com.reverie.paint.core.rerollAiSeed
import com.reverie.paint.core.submitAiGeneration
import com.reverie.paint.core.comfy.AiButtonResolver
import com.reverie.paint.core.comfy.AiGenerateMode
import com.reverie.paint.core.comfy.AiSourceKind
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/**
 * 画布情境 AI 面板 (AI-PAINT-UX.md §4.2 / §4.3)。
 *
 * 挂在底部中央 —— 与既有 11 个 BottomCenter 面板同一个坑位, 但**与 `SelectionFloatPanel`
 * 互斥合流**: 选了 AI 工具后 `tool` 变了, 选区浮层按自己的条件自动隐藏, 一个条件都不用改。
 * 选区操作 (反选 / 复制 / 存选区 / 清除) 不是被藏起来, 是被**收编**进这个面板的第一行 ——
 * Photoshop 的 Contextual Task Bar 本来就是"一个条装两类操作"。
 *
 * 三个形态:
 * - [AiPanelForm.READY]      A 就绪: 意图输入 + 选区操作 + 主按钮
 * - [AiPanelForm.GENERATING] B 生成中: **面板不消失, 画布不锁**。进度 / 排队位次 / 取消
 * - [AiPanelForm.RESULT]     C 结果待选: 面板收缩成一条变体带
 *
 * 核心是 §4.4 的**主按钮随上下文变形**: 文案完全由
 * [com.reverie.paint.core.comfy.AiButtonResolver] 求, 这里一行 if-else 都没有。
 *
 * @param dragBounds 父容器可用矩形 (dp), 传给 [ToolFloatPanel] 做拖拽钳制
 * @param maxContentWidth 内容区最大宽度 (dp, 已扣掉外壳内边距与左侧工具栏)。
 *   里面的行都用 `fillMaxWidth` + `weight`, 所以窄屏时第一行的选区操作会折行, 而不是被裁掉。
 */
@Composable
fun AiGeneratePanel(
    vm: PaintViewModel,
    tool: Tool,
    hazeState: HazeState?,
    dragBounds: Rect,
    maxContentWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val ui = vm.aiUi
    val visible = vm.aiPanelVisible(tool)

    // 面板把自己的包围盒报给画布 → 画布笔画不会落在面板上 (ISSUES.md P5)。
    // 隐藏时发 null 清掉避让, 否则面板走了笔画还在绕路。
    LaunchedEffect(visible) {
        if (!visible) vm.publishAiPanelBounds(null)
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.enterSpring()),
        exit = fadeOut(Motion.exitTween()),
    ) {
        ToolFloatPanel(vm = vm, hazeState = hazeState, offsetBounds = dragBounds) {
            Box(
                modifier = Modifier
                    .width(maxContentWidth)
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInRoot()
                        if (b.width > 0f && b.height > 0f) {
                            vm.publishAiPanelBounds(android.graphics.RectF(b.left, b.top, b.right, b.bottom))
                        }
                    },
            ) {
                when (ui.form) {
                    AiPanelForm.READY,
                    AiPanelForm.GENERATING,
                    -> AiPanelIntent(vm = vm, generating = ui.form == AiPanelForm.GENERATING)

                    AiPanelForm.RESULT -> AiPanelVariantStrip(vm = vm)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 形态 A / B · 就绪与生成中共用同一副骨架
// ---------------------------------------------------------------------------

/**
 * 形态 A (就绪) 与形态 B (生成中) 共用。
 *
 * 不是两个 Composable: 它们的行结构一样, 只有第三行换内容 (参数行 ↔ 进度行)。
 * 分成两个函数的后果是"生成中长什么样"和"就绪长什么样"各改各的, 迟早走样。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiPanelIntent(
    vm: PaintViewModel,
    generating: Boolean,
) {
    val ui = vm.aiUi
    val label = vm.aiButtonLabel()

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        AiPanelHeader(vm = vm)

        // 未配置服务器时摆出生成控件只会让人点了没反应 —— 换成引导去配置的形态
        if (!generating && !vm.comfyServer.configured) {
            AiServerGuide(onOpenSettings = { vm.openMoreSettings("COMFY") })
            return@Column
        }

        // ---- 行 2: 生成方式 / 提示词 / 主按钮 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AiDropdownChip(
                items = aiModeItems(),
                selected = ui.mode,
                onSelect = { ui.mode = it },
            )
            AiPromptField(
                value = ui.prompt,
                onValueChange = { ui.prompt = it },
                modifier = Modifier.weight(1f),
            )
            // 主按钮: 文案随上下文变形, 用 AnimatedContent 让"变形"这件事本身被看见
            AiPrimaryAction(
                actionText = aiActionLabel(label.text),
                queueText = stringResource(R.string.ai_action_queue_more),
                queueMode = generating || ui.queuedCount > 0,
                onClick = { vm.submitAiGeneration() },
            )
        }

        // ---- 行 3: 参数 ↔ 进度 (FlowRow: 窄屏自动折行, 不引入横向滚动) ----
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (generating) {
                AiProgressRow(vm = vm, modifier = Modifier.weight(1f))
                ToolActionButton(
                    iconRes = R.drawable.ic_x,
                    label = stringResource(R.string.common_cancel),
                    onClick = { vm.cancelAiGeneration() },
                )
            } else {
                AiDropdownChip(
                    items = aiSourceItems(),
                    selected = ui.sourceKind,
                    onSelect = { ui.sourceKind = it },
                )
                AiStrengthCapsule(vm = vm)
                Spacer(Modifier.weight(1f))
                ToolActionButton(
                    iconRes = R.drawable.ic_settings,
                    label = stringResource(R.string.ai_action_settings),
                    active = ui.advancedOpen,
                    onClick = { ui.advancedOpen = !ui.advancedOpen },
                )
            }
        }

        // ⚙ 抽屉: 藏起来的是复杂度, 不是能力 (原则 P7)。步数 / CFG / 种子都是真的接在
        // 状态上 —— 改步数会当场改变形态 B 的进度总步数, 不是装饰。
        AnimatedVisibility(
            visible = ui.advancedOpen && !generating,
            enter = fadeIn(Motion.enterSpring()) + scaleIn(Motion.enterSpring(), initialScale = 0.96f),
            exit = fadeOut(Motion.exitTween(140)) + scaleOut(Motion.exitTween(140), targetScale = 0.96f),
        ) {
            AiAdvancedDrawer(vm = vm, modifier = Modifier.fillMaxWidth())
        }

        // 副标题: 11sp 一行说明"这次提交会做什么"。它是主按钮的**解释**, 不是装饰 ——
        // 用户改取材来源时, 这里会跟着换说法, 是理解按钮为什么变形的钥匙。
        AnimatedContent(
            targetState = aiSubtitleLabel(label.subtitle),
            transitionSpec = {
                (fadeIn(Motion.enterSpring()) + scaleIn(Motion.enterSpring(), initialScale = 0.96f))
                    .togetherWith(fadeOut(Motion.exitTween(120)))
            },
            label = "aiSubtitle",
        ) { text ->
            Text(
                text = text,
                fontSize = 11.sp,
                color = Morandi.subText.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 行 1: 标题 + 选区信息 + **收编来的选区操作**。
 *
 * 形态 C 不走这里 —— 结果待选时这一行整个收起来, 只剩变体带 (AI-PAINT-UX.md §4.3)。
 *
 * 用 FlowRow 而不是 Row: 这一行塞得下"标题 + 尺寸 + 4 个带文字的选区操作 + 关闭",
 * 360dp 的竖屏手机上放不下, 折行比裁切或横向滚动体面得多 (横向滚动在项目里只用于
 * QuickBrush, 且是已知不足)。
 *
 * 所有控件**左靠紧排**, 不用 weight 把关闭按钮顶到行尾 —— 折行之后 weight 会在第二行
 * 留下一个空洞, 看起来像没排完。挤在一起反而像一条本来就该这么长的操作条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiPanelHeader(
    vm: PaintViewModel,
) {
    FlowRow(
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_magicwand),
            contentDescription = null,
            tint = Morandi.accent,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = stringResource(R.string.ai_panel_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Morandi.text,
        )
        AiSelectionBadge(vm = vm)

        // 选区操作: 不是隐藏, 是收编。AI 工具期间这是唯一的选区操作入口。
        SelectionActionItem(
            iconRes = R.drawable.ic_refresh,
            label = stringResource(R.string.selection_invert),
            onClick = { vm.invertSelectionAction() },
        )
        SelectionActionItem(
            iconRes = R.drawable.ic_copy,
            label = stringResource(R.string.common_copy),
            onClick = { vm.copyOrCutSelection(cut = false, toNewLayer = false) },
        )
        SelectionActionItem(
            iconRes = R.drawable.ic_bookmark_plus,
            label = stringResource(R.string.selection_bottom_action_save),
            active = vm.savedSelectionsPopupOpen,
            onClick = { vm.savedSelectionsPopupOpen = !vm.savedSelectionsPopupOpen },
        )
        SelectionActionItem(
            iconRes = R.drawable.ic_trash,
            label = stringResource(R.string.selection_clear),
            danger = true,
            onClick = { vm.clearSelectionAction() },
        )

        ToolActionButton(
            iconRes = R.drawable.ic_x,
            label = stringResource(R.string.common_close),
            onClick = { vm.dismissAi() },
        )
    }
}

/**
 * 选区状态徽章。
 *
 * 有选区 → 显示真实的包围盒尺寸 (`选区 2048×1536`), 走后台线程从蒙版算, 不猜。
 * 无选区 → 一句 `没有选区 · 将处理整个画布`。**不给灰、不禁用**, 因为凭空生成
 * 本来就不需要选区 (与 Photoshop 的有意差异, 见 AI-PAINT-UX.md §4.4)。
 */
@Composable
private fun AiSelectionBadge(
    vm: PaintViewModel,
) {
    val ui = vm.aiUi
    val hasSel = vm.hasSelection
    // 选区变化后异步量一次包围盒。蒙版是文档尺寸的逐像素数组 (2000×3000 就是 6MB),
    // 绝不能在主线程扫 —— 这里用 LaunchedEffect 的 key 变化当防抖: 拖动选区时 key
    // 一直在变, 协程不断重启, 只有停下来 220ms 后才真的去量一次。
    LaunchedEffect(hasSel, vm.selectionOverlayBitmap) {
        if (hasSel) {
            kotlinx.coroutines.delay(220)
            vm.refreshAiSelectionBounds()
        }
    }
    val text =
        if (hasSel && ui.selectionWidth > 0) {
            stringResource(R.string.ai_selection_size, ui.selectionWidth, ui.selectionHeight)
        } else {
            stringResource(R.string.ai_no_selection)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (hasSel) Morandi.accent.copy(alpha = 0.14f) else Morandi.panelHi)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_select_rect),
            contentDescription = null,
            tint = if (hasSel) Morandi.accent else Morandi.subText,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (hasSel) Morandi.text else Morandi.subText,
            maxLines = 1,
        )
    }
}

/** 提示词输入框。**允许留空** —— 空提示词 = 照周边续写, 是一等公民输入 (原则 P10) */
@Composable
private fun AiPromptField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panelHi)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            // 占位文案垫在输入层底下与文字重叠 —— 空提示词是一等公民输入 (原则 P10)
            Text(
                text = stringResource(R.string.ai_prompt_placeholder),
                fontSize = 12.sp,
                color = Morandi.subText.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                color = Morandi.text,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Morandi.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 主按钮。
 *
 * 文案来自 [com.reverie.paint.core.comfy.AiButtonResolver], 这里只负责把它变成一个按钮。
 * 形态 B 时**不禁用**, 变成"再排一个" —— 连按是合法的 (COMFYUI-DESIGN.md §2.11)。
 */
@Composable
private fun AiPrimaryAction(
    actionText: String,
    queueText: String,
    queueMode: Boolean,
    onClick: () -> Unit,
) {
    AnimatedContent(
        targetState = if (queueMode) queueText else actionText,
        transitionSpec = {
            // 横向滑动 + 纵向错位: 按钮"变形"这件事必须看得见, 否则用户只会以为自己看错了
            (fadeIn(Motion.enterSpring()) + scaleIn(Motion.enterSpring(), initialScale = 0.88f))
                .togetherWith(
                    fadeOut(Motion.exitTween(110)) + scaleOut(Motion.exitTween(110), targetScale = 1.06f),
                )
        },
        label = "aiPrimaryAction",
    ) { text ->
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.93f else 1f, Motion.springSnap, label = "aiPrimaryScale")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .scale(scale)
                .height(38.dp)
                .widthIn(min = 86.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (queueMode) Morandi.panelHi else Morandi.accent)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                ) { onClick() }
                .padding(horizontal = 15.dp),
        ) {
            Icon(
                painter = painterResource(if (queueMode) R.drawable.ic_plus else R.drawable.ic_magicwand),
                contentDescription = null,
                tint = if (queueMode) Morandi.text else Morandi.onAccent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (queueMode) Morandi.text else Morandi.onAccent,
                maxLines = 1,
            )
        }
    }
}

/** 形态 B 的第三行: 进度条 + 步数 + 排队位次 */
@Composable
private fun AiProgressRow(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val ui = vm.aiUi
    val progress by animateFloatAsState(ui.progress, Motion.springSoft, label = "aiProgress")
    Column(
        modifier = modifier.widthIn(min = 150.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ai_progress_steps, ui.runningStep, ui.totalSteps),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Morandi.text,
            )
            Spacer(Modifier.weight(1f))
            if (ui.queuedCount > 0) {
                Text(
                    text = stringResource(R.string.ai_queued_ahead, ui.queuedCount),
                    fontSize = 10.sp,
                    color = Morandi.accent,
                )
            }
        }
        // 轨道与 ReSlider 同语言: accent 在 panelHi 上
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Morandi.panelHi),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Morandi.accent),
            )
        }
    }
}

/**
 * 改动幅度胶囊: 默认收起只露一个胶囊, 点开才是滑杆。
 *
 * 这是**唯一常驻的数值参数** (原则 P6: 暴露 denoising strength, 不暴露 sampler/CFG/VAE)。
 * 滑杆复用 `ToolFloatSlider` —— 它内建数字键盘精确输入, 画家要的就是"我要 62%, 不是 60%"。
 */
@Composable
private fun AiStrengthCapsule(
    vm: PaintViewModel,
) {
    val ui = vm.aiUi
    var pending by remember(ui.strengthPercent) { mutableFloatStateOf(-1f) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolFloatChip(
            label = stringResource(R.string.ai_strength_capsule, ui.strengthPercent),
            selected = ui.strengthExpanded,
            onClick = { ui.strengthExpanded = !ui.strengthExpanded },
        )
        AnimatedVisibility(
            visible = ui.strengthExpanded,
            enter = fadeIn(Motion.enterSpring()) + scaleIn(Motion.enterSpring(), initialScale = 0.9f),
            exit = fadeOut(Motion.exitTween(120)) + scaleOut(Motion.exitTween(120), targetScale = 0.9f),
        ) {
            ToolFloatSlider(
                label = stringResource(R.string.ai_strength),
                valueText = stringResource(R.string.ai_strength_value, ui.strengthPercent),
                range = 0f..100f,
                value = if (pending >= 0f) pending else ui.strengthPercent.toFloat(),
                onValue = { pending = it },
                onRelease = {
                    if (pending >= 0f) {
                        ui.strengthPercent = pending.toInt().coerceIn(0, 100)
                        pending = -1f
                    }
                },
                labelWidth = 52.dp,
                modifier = Modifier.width(190.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ⚙ 参数抽屉
// ---------------------------------------------------------------------------

/**
 * ⚙ 抽屉 (生成参数)。
 *
 * 只放三个**真的接在状态上**的参数, 其余复杂度 (采样器 / 调度器 / VAE / LoRA / 分块)
 * 一律不暴露 —— 用户想调就新建一个预设 (原则 P7)。
 *
 * 视觉上比外面深一级 ([Morandi.panelHi]) 而不是加一圈描边: `Morandi.border` 是 Transparent,
 * 层级一律靠明度差。
 */
@Composable
private fun AiAdvancedDrawer(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val ui = vm.aiUi
    var pendingSteps by remember(ui.steps) { mutableFloatStateOf(-1f) }
    var pendingCfg by remember(ui.cfg) { mutableFloatStateOf(-1f) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Morandi.panelHi)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_sliders),
                contentDescription = null,
                tint = Morandi.subText,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.ai_advanced_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Morandi.text,
            )
            Spacer(Modifier.weight(1f))
            ToolFloatChip(
                label = stringResource(if (ui.advancedOpen) R.string.ai_collapse else R.string.ai_expand),
                onClick = { ui.advancedOpen = !ui.advancedOpen },
            )
        }
        ToolFloatSlider(
            label = stringResource(R.string.ai_param_steps),
            valueText = ui.steps.toString(),
            range = 8f..50f,
            value = if (pendingSteps >= 0f) pendingSteps else ui.steps.toFloat(),
            onValue = { pendingSteps = it },
            onRelease = {
                if (pendingSteps >= 0f) {
                    ui.steps = pendingSteps.toInt().coerceIn(8, 50)
                    pendingSteps = -1f
                }
            },
            labelWidth = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        ToolFloatSlider(
            label = stringResource(R.string.ai_param_cfg),
            valueText = ui.cfg.roundToInt().toString(),
            range = 1f..14f,
            value = if (pendingCfg >= 0f) pendingCfg else ui.cfg,
            onValue = { pendingCfg = it },
            onRelease = {
                if (pendingCfg >= 0f) {
                    ui.cfg = pendingCfg.coerceIn(1f, 14f)
                    pendingCfg = -1f
                }
            },
            labelWidth = 44.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_param_seed),
                fontSize = 12.sp,
                color = Morandi.subText,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Morandi.bg.copy(alpha = 0.6f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = ui.seed.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Morandi.text,
                )
            }
            Spacer(Modifier.weight(1f))
            ToolFloatChip(
                label = stringResource(R.string.ai_action_random_seed),
                onClick = { vm.rerollAiSeed() },
            )
        }
        AiServerEntry(vm = vm)
    }
}

/**
 * 服务器入口 (在 ⚙ 抽屉里)。点开跳到设置子页 `COMFY`。
 *
 * 右侧露出「已配置 / 未配置」, 让用户不用点进去就知道生成按钮能不能指望。
 */
@Composable
private fun AiServerEntry(vm: PaintViewModel) {
    val server = vm.comfyServer
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(interactionSource = interaction, indication = null) { vm.openMoreSettings("COMFY") }
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.ai_server_settings_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Morandi.text,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text =
                if (server.configured) {
                    stringResource(R.string.ai_server_configured)
                } else {
                    stringResource(R.string.ai_server_not_configured_short)
                },
            fontSize = 11.sp,
            color = if (server.configured) Morandi.accent else Morandi.subText,
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = Morandi.subText.copy(alpha = 0.45f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * 未配置服务器的引导态。
 *
 * 替换整段生成控件 (含主按钮), 只留一句说明和一个明确的「去配置」入口 —— 面板不会
 * 让人觉得「能生成」而实际上什么都不发生。
 */
@Composable
private fun AiServerGuide(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panelHi)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ai_server_guide_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Morandi.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.ai_server_guide_desc),
                fontSize = 11.sp,
                color = Morandi.subText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Morandi.accent)
                .clickable { onOpenSettings() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_server_guide_action),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Morandi.onAccent,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 形态 C · 结果待选 (面板收缩成一条变体带)
// ---------------------------------------------------------------------------

/**
 * 形态 C: 整条面板缩成一行变体带。
 *
 * 变体用深色占位块 (不生成假图), 当前变体 2.5dp accent 描边高亮 —— 描边是**唯一允许的
 * 1dp+ 线条**, 因为这里表达的是"选中", 不是分区 (Morandi.border 是 Transparent,
 * 层级一律靠明度差, 见 AGENTS.md / AI-PAINT-UX.md §5.3)。
 */
@Composable
private fun AiPanelVariantStrip(
    vm: PaintViewModel,
) {
    val ui = vm.aiUi
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_magicwand),
            contentDescription = null,
            tint = Morandi.accent,
            modifier = Modifier.size(15.dp),
        )
        ui.variants.forEach { variant ->
            AiVariantThumb(
                index = variant.index,
                selected = variant.index == ui.selectedVariant,
                onClick = { ui.selectedVariant = variant.index },
            )
        }
        Spacer(Modifier.weight(1f))
        ToolActionButton(
            iconRes = R.drawable.ic_refresh,
            label = stringResource(R.string.ai_action_regenerate),
            onClick = { vm.regenerateAi() },
        )
        ToolActionButton(
            iconRes = R.drawable.ic_check,
            label = stringResource(R.string.common_apply),
            primary = true,
            onClick = { vm.applyAiResult() },
        )
        ToolActionButton(
            iconRes = R.drawable.ic_x,
            label = stringResource(R.string.common_discard),
            onClick = { vm.dismissAi() },
        )
    }
}

@Composable
private fun AiVariantThumb(
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    val scale by animateFloatAsState(if (selected) 1f else 0.94f, Motion.springSnap, label = "variantScale")
    Box(
        modifier = Modifier
            .scale(scale)
            .size(44.dp)
            .clip(shape)
            .background(Color(0xFF141518))
            .then(
                // 当前变体: 2.5dp accent 描边 (选中态, 是允许的例外)
                if (selected) Modifier.border(2.5.dp, Morandi.accent, shape) else Modifier,
            )
            .noRippleClickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = Morandi.subText.copy(alpha = if (selected) 0.55f else 0.3f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.ai_variant_index, index + 1),
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Morandi.accent else Morandi.subText.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 通用: 下拉 chip + strings key 解析
// ---------------------------------------------------------------------------

/** 形态 A 第二行的两个下拉 (生成方式 / 取材来源), 复用 ToolBubbleDropdown 的气泡菜单 */
@Composable
private fun <T> AiDropdownChip(
    items: List<AiDropdownItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    ToolBubbleDropdown(
        items = items.map { ToolDropdownItemData(it.value, it.iconRes, it.label) },
        selected = selected,
        onSelect = onSelect,
        active = true,
        labelOverride = items.firstOrNull { it.value == selected }?.label ?: "",
    )
}

private data class AiDropdownItem<T>(
    val value: T,
    val iconRes: Int,
    val label: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun aiModeItems(): List<AiDropdownItem<AiGenerateMode>> =
    listOf(
        AiDropdownItem(AiGenerateMode.FILL, R.drawable.ic_fill, stringResource(R.string.ai_mode_fill)),
        AiDropdownItem(AiGenerateMode.EXTEND, R.drawable.ic_canvas_resize, stringResource(R.string.ai_mode_extend)),
        AiDropdownItem(AiGenerateMode.ADD, R.drawable.ic_plus, stringResource(R.string.ai_mode_add)),
        AiDropdownItem(AiGenerateMode.REMOVE, R.drawable.ic_eraser, stringResource(R.string.ai_mode_remove)),
        AiDropdownItem(AiGenerateMode.REPLACE_BG, R.drawable.ic_layers, stringResource(R.string.ai_mode_replace_bg)),
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun aiSourceItems(): List<AiDropdownItem<AiSourceKind>> =
    listOf(
        AiDropdownItem(AiSourceKind.SELECTION, R.drawable.ic_select_rect, stringResource(R.string.ai_source_selection)),
        AiDropdownItem(AiSourceKind.WHOLE_CANVAS, R.drawable.ic_image, stringResource(R.string.ai_source_canvas)),
        AiDropdownItem(AiSourceKind.REFERENCE, R.drawable.ic_reference, stringResource(R.string.ai_source_reference)),
    )

/**
 * 把 [com.reverie.paint.core.comfy.AiButtonResolver] 给的 **strings key 名**换成字面量。
 *
 * Resolver 里存 key 而不是 `R.string.*` 的 int, 是为了让整条判定链能在纯 JVM 上单测。
 * 这里就是那条链与资源系统的**唯一**接缝 —— 加新语义时, 两边都要改 (编译器会兜住漏的那个)。
 */
@Composable
private fun aiActionLabel(key: String): String =
    when (key) {
        AiButtonResolver.ACTION_FILL -> stringResource(R.string.ai_action_fill)
        AiButtonResolver.ACTION_EXPAND -> stringResource(R.string.ai_action_expand)
        else -> stringResource(R.string.ai_action_generate)
    }

@Composable
private fun aiSubtitleLabel(key: String): String =
    when (key) {
        AiButtonResolver.SUB_INPAINT -> stringResource(R.string.ai_sub_inpaint)
        AiButtonResolver.SUB_OUTPAINT -> stringResource(R.string.ai_sub_outpaint)
        AiButtonResolver.SUB_ADD_CONTENT -> stringResource(R.string.ai_sub_add_content)
        AiButtonResolver.SUB_REMOVE_CONTENT -> stringResource(R.string.ai_sub_remove_content)
        AiButtonResolver.SUB_REPLACE_BG -> stringResource(R.string.ai_sub_replace_bg)
        AiButtonResolver.SUB_REFINE_CANVAS -> stringResource(R.string.ai_sub_refine_canvas)
        else -> stringResource(R.string.ai_sub_free_generate)
    }