/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import kotlin.math.roundToInt

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.core.*

/** 面板在父容器里的静止锚点 —— 决定拖拽钳制时"能往哪边挪" */
enum class FloatPanelAnchor { TOP_START, BOTTOM_CENTER }

/**
 * Premium glassmorphism-style floating capsule with entry animations and drag support.
 *
 * @param offsetBounds 父容器可用矩形 (dp)。默认 null = **不钳制**, 这是既有 11 个调用点的
 *   历史行为; 传了就会把拖拽位移 clamp 在屏幕内。
 *   为什么需要它: 面板尺寸是动态的 (AI 面板随形态在 1~4 行之间变), 不钳制的话能一路
 *   拖出屏幕再也抓不回来。
 *   单位是 dp 而不是 px: `paintingUiScale` 只缩放 [LocalDensity], "量"和"用"走同一个
 *   density, 缩放自动抵消 (绕开 AI-PAINT-UX.md §6 C3 的手算坑)。
 * @param anchor 面板的静止锚点, 与调用点的 `Modifier.align(...)` 保持一致
 */
@Composable
fun ToolFloatPanel(
    modifier: Modifier = Modifier,
    vm: PaintViewModel? = null,
    hazeState: HazeState? = null,
    offsetBounds: Rect? = null,
    anchor: FloatPanelAnchor = FloatPanelAnchor.BOTTOM_CENTER,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val capsuleShape = RoundedCornerShape(18.dp)
    // 面板静止时的尺寸 (不含拖拽位移), 钳制要用它算可用行程
    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)),
        exit = fadeOut() + scaleOut(targetScale = 0.95f)
    ) {
        Row(
            modifier = modifier
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .onSizeChanged { panelSize = it }
                .pointerInput(offsetBounds, anchor) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragOffset = clampDragOffset(dragOffset + dragAmount, offsetBounds, panelSize, anchor, this)
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .shadow(16.dp, capsuleShape, spotColor = Color.Black.copy(alpha = 0.45f))
                .systemHoverIcon(context)
                .clip(capsuleShape)
                .then(
                    if (vm?.blurBackground == true && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.popupStyle(vm.popupPanelOpacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = vm?.popupPanelOpacity ?: 0.94f))
                    }
                )
                .glassBorder(capsuleShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

/**
 * 把拖拽位移钳制在父容器内, 贴边留 [DRAG_EDGE_MARGIN_DP] 余量。
 *
 * 面板静止时靠 [anchor] 对齐, 所以可拖区间是"静止位置 ± 可用行程的一半/全长"。
 * 完全贴边时用户会以为"卡住了" (它其实只是到头了), 所以留一点余量。
 */
private const val DRAG_EDGE_MARGIN_DP = 8f

private fun clampDragOffset(
    offset: Offset,
    bounds: Rect?,
    panelSize: IntSize,
    anchor: FloatPanelAnchor,
    density: Density,
): Offset {
    if (bounds == null || panelSize.width <= 0 || panelSize.height <= 0) return offset
    return with(density) {
        val marginPx = DRAG_EDGE_MARGIN_DP.dp.toPx()
        val slackX = (bounds.width - panelSize.width - marginPx * 2f).coerceAtLeast(0f)
        val slackY = (bounds.height - panelSize.height - marginPx * 2f).coerceAtLeast(0f)
        val centered = anchor == FloatPanelAnchor.BOTTOM_CENTER
        val baseX = if (centered) slackX / 2f else 0f
        val baseY = if (centered) slackY else 0f
        Offset(
            x = offset.x.coerceIn(-baseX, slackX - baseX),
            y = offset.y.coerceIn(-baseY, slackY - baseY),
        )
    }
}

@Composable
fun ToolFloatChip(
    label: String,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "scale")

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> Morandi.accent
                    danger -> Color(0x33B05552)
                    else -> Morandi.panelHi
                }
            )
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Morandi.onAccent else if (danger) Color(0xFFE57373) else Morandi.text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun <T> ToolFloatSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (key, label) ->
            val isSel = selected == key
            var pressed by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "segScale")

            Box(
                modifier = Modifier
                    .scale(scale)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) Morandi.accent else Color.Transparent)
                    .pointerInput(key) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { onSelect(key) }
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSel) Morandi.onAccent else Morandi.subText,
                )
            }
        }
    }
}

@Composable
fun ToolFloatSlider(
    label: String,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    labelWidth: Dp? = null,
    onRelease: (() -> Unit)? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    val span = maxOf(0.001f, range.endInclusive - range.start)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = Morandi.subText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            modifier = if (labelWidth != null) Modifier.widthIn(min = labelWidth) else Modifier,
        )
        com.reverie.paint.ui.components.ReSlider(
            value = ((value - range.start) / span).coerceIn(0f, 1f),
            onValue = { frac -> onValue(range.start + frac * span) },
            onRelease = onRelease,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .widthIn(min = 36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Morandi.panelHi.copy(alpha = 0.7f))
                .noRippleClickable { showDialog = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                valueText,
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    if (showDialog) {
        val suffix = when {
            valueText.endsWith("px") -> "px"
            valueText.endsWith("%") -> "%"
            valueText.endsWith("°") -> "°"
            else -> ""
        }
        NumericValueInputDialog(
            label = label,
            currentValue = value,
            min = range.start,
            max = range.endInclusive,
            unitSuffix = suffix,
            onValueConfirmed = { confirmed ->
                onValue(confirmed)
                onRelease?.invoke()
            },
            onDismiss = { showDialog = false }
        )
    }
}
