/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.ComfyConnectionStatus
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.addComfyHeaderRow
import com.reverie.paint.core.comfy.ComfyAuthMode
import com.reverie.paint.core.persistComfySettings
import com.reverie.paint.core.removeComfyHeaderRow
import com.reverie.paint.core.setComfyTrustSelfSigned
import com.reverie.paint.core.testComfyConnection
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.painting.TextInputGuard
import com.reverie.paint.ui.theme.Theme

/**
 * ComfyUI 服务器设置子页 (只做配置 + 测试连接, 不做生成)。
 *
 * 结构与 [SyncSettingsSubPage] 一致: 分类标题 + 连体卡片 + 底部状态行。密码只以草稿形式
 * 存在于本 Composable 的本地状态里, 点「测试连接」时才交给 ViewModel 加密落盘。
 */
@Composable
internal fun ComfySettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    onBack: () -> Unit,
) {
    // 服务器地址/请求头输入框需挂守卫, 避免从画布进入本页时退格被全局快捷键吞掉
    TextInputGuard(vm)
    val colors = Theme.current
    val server = vm.comfyServer

    var draftPassword by remember { mutableStateOf("") }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .padding(
                        horizontal = if (compact) 12.dp else 20.dp,
                        vertical = if (compact) 12.dp else 20.dp,
                    ),
        ) {
            SettingSubPageHeader(
                title = stringResource(R.string.comfy_page_title),
                subtitle = stringResource(R.string.comfy_page_subtitle),
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            // ---- 服务器地址 ----
            SettingCategoryTitle(stringResource(R.string.comfy_category_server))
            SettingGroup {
                ComfyInputField(
                    label = stringResource(R.string.comfy_field_server),
                    value = server.baseUrl,
                    placeholder = stringResource(R.string.comfy_field_server_placeholder),
                    keyboardType = KeyboardType.Uri,
                    shape = settingGroupShape(0, 1),
                    onValueChange = { server.baseUrl = it },
                )
            }

            // ---- 认证方式 ----
            SettingCategoryTitle(stringResource(R.string.comfy_category_auth))
            SettingSegmentGroupItem(
                title = stringResource(R.string.comfy_auth_title),
                summary = stringResource(R.string.comfy_auth_summary),
                options =
                    listOf(
                        ComfyAuthMode.NONE to stringResource(R.string.comfy_auth_none),
                        ComfyAuthMode.BASIC to stringResource(R.string.comfy_auth_basic),
                        ComfyAuthMode.CUSTOM_HEADER to stringResource(R.string.comfy_auth_header),
                    ),
                selected = server.authMode,
                shape = settingGroupShape(0, 1),
                onSelect = {
                    server.authMode = it
                    vm.persistComfySettings()
                },
            )

            when (server.authMode) {
                ComfyAuthMode.NONE -> Unit

                ComfyAuthMode.BASIC -> {
                    Spacer(Modifier.height(4.dp))
                    SettingGroup {
                        ComfyInputField(
                            label = stringResource(R.string.comfy_field_username),
                            value = server.username,
                            placeholder = stringResource(R.string.comfy_field_username_placeholder),
                            shape = settingGroupShape(0, 2),
                            onValueChange = { server.username = it },
                        )
                        ComfyInputField(
                            label = stringResource(R.string.comfy_field_password),
                            value = draftPassword,
                            placeholder =
                                stringResource(
                                    if (server.hasSavedPassword) {
                                        R.string.comfy_password_saved_hint
                                    } else {
                                        R.string.comfy_field_password_placeholder
                                    },
                                ),
                            keyboardType = KeyboardType.Password,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = settingGroupShape(1, 2),
                            onValueChange = { draftPassword = it },
                        )
                    }
                }

                ComfyAuthMode.CUSTOM_HEADER -> {
                    Spacer(Modifier.height(4.dp))
                    ComfyHeaderEditor(vm = vm)
                }
            }

            // ---- 安全 ----
            Spacer(Modifier.height(8.dp))
            SettingCategoryTitle(stringResource(R.string.comfy_category_security))
            SettingGroup {
                SettingSwitchGroupItem(
                    title = stringResource(R.string.comfy_trust_self_signed),
                    summary = stringResource(R.string.comfy_trust_self_signed_summary),
                    checked = server.trustSelfSigned,
                    shape = settingGroupShape(0, 1),
                    onCheckedChange = { vm.setComfyTrustSelfSigned(it) },
                )
            }

            // ---- 测试连接 ----
            Spacer(Modifier.height(8.dp))
            SettingGroup {
                ComfyActionRow(
                    index = 0,
                    total = 1,
                    text = stringResource(R.string.comfy_test_connection),
                    color = colors.accent,
                    enabled = server.status != ComfyConnectionStatus.TESTING,
                ) { vm.testComfyConnection(draftPassword) }
            }

            Spacer(Modifier.height(8.dp))
            ComfyStatusBlock(vm = vm)

            Spacer(Modifier.height(16.dp))
            SettingInfoCard(
                title = stringResource(R.string.comfy_info_title),
                text = stringResource(R.string.comfy_info_text),
            )

            Spacer(Modifier.height(60.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 自定义请求头编辑器
// ---------------------------------------------------------------------------

@Composable
private fun ComfyHeaderEditor(vm: PaintViewModel) {
    val colors = Theme.current
    val rows = vm.comfyServer.extraHeaders
    val total = rows.size + 1

    SettingGroup {
        if (rows.isEmpty()) {
            ComfyHeaderEmptyRow(shape = settingGroupShape(0, total))
        } else {
            rows.forEachIndexed { index, row ->
                ComfyHeaderRowItem(
                    key = row.key,
                    value = row.value,
                    shape = settingGroupShape(index, total),
                    onKeyChange = { row.key = it },
                    onValueChange = { row.value = it },
                    onDelete = { vm.removeComfyHeaderRow(index) },
                )
            }
        }
        ComfyActionRow(
            index = total - 1,
            total = total,
            text = stringResource(R.string.comfy_header_add),
            color = colors.accent,
        ) { vm.addComfyHeaderRow() }
    }
}

@Composable
private fun ComfyHeaderEmptyRow(shape: RoundedCornerShape) {
    val colors = Theme.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.comfy_header_empty),
            color = colors.subText,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ComfyHeaderRowItem(
    key: String,
    value: String,
    shape: RoundedCornerShape,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Theme.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel)
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ComfyBareField(
            value = key,
            placeholder = stringResource(R.string.comfy_header_key),
            modifier = Modifier.weight(0.42f),
            onValueChange = onKeyChange,
        )
        ComfyBareField(
            value = value,
            placeholder = stringResource(R.string.comfy_header_value),
            modifier = Modifier.weight(0.58f),
            onValueChange = onValueChange,
            imeAction = ImeAction.Done,
        )
        ComfyIconTap(
            iconRes = R.drawable.ic_trash,
            tint = colors.error,
            onClick = onDelete,
        )
    }
}

// ---------------------------------------------------------------------------
// 状态行
// ---------------------------------------------------------------------------

@Composable
private fun ComfyStatusBlock(vm: PaintViewModel) {
    val colors = Theme.current
    val server = vm.comfyServer
    val status = server.status

    if (status == ComfyConnectionStatus.NOT_CONFIGURED) {
        Text(
            text = stringResource(R.string.comfy_status_not_configured),
            color = colors.subText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp),
        )
        return
    }

    val (text, color) =
        when (status) {
            ComfyConnectionStatus.NOT_CONFIGURED ->
                stringResource(R.string.comfy_status_not_configured) to colors.subText

            ComfyConnectionStatus.TESTING ->
                stringResource(R.string.comfy_status_testing) to colors.subText

            ComfyConnectionStatus.CONNECTED ->
                if (server.statusDetail.isBlank()) {
                    stringResource(R.string.comfy_status_connected) to colors.accent
                } else {
                    stringResource(R.string.comfy_status_connected_version, server.statusDetail) to colors.accent
                }

            ComfyConnectionStatus.AUTH_NOT_CONFIGURED ->
                stringResource(R.string.comfy_status_auth_not_configured) to colors.error

            ComfyConnectionStatus.AUTH_FAILED ->
                stringResource(R.string.comfy_status_auth_failed) to colors.error

            ComfyConnectionStatus.UNREACHABLE ->
                stringResource(R.string.comfy_status_unreachable) to colors.error

            ComfyConnectionStatus.SERVER_ERROR ->
                stringResource(R.string.comfy_status_server_error, server.statusDetail) to colors.error

            ComfyConnectionStatus.INVALID_URL ->
                stringResource(R.string.comfy_status_invalid_url, server.statusDetail) to colors.error
        }

    Column(modifier = Modifier.padding(start = 6.dp)) {
        Text(text = text, color = color, fontSize = 12.sp)
        // 鉴权失败: 提示「服务器可能没开认证」这条常见弯路
        if (status == ComfyConnectionStatus.AUTH_FAILED) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.comfy_status_auth_failed_hint),
                color = colors.subText,
                fontSize = 11.sp,
            )
        }
        // 连不上: 把本次尝试的完整 URL 摆出来, 地址写错时一眼能看出来
        if (status == ComfyConnectionStatus.UNREACHABLE && server.lastAttemptUrl.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.comfy_status_unreachable_url, server.lastAttemptUrl),
                color = colors.subText,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 局部控件 (与 SyncSettingsSubPage 的同名私有控件形状一致, 不复用其私有实现)
// ---------------------------------------------------------------------------

@Composable
private fun ComfyActionRow(
    index: Int,
    total: Int,
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(settingGroupShape(index, total))
                .background(colors.panel)
                .then(
                    if (enabled) {
                        Modifier
                            .pressScale(interaction, pressedScale = 0.98f)
                            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (enabled) color else colors.subText.copy(alpha = 0.4f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ComfyInputField(
    label: String,
    value: String,
    placeholder: String,
    shape: RoundedCornerShape,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onValueChange: (String) -> Unit,
) {
    val colors = Theme.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = colors.subText.copy(alpha = 0.5f), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.accent,
                ),
        )
    }
}

/** 请求头行里的裸输入框: 无标签、背景透明, 与卡片底色融合 */
@Composable
private fun ComfyBareField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    onValueChange: (String) -> Unit,
) {
    val colors = Theme.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = colors.subText.copy(alpha = 0.5f), fontSize = 12.sp) },
        modifier = modifier,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = colors.text),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = imeAction),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                cursorColor = colors.accent,
            ),
    )
}

@Composable
private fun ComfyIconTap(
    iconRes: Int,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .pressScale(interaction, pressedScale = 0.9f)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(R.string.common_delete),
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
