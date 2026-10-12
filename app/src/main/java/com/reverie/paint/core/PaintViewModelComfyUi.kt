/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.reverie.paint.core.comfy.AiButtonLabel
import com.reverie.paint.core.comfy.AiButtonResolver
import com.reverie.paint.core.comfy.AiContext
import com.reverie.paint.core.comfy.AiGenerateMode
import com.reverie.paint.core.comfy.AiSourceKind
import com.reverie.paint.core.comfy.ComfyAuthMode
import com.reverie.paint.core.comfy.ComfyCall
import com.reverie.paint.core.comfy.ComfyClient
import com.reverie.paint.core.comfy.ComfyConnectionError
import com.reverie.paint.core.comfy.ComfyCredentialStore
import com.reverie.paint.core.comfy.ComfyCredentials
import com.reverie.paint.core.comfy.ComfyServerConfig
import com.reverie.paint.core.comfy.ComfyUrl
import com.reverie.paint.core.sync.SyncHttp
import com.reverie.paint.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S1 · AI 辅助绘画的**会话 UI 状态**。
 *
 * 这里只挂 UI 状态镜像, 不持有任何像素 (铁律 1: 文档真身在 C++)。
 * 一期 (S1) 用假数据把三形态跑通, 不连网络 —— 引擎侧接线是 S2 的事。
 *
 * 三形态 (AI-PAINT-UX.md §4.3):
 * - [AiPanelForm.READY]      形态 A · 就绪: 生成方式 / 提示词 / 取材 / 改动幅度
 * - [AiPanelForm.GENERATING] 形态 B · 生成中: 进度 / 排队位次 / 取消。**面板不消失, 画布不锁**
 * - [AiPanelForm.RESULT]     形态 C · 结果待选: 面板收缩成一条变体带
 */
internal enum class AiPanelForm { READY, GENERATING, RESULT }

/** 一个变体 (形态 C 缩略图占位)。一期只有序号, 二期挂 [com.reverie.paint.core.comfy.AiVariant] 的位图 */
internal data class AiUiVariant(val index: Int)

/** 一批生成的步数默认值 —— 对齐真实 SD 的 20~30 步量级 */
internal const val AI_DEFAULT_STEPS = 28

/** 每步的假耗时 (毫秒)。走 Motion 之外的时间常数, 因为它模拟的是"远端算力", 不是 UI 动效 */
private const val AI_FAKE_STEP_MS = 620L

/** 一批结果的变体数 */
private const val AI_FAKE_VARIANT_COUNT = 4

/**
 * AI 面板的全部 UI 状态。
 *
 * 挂在 [PaintViewModel.aiUi] 上 (partial 扩展模式), 这样本文件不需要动
 * `PaintViewModel` 里任何既有字段, 只在主类里加一行挂载点。
 */
internal class AiUiState {
    /** 当前形态 */
    var form by mutableStateOf(AiPanelForm.READY)

    // ---- 形态 A: 意图输入 ----

    /** 提示词。**空串是一等公民输入** (原则 P10: 留空 = 照周边续写), 所以不做非空校验 */
    var prompt by mutableStateOf("")

    /** 生成方式 (画家词汇, 不是模型词汇 —— 原则 P6) */
    var mode by mutableStateOf(AiGenerateMode.FILL)

    /** 取材来源 */
    var sourceKind by mutableStateOf(AiSourceKind.SELECTION)

    /**
     * 改动幅度 0..100。
     *
     * **不参与按钮判定**, 只喂 `KSampler.denoise` —— 见 [AiButtonResolver] 的 KDoc。
     * 这是"连着管线语义"的反面教材, 已在单测里钉死。
     */
    var strengthPercent by mutableIntStateOf(62)

    /** 改动幅度胶囊是否展开为滑杆 (默认收起, 只露一个胶囊) */
    var strengthExpanded by mutableStateOf(false)

    // ---- ⚙ 抽屉: 藏起来的是复杂度, 不是能力 (原则 P7) ----

    var advancedOpen by mutableStateOf(false)

    /** 采样步数。真实存在的参数, 一期只喂假驱动 (进度条总步数跟着它变) */
    var steps by mutableIntStateOf(AI_DEFAULT_STEPS)

    /** 提示词引导强度 */
    var cfg by mutableFloatStateOf(7.0f)

    /** 种子。「每次生成换种子」一期不做, 只给一个能点的换种子 */
    var seed by mutableLongStateOf(104729L)

    // ---- 形态 A: 选区信息 (由选区蒙版异步算出, 见 [refreshAiSelectionBounds]) ----

    var selectionWidth by mutableIntStateOf(0)
    var selectionHeight by mutableIntStateOf(0)

    // ---- 形态 B: 生成中 ----

    /** 当前任务已完成的步数 */
    var runningStep by mutableIntStateOf(0)

    /** 后面还排着几个任务 (连按主按钮是合法的, 不做并发限制) */
    var queuedCount by mutableIntStateOf(0)

    // ---- 形态 C: 结果待选 ----

    val variants = mutableStateListOf<AiUiVariant>()

    /** 当前选中的变体下标。点其他变体 = 换贴图, 不重新提交 */
    var selectedVariant by mutableIntStateOf(0)

    // ---- 面板几何 (动态触摸避让, 见 CanvasTouchView.rebuildUiExclusionRects) ----

    /**
     * 队列驱动器。**全局只允许一个**, 否则连按主按钮会开出多条并发协程把进度搅乱。
     */
    internal var driverJob: Job? = null

    /**
     * 面板在画布坐标系里的实际包围盒, 由 Compose 侧 `onGloballyPositioned` 回写。
     *
     * 既有 `uiExclusionRects` / `isHoverOverUi` 手抄了每个浮窗的静态 dp 尺寸, 而 AI 面板
     * 高度随形态变化 (1~4 行), 表达不了 —— 这里走**动态通道**: 布局变了就重发一个不可变
     * 快照。`@Volatile` 保证渲染线程读到的一定是完整发布的那一个 (发布后不再修改)。
     */
    @Volatile
    var panelBounds: RectF? = null

    /** 会话是否非空 (形态 B/C)。面板的可见条件之一, 见 [aiPanelVisible] */
    val sessionActive: Boolean
        get() = form != AiPanelForm.READY || variants.isNotEmpty()

    /** 一批的总步数 (面板的 `第 N/M 步` 要读它, 不能在 UI 侧写死) */
    val totalSteps: Int get() = steps.coerceAtLeast(1)

    /** 生成中的进度 0..1 */
    val progress: Float
        get() = (runningStep.toFloat() / totalSteps).coerceIn(0f, 1f)
}

/** 主按钮读法 —— 每次重组现算, 绝不在 if-else 里手写分支 (原则 P3) */
internal fun PaintViewModel.aiButtonLabel(): AiButtonLabel =
    AiButtonResolver.resolve(
        AiContext(
            hasSelection = hasSelection,
            sourceKind = aiUi.sourceKind,
            strengthPercent = aiUi.strengthPercent,
            mode = aiUi.mode,
        ),
    )

/** 面板是否该浮出来: 选了 AI 工具, 或有进行中的会话 (入口 E1 / E2 / E3 三条路) */
internal fun PaintViewModel.aiPanelVisible(tool: Tool): Boolean =
    tool == Tool.AI_IMAGE || aiUi.sessionActive

// ---------------------------------------------------------------------------
// 形态切换
// ---------------------------------------------------------------------------

/**
 * 点主按钮。
 *
 * **不禁用**: 生成中再按一次 = 排队 (COMFYUI-DESIGN.md §2.11 的既定决策, 不做并发限制)。
 * 用户连按 3 次就是排 3 个, 这是合法的、甚至是想要的。
 */
internal fun PaintViewModel.submitAiGeneration() {
    val ui = aiUi
    if (ui.form == AiPanelForm.READY) {
        ui.form = AiPanelForm.GENERATING
        ui.runningStep = 0
        ui.queuedCount = 0
    } else {
        ui.queuedCount += 1
    }
    ensureAiQueueDriver()
    // 提交后立刻把工具还给画笔 (P1-21: 画布立刻可画)。
    // 面板不消失 —— 它靠"会话进行中"继续挂着, 这正是非模态的形态 (原则 P4)。
    if (currentToolId == Tool.AI_IMAGE.id) applyTool(Tool.BRUSH.id)
}

/** ⟳ 再生成: 同提示词同参数换一批。旧的变体组保留, 可以回头翻 */
internal fun PaintViewModel.regenerateAi() {
    val ui = aiUi
    ui.queuedCount += 1
    ui.form = AiPanelForm.GENERATING
    ui.runningStep = 0
    ensureAiQueueDriver()
}

/** 取消正在跑的任务。已有变体就退回形态 C, 否则退回形态 A */
internal fun PaintViewModel.cancelAiGeneration() {
    val ui = aiUi
    ui.driverJob?.cancel()
    ui.driverJob = null
    ui.queuedCount = 0
    ui.runningStep = 0
    ui.form = if (ui.variants.isNotEmpty()) AiPanelForm.RESULT else AiPanelForm.READY
}

/** 换一颗种子。种子是唯一值得"手动碰"的隐藏参数 —— 它决定"再生成"能不能真的换出东西 */
internal fun PaintViewModel.rerollAiSeed() {
    aiUi.seed = (aiUi.seed * 1103515245L + 12345L and 0x7FFFFFFF).toLong().coerceAtLeast(1L)
}

/** ✕ 放弃结果 / [×] 收起面板。**零痕迹**: 不产生撤销记录, 画布一帧都没变 */
internal fun PaintViewModel.dismissAi() {
    val ui = aiUi
    ui.driverJob?.cancel()
    ui.driverJob = null
    ui.form = AiPanelForm.READY
    ui.queuedCount = 0
    ui.runningStep = 0
    ui.variants.clear()
    ui.selectedVariant = 0
    if (currentToolId == Tool.AI_IMAGE.id) applyTool(Tool.BRUSH.id)
}

/** ✓ 应用: 形态 C 收工 (真实图层新增是 S2 的事, 一期先走通交互) */
internal fun PaintViewModel.applyAiResult() {
    val ui = aiUi
    ui.variants.clear()
    ui.selectedVariant = 0
    ui.form = AiPanelForm.READY
    ui.driverJob?.cancel()
    ui.driverJob = null
    applyTool(Tool.BRUSH.id)
}

// ---------------------------------------------------------------------------
// 假生成驱动 (S1: 不连网络)
// ---------------------------------------------------------------------------

private fun PaintViewModel.ensureAiQueueDriver() {
    val ui = aiUi
    if (ui.driverJob?.isActive == true) return
    ui.driverJob =
        viewModelScope.launch {
            try {
                while (true) {
                    val total = ui.totalSteps
                    ui.form = AiPanelForm.GENERATING
                    ui.runningStep = 0
                    for (step in 1..total) {
                        ui.runningStep = step
                        delay(AI_FAKE_STEP_MS)
                    }
                    // 一批结果到达: 面板切到形态 C (入口 E3 的"结果召回")
                    ui.variants.clear()
                    repeat(AI_FAKE_VARIANT_COUNT) { i -> ui.variants.add(AiUiVariant(i)) }
                    ui.selectedVariant = 0
                    ui.form = AiPanelForm.RESULT
                    if (ui.queuedCount <= 0) return@launch
                    ui.queuedCount -= 1
                }
            } finally {
                ui.driverJob = null
            }
        }
}

// ---------------------------------------------------------------------------
// 选区包围盒 (真实数据, 非假)
// ---------------------------------------------------------------------------

/**
 * 重算选区包围盒, 给形态 A 第一行显示 `选区 2048×1536`。
 *
 * 蒙版是文档尺寸的逐像素数组 (docWidth × docHeight), 2000×3000 级别的文档就是 6MB,
 * 因此: ① 读蒙版走核心线程 ([runCore], 铁律 2), ② 扫描走 `Dispatchers.Default`,
 * ③ 由调用方节流 (选区拖动中每帧都会变 `hasSelection`)。
 *
 * 读失败 / 尺寸对不上时退回整画布尺寸, 保证第一行永远有话可说。
 */
internal fun PaintViewModel.refreshAiSelectionBounds() {
    val ui = aiUi
    val w = docWidth
    val h = docHeight
    if (!hasSelection || w <= 0 || h <= 0) {
        ui.selectionWidth = 0
        ui.selectionHeight = 0
        return
    }
    var mask: ByteArray? = null
    runCore(render = false) {
        mask = runCatching { ReverieCoreBridge.selectionMask() }.getOrNull()
    }
    val bytes = mask
    val uiRef = ui
    viewModelScope.launch {
        if (bytes == null || bytes.size < w * h) {
            uiRef.selectionWidth = w
            uiRef.selectionHeight = h
            return@launch
        }
        val box =
            withContext(Dispatchers.Default) {
                var minX = w
                var minY = h
                var maxX = -1
                var maxY = -1
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        if (bytes[row + x] > 127) {
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                }
                if (maxX < minX || maxY < minY) intArrayOf(0, 0, w, h) else intArrayOf(minX, minY, maxX + 1, maxY + 1)
            }
        // 期间选区可能已被改掉/清掉, 这时丢弃这次结果, 等下一次刷新
        if (!hasSelection) return@launch
        uiRef.selectionWidth = box[2] - box[0]
        uiRef.selectionHeight = box[3] - box[1]
    }
}

// ---------------------------------------------------------------------------
// 动态触摸避让 (ISSUES.md P5)
// ---------------------------------------------------------------------------

/**
 * 面板把自己的包围盒报给画布, 让笔画不会落在面板上。
 *
 * 既有机制 (`uiExclusionRects` / `isHoverOverUi`) 两处手抄了静态 dp 尺寸, 表达不了
 * "高度随形态变化"的 AI 面板。这里是第二条通道: **面板量自己, 报自己的矩形**。
 *
 * @param boundsPx 根坐标系下的包围盒 (与 [com.reverie.paint.ui.painting.canvas.CanvasTouchView]
 *   的 `width/height` 同一坐标系); 传 null = 面板不可见, 清掉避让
 */
internal fun PaintViewModel.publishAiPanelBounds(boundsPx: RectF?) {
    val ui = aiUi
    val old = ui.panelBounds
    if (boundsPx == null) {
        if (old != null) ui.panelBounds = null
        return
    }
    // 抖动小于半像素就不重发, 省掉拖拽时每帧的一次可见性抖动
    if (old != null &&
        kotlin.math.abs(old.left - boundsPx.left) < 0.5f &&
        kotlin.math.abs(old.top - boundsPx.top) < 0.5f &&
        kotlin.math.abs(old.right - boundsPx.right) < 0.5f &&
        kotlin.math.abs(old.bottom - boundsPx.bottom) < 0.5f
    ) {
        return
    }
    // 发布一个不可变快照: 发布之后不再修改, 渲染线程只读
    ui.panelBounds = RectF(boundsPx)
}

// ---------------------------------------------------------------------------
// 服务器配置 (COMFYUI-DESIGN 服务器页 · 仅配置与测试连接, 不含生成)
// ---------------------------------------------------------------------------

/**
 * 连接状态。**分类是刻意的**: 「没配凭据的 401」「凭据错的 401/403」「连不上」
 * 「服务器报错」要给出互不相同的提示, 混在一起用户会白折腾 (见 ComfyErrorClassifier 的头注释)。
 */
internal enum class ComfyConnectionStatus {
    /** 还没填服务器地址 */
    NOT_CONFIGURED,

    /** 探测进行中 */
    TESTING,

    /** 探测成功, [ComfyServerState.statusDetail] 是版本号 (可能为空) */
    CONNECTED,

    /** 连接失败 / 超时, [ComfyServerState.lastAttemptUrl] 是本次尝试的完整 URL */
    UNREACHABLE,

    /** 服务端要求认证但本次没带认证头 —— 去配凭据, 不是提示密码错 */
    AUTH_NOT_CONFIGURED,

    /** 带了认证但被拒 (401/403) —— 检查用户名密码 */
    AUTH_FAILED,

    /** 其它非 2xx, [ComfyServerState.statusDetail] 是状态码 */
    SERVER_ERROR,

    /** 地址非法, [ComfyServerState.statusDetail] 是原因 */
    INVALID_URL,
}

/** 自定义请求头的一行 (可在 UI 里增删) */
internal class ComfyHeaderRow(
    key: String = "",
    value: String = "",
) {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
}

/**
 * ComfyUI 服务器的 UI 状态镜像。
 *
 * 与 [AiUiState] 分开: 面板状态是「这次生成」的会话, 服务器配置是「这台设备连谁」的持久设置 ——
 * 两者生命周期不同 (关掉面板不清配置, 换服务器不丢会话)。挂载点见 `PaintViewModel.comfyServer`。
 */
internal class ComfyServerState {
    /** 服务器地址 (用户手填, 测试时规范化) */
    var baseUrl by mutableStateOf("")

    var authMode by mutableStateOf(ComfyAuthMode.NONE)

    var username by mutableStateOf("")

    /** 是否已有加密保存的密码 (密码明文不挂在这里) */
    var hasSavedPassword by mutableStateOf(false)

    /** 自定义请求头 (仅 [ComfyAuthMode.CUSTOM_HEADER] 时生效) */
    val extraHeaders = mutableStateListOf<ComfyHeaderRow>()

    var trustSelfSigned by mutableStateOf(false)

    var status by mutableStateOf(ComfyConnectionStatus.NOT_CONFIGURED)

    /** 状态详情: 版本号 / 状态码 / 地址非法原因 */
    var statusDetail by mutableStateOf("")

    /** 本次「测试连接」实际尝试的完整 URL, 连不上时显示给用户 */
    var lastAttemptUrl by mutableStateOf("")

    /** 服务器地址非空即视为「已配置」 */
    val configured: Boolean get() = baseUrl.isNotBlank()
}

/** 启动时读一次服务器配置 */
internal fun PaintViewModel.loadComfySettings() {
    if (!hasAppContext()) return
    val config = ComfyCredentialStore.load(appContext)
    val server = comfyServer
    server.baseUrl = config.baseUrl
    server.authMode = config.authMode
    server.username = config.username
    server.trustSelfSigned = config.trustSelfSigned
    server.extraHeaders.clear()
    config.extraHeaders.forEach { server.extraHeaders.add(ComfyHeaderRow(it.first, it.second)) }
    server.hasSavedPassword = ComfyCredentialStore.loadPassword(appContext).isNotEmpty()
    server.status = ComfyConnectionStatus.NOT_CONFIGURED
    server.statusDetail = ""
    server.lastAttemptUrl = ""
}

/** 新增一行空的请求头 */
internal fun PaintViewModel.addComfyHeaderRow() {
    comfyServer.extraHeaders.add(ComfyHeaderRow())
}

/** 删除指定下标的请求头 */
internal fun PaintViewModel.removeComfyHeaderRow(index: Int) {
    if (index in comfyServer.extraHeaders.indices) comfyServer.extraHeaders.removeAt(index)
}

internal fun PaintViewModel.setComfyTrustSelfSigned(value: Boolean) {
    comfyServer.trustSelfSigned = value
    persistComfySettings()
}

/** 落盘非敏感配置 (地址 / 认证方式 / 用户名 / 请求头 / 自签开关) */
internal fun PaintViewModel.persistComfySettings() {
    if (!hasAppContext()) return
    val server = comfyServer
    val headers = server.extraHeaders.filter { it.key.isNotBlank() }.map { it.key.trim() to it.value }
    ComfyCredentialStore.save(
        appContext,
        ComfyServerConfig(
            baseUrl = server.baseUrl.trim(),
            authMode = server.authMode,
            username = server.username,
            extraHeaders = headers,
            trustSelfSigned = server.trustSelfSigned,
        ),
    )
}

/**
 * 测试连接。
 *
 * [passwordDraft] 是 UI 里刚输入的密码; 为空则回退到已加密保存的那份。
 * 网络调用走 [Dispatchers.IO], **不挂渲染线程** (铁律 2), 也绝不设置 `isBlockingLoading`。
 */
internal fun PaintViewModel.testComfyConnection(passwordDraft: String = "") {
    val server = comfyServer
    persistComfySettings()

    if (server.baseUrl.isBlank()) {
        server.status = ComfyConnectionStatus.NOT_CONFIGURED
        server.statusDetail = ""
        server.lastAttemptUrl = ""
        return
    }

    val normalized = ComfyUrl.normalize(server.baseUrl)
    if (normalized is ComfyUrl.UrlResult.Invalid) {
        server.status = ComfyConnectionStatus.INVALID_URL
        server.statusDetail = normalized.reason
        server.lastAttemptUrl = server.baseUrl.trim()
        return
    }
    val base = (normalized as ComfyUrl.UrlResult.Ok).base

    // Basic 模式下才有密码; 非 Basic 不发送任何 Basic 头
    val effectivePassword =
        when (server.authMode) {
            ComfyAuthMode.BASIC ->
                passwordDraft.ifBlank {
                    if (hasAppContext()) ComfyCredentialStore.loadPassword(appContext) else ""
                }
            else -> ""
        }
    if (server.authMode == ComfyAuthMode.BASIC && passwordDraft.isNotEmpty() && hasAppContext()) {
        ComfyCredentialStore.savePassword(appContext, passwordDraft)
        server.hasSavedPassword = true
    }

    val creds =
        ComfyCredentials(
            baseUrl = base,
            username = if (server.authMode == ComfyAuthMode.BASIC) server.username else null,
            password = if (server.authMode == ComfyAuthMode.BASIC) effectivePassword.ifEmpty { null } else null,
            extraHeaders =
                if (server.authMode == ComfyAuthMode.CUSTOM_HEADER) {
                    server.extraHeaders
                        .filter { it.key.isNotBlank() }
                        .associate { it.key.trim() to it.value }
                } else {
                    emptyMap()
                },
        )
    val httpClient = SyncHttp.client(server.trustSelfSigned)

    server.lastAttemptUrl = ComfyUrl.join(base, "system_stats")
    server.status = ComfyConnectionStatus.TESTING
    server.statusDetail = ""

    viewModelScope.launch {
        val result =
            withContext(Dispatchers.IO) {
                ComfyClient.create(creds, httpClient)?.probe()
                    ?: ComfyCall.Fail(ComfyConnectionError.Unreachable, null)
            }
        // 期间用户可能又改了地址并重测, 丢弃这份过期结果
        if (server.status != ComfyConnectionStatus.TESTING) return@launch
        when (result) {
            is ComfyCall.Ok -> {
                server.status = ComfyConnectionStatus.CONNECTED
                server.statusDetail = result.value
            }
            is ComfyCall.Fail -> {
                when (val error = result.error) {
                    ComfyConnectionError.AuthNotConfigured -> {
                        server.status = ComfyConnectionStatus.AUTH_NOT_CONFIGURED
                        server.statusDetail = ""
                    }
                    ComfyConnectionError.AuthFailed -> {
                        server.status = ComfyConnectionStatus.AUTH_FAILED
                        server.statusDetail = ""
                    }
                    ComfyConnectionError.Unreachable -> {
                        server.status = ComfyConnectionStatus.UNREACHABLE
                        server.statusDetail = result.detail ?: ""
                    }
                    is ComfyConnectionError.ServerError -> {
                        server.status = ComfyConnectionStatus.SERVER_ERROR
                        server.statusDetail = error.code.toString()
                    }
                }
            }
        }
    }
}