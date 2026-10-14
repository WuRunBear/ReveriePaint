/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.core.comfy.AiButtonLabel
import com.reverie.paint.core.comfy.AiButtonResolver
import com.reverie.paint.core.comfy.AiContext
import com.reverie.paint.core.comfy.AiGenerateMode
import com.reverie.paint.core.comfy.AiGenerationParams
import com.reverie.paint.core.comfy.AiSourceKind
import com.reverie.paint.core.comfy.CanvasExportResult
import com.reverie.paint.core.comfy.ComfyAuthMode
import com.reverie.paint.core.comfy.ComfyCall
import com.reverie.paint.core.comfy.ComfyCanvasExporter
import com.reverie.paint.core.comfy.ComfyClient
import com.reverie.paint.core.comfy.ComfyConnectionError
import com.reverie.paint.core.comfy.ComfyCredentialStore
import com.reverie.paint.core.comfy.ComfyCredentials
import com.reverie.paint.core.comfy.ComfyImageRef
import com.reverie.paint.core.comfy.ComfyParamDefaultsReader
import com.reverie.paint.core.comfy.ComfyPromptBuilder
import com.reverie.paint.core.comfy.ComfyServerConfig
import com.reverie.paint.core.comfy.ComfySocket
import com.reverie.paint.core.comfy.ComfySocketEvent
import com.reverie.paint.core.comfy.ComfyUrl
import com.reverie.paint.core.comfy.ComfyWorkflowStore
import com.reverie.paint.core.sync.SyncHttp
import com.reverie.paint.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * AI 辅助绘画的**会话 UI 状态**。
 *
 * 这里只挂 UI 状态镜像, 不持有文档像素 (铁律 1: 文档真身在 C++)。变体条里的位图是
 * ComfyUI 返回的结果缩略图, 属于网络产物而非文档数据, 因此可以安全地挂在这里。
 *
 * 三形态 (AI-PAINT-UX.md §4.3):
 * - [AiPanelForm.READY]      形态 A · 就绪: 生成方式 / 提示词 / 取材 / 改动幅度
 * - [AiPanelForm.GENERATING] 形态 B · 生成中: 进度 / 排队位次 / 取消。**面板不消失, 画布不锁**
 * - [AiPanelForm.RESULT]     形态 C · 结果待选: 面板收缩成一条变体带
 */
internal enum class AiPanelForm { READY, GENERATING, RESULT }

/** 一个变体: 序号 + 真实结果缩略图 (位图由生成链路填充, UI 只负责画) */
internal data class AiUiVariant(
    val index: Int,
    val bitmap: Bitmap? = null,
)

/** 一批生成的步数默认值 —— 真实生成时由工作流步数 / ComfyUI progress 消息覆盖 */
internal const val AI_DEFAULT_STEPS = 28

/** 改动幅度未调整时滑杆与未绑定态展示用的默认值 —— 仅作显示, 不代表会注入工作流 */
internal const val AI_DEFAULT_STRENGTH = 62

/** 「应用成图层」序号持久化用的 prefs 名与 key (与自动保存历史的计数无关) */
private const val AI_APPLY_PREFS = "paint_prefs"
private const val AI_APPLY_COUNT_KEY = "ai_applied_layer_count"

/** 图层名里序号与工作流名之间的分隔符, 形如 `001 · one` */
private const val AI_LAYER_NAME_SEPARATOR = " · "

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

    /**
     * 负面提示词。**空串是合法的** —— 与提示词一样不做校验, 留空即不注入 (保持模板原值)。
     * 只有工作流绑定了负面提示词角色时才写进工作流。
     */
    var negativePrompt by mutableStateOf("")

    /** 负面提示词输入是否展开 (默认收起, 只露一行折叠入口) */
    var negativeExpanded by mutableStateOf(false)

    /** 生成方式 (画家词汇, 不是模型词汇 —— 原则 P6) */
    var mode by mutableStateOf(AiGenerateMode.FILL)

    /** 取材来源 */
    var sourceKind by mutableStateOf(AiSourceKind.SELECTION)

    /**
     * 改动幅度 0..100。**null 表示用户尚未调整** —— 提交时不注入 `KSampler.denoise`,
     * 沿用工作流自带的值 (与 steps/cfg/seed 的「不拖就不覆写」同一语义)。
     *
     * **不参与按钮判定**, 只喂 `KSampler.denoise` —— 见 [AiButtonResolver] 的 KDoc。
     * 这是"连着管线语义"的反面教材, 已在单测里钉死。
     */
    var strengthPercent by mutableStateOf<Int?>(null)

    // ---- ⚙ 抽屉: 藏起来的是复杂度, 不是能力 (原则 P7) ----

    var advancedOpen by mutableStateOf(false)

    /**
     * 采样步数。**走绑定注入** —— 只有当前工作流绑定了 steps 字段时, 面板滑块才可用,
     * 调整即随提交写进工作流; 未绑定时禁用 (见 `AiGeneratePanel`)。
     * 生成中作为「工作流真实步数」的初值, 首个 progress 消息到达后由 ComfyUI 实测值覆盖。
     */
    var steps by mutableIntStateOf(AI_DEFAULT_STEPS)

    /**
     * 提示词引导强度。**走绑定注入**, 与步数同样的可用性规则: 未绑定即禁用。
     */
    var cfg by mutableFloatStateOf(7.0f)

    /** 种子。**走 SEED 角色绑定注入**, 未绑定时「换种子」禁用 */
    var seed by mutableLongStateOf(104729L)

    // ---- 形态 A: 选区信息 (由选区蒙版异步算出, 见 [refreshAiSelectionBounds]) ----

    var selectionWidth by mutableIntStateOf(0)
    var selectionHeight by mutableIntStateOf(0)

    // ---- 形态 B: 生成中 ----

    /** 当前正在跑的任务已完成的步数 (来自 ComfyUI `progress` 消息) */
    var runningStep by mutableIntStateOf(0)

    /** 这一批的总步数。提交时先填工作流真实步数, 首个 progress 消息到达后用 `max` 覆盖 */
    var totalSteps by mutableIntStateOf(AI_DEFAULT_STEPS)

    /** 后面还排着几个任务 (连按主按钮是合法的, 不做并发限制) */
    var queuedCount by mutableIntStateOf(0)

    // ---- 失败提示 (两种来源分开存: 我们的提示走资源, ComfyUI 原文原样显示) ----

    /** 我们自己产生的失败提示 (string res), 由 UI 层 `stringResource` 翻译 */
    var errorRes by mutableStateOf<Int?>(null)

    /** ComfyUI 返回的**原始错误文本**, 不翻译不截断 —— 用户排查的唯一依据 */
    var errorRaw by mutableStateOf<String?>(null)

    // ---- 形态 C: 结果待选 ----

    val variants = mutableStateListOf<AiUiVariant>()

    /** 当前选中的变体下标。点其他变体 = 换贴图, 不重新提交 */
    var selectedVariant by mutableIntStateOf(0)

    /**
     * 「应用成图层」的累计次数, 决定图层序号并持久化到 `paint_prefs`。
     *
     * `-1` = 尚未从 prefs 读取(首次应用时惰性加载); 只按**成功落层**的次数递增, 与生成次数、
     * 自动保存历史(8 张快照, 用户可改)完全无关。抢占式 +1 见 [reserveAiLayerSequence]。
     */
    internal var appliedLayerCount: Int = -1

    // ---- 面板几何 (动态触摸避让, 见 CanvasTouchView.rebuildUiExclusionRects) ----

    /**
     * 生成驱动 Job (仅用于「会话是否在跑」的粗判与取消; 真实任务表见 [activeTasks])。
     */
    internal var driverJob: Job? = null

    // ---- 真实生成链路 (全部由 createComfySession 一族的函数维护, 不是 Compose 状态) ----

    /**
     * 已提交但尚未出结果的任务表 (`prompt_id -> 任务`)。
     *
     * 用 [LinkedHashMap] 保序: 最先提交的排在前面, 正好对应 ComfyUI FIFO 的执行顺序,
     * 因此 `values.first()` 就是「正在跑的那个」。
     */
    internal val activeTasks = LinkedHashMap<String, AiTask>()

    /** 还在「导出 → 上传 → 提交」途中的提交数, 用来避免过早判定「全部结束」 */
    internal var pendingSubmissions = 0

    /**
     * 会话令牌。取消 / 放弃 / 应用时 +1, 使所有在途提交与取图协程作废 ——
     * 防止「点了取消还冒出结果」。
     */
    internal var sessionToken = 0

    /** 当前会话的 WebSocket (一个 clientId 一条), 空闲时不自动断, 由取消/退出显式关闭 */
    internal var socket: ComfySocket? = null

    /** 本次会话的 clientId (提交与 WebSocket 必须一致, 否则收不到进度) */
    internal var clientId: String? = null

    /** 最近一次建好的 HTTP 客户端, 取消时用它 `POST /interrupt` */
    internal var activeClient: ComfyClient? = null

    /**
     * 面板在画布坐标系里的实际包围盒, 由 Compose 侧 `onGloballyPositioned` 回写。
     *
     * 既有 `uiExclusionRects` / `isHoverOverUi` 手抄了每个浮窗的静态 dp 尺寸, 而 AI 面板
     * 高度随形态变化 (1~4 行), 表达不了 —— 这里走**动态通道**: 布局变了就重发一个不可变
     * 快照。`@Volatile` 保证渲染线程读到的一定是完整发布的那一个 (发布后不再修改)。
     */
    @Volatile
    var panelBounds: RectF? = null

    /**
     * 面板是否应当保持打开。
     *
     * **不能靠会话状态派生可见性**: 提交后会自动切回笔刷工具 (此时 `tool != AI_IMAGE`),
     * 若此时取消生成, 表单回到 [AiPanelForm.READY] 且没有变体, 派生出来的"会话非空"
     * 会立刻变 false, **整个面板被藏起来** —— 用户点了取消却像是关掉了对话框。
     *
     * 面板只在两种情况下关闭: 用户点 [×] (见 [dismissAi]) 或结果已应用
     * (见 [applyAiResult])。取消生成只是退出生成态, 面板留着让用户接着改提示词重试。
     */
    var panelOpen by mutableStateOf(false)

    /**
     * 会话数据已被应用/放弃, 但**要等面板退出动画结束再清**。
     *
     * 若在关闭的同一帧就把 `form` 清成 READY、把变体清空, `AnimatedVisibility` 的
     * 退场动画会在变体带淡出的同时渲染成"就绪态输入区" —— 用户看到的是
     * "先退回上一个界面, 再关闭", 很别扭。所以这里只做一个待清理标记,
     * 真正的清理由 [onAiPanelBecameVisible] 在面板下次可见时执行。
     */
    var sessionConsumed by mutableStateOf(false)

    /** 生成中的进度 0..1 */
    val progress: Float
        get() = (runningStep.toFloat() / totalSteps.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/**
 * 一次已提交的生成任务。
 *
 * @property promptId ComfyUI 分配的 prompt_id, 结果归属的主键
 * @property outputNodeId 工作流绑定的产图节点 (node 匹配的兜底归属依据)
 * @property awaitingImages 已收到 executed 且正在取图 —— 用来防止随后的
 *   `executing node=null` 在取图完成前把任务提前收尾
 */
internal class AiTask(
    val promptId: String,
    val outputNodeId: String,
    var awaitingImages: Boolean = false,
)
/** 主按钮读法 —— 每次重组现算, 绝不在 if-else 里手写分支 (原则 P3) */
internal fun PaintViewModel.aiButtonLabel(): AiButtonLabel =
    AiButtonResolver.resolve(
        AiContext(
            hasSelection = hasSelection,
            sourceKind = aiUi.sourceKind,
            strengthPercent = aiUi.strengthPercent ?: AI_DEFAULT_STRENGTH,
            mode = aiUi.mode,
        ),
    )

/** 面板是否该浮出来: 选了 AI 工具, 或用户主动打开过 (入口 E1 / E2 / E3 三条路) */
internal fun PaintViewModel.aiPanelVisible(tool: Tool): Boolean =
    tool == Tool.AI_IMAGE || aiUi.panelOpen

// ---------------------------------------------------------------------------
// 形态切换
// ---------------------------------------------------------------------------

/**
 * 点主按钮。
 *
 * **不禁用**: 生成中再按一次 = 排队 (COMFYUI-DESIGN.md §2.11 的既定决策, 不做并发限制)。
 * 用户连按 3 次就是排 3 个 —— 每个提交立即发出, 结果按 `prompt_id` 各自归属, 互不串批。
 *
 * 未配置服务器 / 未导入工作流时**明确提示**, 绝不静默失败 (面板此时通常已隐藏生成控件,
 * 这里兜住「从结果带点再生成」等旁路入口)。
 */
internal fun PaintViewModel.submitAiGeneration() {
    val ui = aiUi
    ui.panelOpen = true
    if (!requireAiReady(ui)) {
        if (currentToolId == Tool.AI_IMAGE.id) applyTool(Tool.BRUSH.id)
        return
    }
    ui.errorRaw = null
    ui.errorRes = null
    if (ui.form == AiPanelForm.GENERATING || ui.activeTasks.isNotEmpty()) {
        // 已有任务在跑: 这一批排队 (不做并发限制, 连按就是排多个), 结果累加到变体带
        ui.queuedCount = ui.activeTasks.size
    } else {
        ui.form = AiPanelForm.GENERATING
        ui.runningStep = 0
        ui.queuedCount = 0
    }
    launchAiSubmission()
    // 提交后立刻把工具还给画笔 (P1-21: 画布立刻可画)。
    // 面板不消失 —— 靠 [aiUi.panelOpen] 继续挂着, 这正是非模态的形态 (原则 P4)。
    if (currentToolId == Tool.AI_IMAGE.id) applyTool(Tool.BRUSH.id)
}

/** ⟳ 再生成: 同提示词同参数换一批 (丢弃上一批变体, 结果带被新一批替换) */
internal fun PaintViewModel.regenerateAi() {
    val ui = aiUi
    ui.panelOpen = true
    if (!requireAiReady(ui)) return
    ui.errorRaw = null
    ui.errorRes = null
    if (ui.activeTasks.isNotEmpty() || ui.pendingSubmissions > 0) {
        // 已有任务在跑: 排到它后面
        ui.queuedCount = ui.activeTasks.size + ui.pendingSubmissions
    } else {
        ui.variants.clear()
        ui.selectedVariant = 0
        ui.form = AiPanelForm.GENERATING
        ui.runningStep = 0
        ui.queuedCount = 0
    }
    launchAiSubmission()
}

/**
 * 取消正在跑的任务(s)。
 *
 * 真实取消: `POST /interrupt` 让 ComfyUI 停下远端执行, 关闭 WebSocket, 清本地任务表。
 * **只退出生成态, 不关面板** —— 面板留着让用户接着改提示词重试; 已有变体退回形态 C。
 */
internal fun PaintViewModel.cancelAiGeneration() {
    val ui = aiUi
    abortAiSession(ui, interrupt = true)
    ui.driverJob?.cancel()
    ui.driverJob = null
    ui.form = if (ui.variants.isNotEmpty()) AiPanelForm.RESULT else AiPanelForm.READY
    // 注意: 这里**不要**动 panelOpen。取消 ≠ 关闭。
}

/** 换一颗种子。种子是唯一值得"手动碰"的隐藏参数 —— 它决定"再生成"能不能真的换出东西 */
internal fun PaintViewModel.rerollAiSeed() {
    aiUi.seed = (aiUi.seed * 1103515245L + 12345L and 0x7FFFFFFF).toLong().coerceAtLeast(1L)
}

/** ✕ 放弃结果 / [×] 收起面板。**零痕迹**: 不产生撤销记录, 画布一帧都没变 */
internal fun PaintViewModel.dismissAi() {
    val ui = aiUi
    abortAiSession(ui, interrupt = true)
    ui.driverJob?.cancel()
    ui.driverJob = null
    ui.errorRaw = null
    ui.errorRes = null
    // 只标记待清理, 不在这一帧改 form / 清变体 —— 否则退场动画会闪出就绪态 (见 sessionConsumed)
    ui.sessionConsumed = true
    ui.panelOpen = false
    if (currentToolId == Tool.AI_IMAGE.id) applyTool(Tool.BRUSH.id)
}

/**
 * ✓ 应用: 把当前选中的变体铺满画布落成一张新图层, 再收工。
 *
 * **不复用 [importImageToNewLayer]** —— 那条路径会回收入参位图、强制 80% 内缩、且不组撤销宏,
 * 三点都不符合本需求。这里新写一条专用路径:
 * - 只读变体位图, 用 [ImageImportHelper.createScaledMutableCopy] 生成独立副本, 原图留给变体带重复使用
 * - 等比缩放**铺满画布**([ImageImportHelper.calculateCoverPlacement]); 比例不同时居中裁切, 绝不拉伸变形
 * - `addLayer` + `stampBitmap` **不包撤销宏**(撤销需两步: 先撤盖章, 再撤建层, 与 [importImageToNewLayer] 一致)。
 *   撤销宏打开期间引擎会一直持有调度器 barrierLock, 而 `addLayer` 内部的同步投影等待会永久自旋, 见 op 内注释
 *
 * 引擎调用全部走 [runCore](铁律 2): 缩放与通道交换都在核心线程, 不阻塞触摸分发。
 * 位图不可用 / 落层失败时保持结果带并给出错误, 不做「已建层却没盖章」的半更新。
 */
internal fun PaintViewModel.applyAiResult() {
    val ui = aiUi
    // 先取选中变体; 越界或该变体尚未解出位图时, 退回第一个有位图的变体
    val variant = ui.variants.getOrNull(ui.selectedVariant)?.takeIf { it.bitmap != null }
        ?: ui.variants.firstOrNull { it.bitmap != null }
    val source = variant?.bitmap
    if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) {
        // 没有可用位图: 明确反馈并留在结果带, 不静默关掉会话
        ui.errorRes = R.string.ai_error_apply_failed
        ui.errorRaw = null
        ui.panelOpen = true
        return
    }

    // 抢占序号(内存 +1, 防连点重名); 成功后落盘, 失败回滚 —— 保证「成功一次才 +1」
    val sequence = reserveAiLayerSequence()
    val fallbackName = if (hasAppContext()) appContext.getString(R.string.layer_default_ai_image) else ""
    val workflowName = comfyWorkflows.current?.name?.trim().orEmpty().ifEmpty { fallbackName }
    val layerName = formatAiLayerName(sequence, workflowName)
    val applied = java.util.concurrent.atomic.AtomicBoolean(false)

    // 应用即收工: 停后续生成并淡出面板 (与既有交互一致); 落层失败时在 after 里重新打开并报错
    abortAiSession(ui, interrupt = true)
    ui.driverJob?.cancel()
    ui.driverJob = null
    ui.sessionConsumed = true
    ui.panelOpen = false
    if (currentToolId == Tool.AI_IMAGE.id) applyTool(Tool.BRUSH.id)

    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD)
    }
    clearLayerSelection()

    runCore(
        after = {
            clearLayerSelection()
            if (applied.get()) {
                commitAiLayerSequence(sequence)
                notifyLayerChanged(pixelChanged = true)
            } else {
                rollbackAiLayerSequence(sequence)
                // 文档已关闭(核心已释放)时不再弹面板, 避免在主页凭空打开 AI 面板
                if (renderHandler != null) {
                    ui.sessionConsumed = false
                    ui.panelOpen = true
                    ui.form = AiPanelForm.RESULT
                    ui.errorRes = R.string.ai_error_apply_failed
                    ui.errorRaw = null
                }
            }
        },
    ) {
        try {
            val docW = coreW
            val docH = coreH
            if (docW <= 0 || docH <= 0) {
                return@runCore
            }
            val placement =
                ImageImportHelper.calculateCoverPlacement(docW, docH, source.width, source.height)
            val work =
                ImageImportHelper.createScaledMutableCopy(source, placement.targetW, placement.targetH)
            if (work == null) {
                return@runCore
            }
            // 【铁律】这里**不能**用 beginUndoMacro/endUndoMacro 包住 addLayer:
            // KisLegacyUndoAdapter::beginMacro 会调用 KisImage::barrierLock() 并一直持有到
            // endMacro (kis_legacy_undo_adapter.cpp), 而 addLayer 内部的
            // recompositeProjection() = refreshGraphAsync + waitForDone 会同步等待被该 barrier
            // 卡住的投影任务 -> 渲染线程在 KisUpdateScheduler::waitForDone 的自旋里永久卡死,
            // 之后所有 runCore 任务(含再次生成的第一步 renderDocumentBitmap)再也排不到。
            // C++ 侧既有用法一律在 endUndoMacro 之后才 recompositeProjection; 本路径与
            // importImageToNewLayer 对齐: 只记录两条独立撤销(先撤盖章, 再撤建层)。
            try {
                // `addLayer` 在 Kotlin 侧是 Unit(不返回下标), 用层数变化判定是否真的建成了
                val before = ReverieCoreBridge.layerCount()
                ReverieCoreBridge.addLayer(layerName)
                val afterCount = ReverieCoreBridge.layerCount()
                if (afterCount <= before) {
                    return@runCore
                }
                val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(work)
                try {
                    ReverieCoreBridge.stampBitmap(placement.x, placement.y, stampBmp)
                } finally {
                    if (stampBmp !== work && !stampBmp.isRecycled) stampBmp.recycle()
                }
                applied.set(true)
            } finally {
                if (!work.isRecycled) work.recycle()
            }
        } catch (t: Throwable) {
            // 保持 applied=false, 原样抛回 runCore —— 由它既有的 catch 记录并继续派发 after()
            throw t
        }
    }
}

// ---------------------------------------------------------------------------
// 「应用成图层」的图层名与序号 (纯逻辑, 见 AiLayerNamingTest)
// ---------------------------------------------------------------------------

/** 图层序号: 至少三位补零; 超过 999 自然升位, 不截断 */
internal fun formatAiLayerSequence(sequence: Int): String =
    sequence.coerceAtLeast(0).toString().padStart(3, '0')

/**
 * 图层名 = 「序号 · 工作流名」(例: `001 · one`)。
 * 工作流名为空时退化为纯序号 —— 调用方应传入默认文案(见 [R.string.layer_default_ai_image])兜底。
 */
internal fun formatAiLayerName(sequence: Int, workflowName: String): String {
    val seq = formatAiLayerSequence(sequence)
    return if (workflowName.isBlank()) seq else "$seq$AI_LAYER_NAME_SEPARATOR$workflowName"
}

/** 读取持久化的应用次数, 首次调用时从 `paint_prefs` 惰性加载 */
private fun PaintViewModel.aiAppliedLayerCount(): Int {
    val ui = aiUi
    if (ui.appliedLayerCount < 0) {
        val loaded =
            if (hasAppContext()) {
                appContext
                    .getSharedPreferences(AI_APPLY_PREFS, android.content.Context.MODE_PRIVATE)
                    .getInt(AI_APPLY_COUNT_KEY, 0)
            } else {
                0
            }
        ui.appliedLayerCount = loaded.coerceAtLeast(0)
    }
    return ui.appliedLayerCount
}

/** 抢占下一个序号: 先在内存 +1, 成功落盘 / 失败回滚都由调用方决定 */
private fun PaintViewModel.reserveAiLayerSequence(): Int =
    (aiAppliedLayerCount() + 1).also { aiUi.appliedLayerCount = it }

/** 落层成功: 把抢占的序号固化到内存与 `paint_prefs` */
private fun PaintViewModel.commitAiLayerSequence(sequence: Int) {
    aiUi.appliedLayerCount = sequence
    if (hasAppContext()) {
        appContext
            .getSharedPreferences(AI_APPLY_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt(AI_APPLY_COUNT_KEY, sequence)
            .apply()
    }
}

/** 落层失败: 回滚抢占的序号, 让下一次成功应用仍拿到同一个编号 */
private fun PaintViewModel.rollbackAiLayerSequence(sequence: Int) {
    if (aiUi.appliedLayerCount == sequence) {
        aiUi.appliedLayerCount = sequence - 1
    }
}

/**
 * 面板刚从不可见变为可见时调用 (由 [com.reverie.paint.ui.painting.panels.AiGeneratePanel] 的
 * `LaunchedEffect(visible)` 触发)。执行 [AiUiState.sessionConsumed] 挂起的清理。
 */
internal fun PaintViewModel.onAiPanelBecameVisible() {
    val ui = aiUi
    if (!ui.sessionConsumed) return
    ui.sessionConsumed = false
    ui.form = AiPanelForm.READY
    ui.queuedCount = 0
    ui.runningStep = 0
    ui.variants.clear()
    ui.selectedVariant = 0
    ui.errorRaw = null
    ui.errorRes = null
}

/**
 * ViewModel 销毁时调用 (由 `PaintViewModel.onCleared` 转入)。
 *
 * 只做本地资源收尾 —— **不发 interrupt**: 销毁是进程级事件, 让远端把已排队的工作跑完
 * 更符合预期; 用户主动「取消」才走 [abortAiSession] 的 interrupt 路径。
 */
internal fun PaintViewModel.shutdownAiComfy() {
    val ui = aiUi
    ui.sessionToken += 1
    ui.activeTasks.clear()
    ui.pendingSubmissions = 0
    closeAiSocket(ui)
}

// ---------------------------------------------------------------------------
// 真实生成链路 (S2): 导出画布 → 上传 → 提交 → WebSocket 监听 → 取图
// ---------------------------------------------------------------------------

/**
 * 发起一次提交。
 *
 * **会话 token**: 每次提交在开始时记下 [AiUiState.sessionToken], 之后每个挂起点都校验它 ——
 * 取消/放弃会 +1, 于是所有在途提交自动作废, 不会在用户已经取消之后还往 UI 里塞结果。
 * 这是「点取消却仍冒出结果」的唯一可靠防线。
 *
 * 线程: 网络/IO/编解码全部 [Dispatchers.IO] (由各自的实现保证), 引擎调用走 [runCore]
 * (见 [ComfyCanvasExporter]), **绝不挂渲染线程**; 也绝不设置 `isBlockingLoading`。
 */
private fun PaintViewModel.launchAiSubmission() {
    val vm = this
    val ui = aiUi
    val token = ui.sessionToken
    ui.pendingSubmissions += 1
    viewModelScope.launch {
        try {
            val workflow =
                vm.comfyWorkflows.current
                    ?: run { vm.failLocal(ui, R.string.ai_error_no_workflow); return@launch }
            val binding =
                workflow.binding
                    ?: run { vm.failLocal(ui, R.string.ai_error_workflow_unusable); return@launch }
            val client =
                vm.obtainComfyClient()
                    ?: run { vm.failLocal(ui, R.string.ai_error_no_server); return@launch }
            ui.activeClient = client
            vm.ensureAiSocket(ui, client)

            val rawJson =
                withContext(Dispatchers.IO) {
                    runCatching {
                        ComfyWorkflowStore.readJson(vm.appContext, workflow.id)?.let { JSONObject(it) }
                    }.getOrNull()
                } ?: run { vm.failLocal(ui, R.string.ai_error_workflow_unusable); return@launch }
            if (ui.sessionToken != token) return@launch

            // 只有绑定了图片角色才导出画布并上传; 未绑定 = 纯文生图, 不上传
            var imageName: String? = null
            if (binding.base.image != null) {
                when (val exported = ComfyCanvasExporter.export(vm)) {
                    is CanvasExportResult.Ok -> {
                        val upload =
                            client.uploadImage(exported.bytes, ComfyCanvasExporter.UPLOAD_FILENAME)
                        when (upload) {
                            is ComfyCall.Ok -> imageName = upload.value.filename
                            is ComfyCall.Fail -> {
                                vm.failComfy(ui, upload, R.string.ai_error_upload_failed)
                                return@launch
                            }
                        }
                    }
                    else -> {
                        vm.failLocal(ui, R.string.ai_error_export_failed)
                        return@launch
                    }
                }
                if (ui.sessionToken != token) return@launch
            }

            val defaults = ComfyParamDefaultsReader.read(rawJson, binding)
            // 显示用的总步数必须与**实际注入值**一致: 绑定了就以面板值为准 (用户可能刚改过),
            // 未绑定才退回工作流值。此前无条件用 defaults (工作流值) 覆盖, 导致用户把步数
            // 从 28 改成 8 后, 实际按 8 跑但进度条显示 28。
            // 真值最终由 socket 的 progress.max 校正。
            ui.totalSteps =
                (if (binding.steps != null) ui.steps else defaults.steps ?: ui.steps).coerceAtLeast(1)
            val params =
                AiGenerationParams(
                    prompt = ui.prompt,
                    negativePrompt = ui.negativePrompt.takeIf { it.isNotBlank() },
                    imageName = imageName,
                    strength = ui.strengthPercent,
                    steps = binding.steps?.let { ui.steps },
                    cfg = binding.cfg?.let { ui.cfg },
                    seed = binding.seed?.let { ui.seed },
                )
            val promptJson = ComfyPromptBuilder.build(rawJson, binding, params).toString()
            val clientId = vm.ensureAiClientId(ui)

            when (val submitted = client.submitPrompt(promptJson, clientId)) {
                is ComfyCall.Ok -> {
                    if (ui.sessionToken != token) return@launch
                    ui.activeTasks[submitted.value] = AiTask(submitted.value, binding.base.outputNodeId)
                    ui.queuedCount = (ui.activeTasks.size - 1).coerceAtLeast(0)
                }
                is ComfyCall.Fail -> vm.failComfy(ui, submitted, R.string.ai_error_submit_failed)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            vm.failLocal(ui, R.string.ai_error_submit_failed)
        } finally {
            if (ui.sessionToken == token) {
                if (ui.pendingSubmissions > 0) ui.pendingSubmissions -= 1
                vm.maybeEndAiGeneration(ui)
            }
        }
    }
}

/** Socket 事件入口 (已在主线程), 按 prompt_id / node 把结果归属到对应任务 */
private fun PaintViewModel.handleAiSocketEvent(event: ComfySocketEvent) {
    val ui = aiUi
    when (event) {
        is ComfySocketEvent.ExecutionStart -> ui.runningStep = 0

        is ComfySocketEvent.Progress -> {
            // 只有正在执行的那个 prompt 会推 progress; 不在任务表里的丢弃
            if (event.promptId == null || ui.activeTasks.containsKey(event.promptId)) {
                ui.totalSteps = event.max.coerceAtLeast(1)
                ui.runningStep = event.value.coerceAtLeast(0)
            }
        }

        is ComfySocketEvent.Executing -> {
            // node == null: 该 prompt 执行结束。若已收到 executed 正在取图, 交给取图流程收尾
            if (event.node == null) {
                val task = findAiTask(ui, event.promptId, null)
                if (task != null && !task.awaitingImages) finishAiTask(ui, task.promptId)
            }
        }

        is ComfySocketEvent.Executed -> {
            if (event.images.isEmpty()) return
            val task = findAiTask(ui, event.promptId, event.node) ?: return
            task.awaitingImages = true
            fetchAiResults(task, event.images)
        }

        is ComfySocketEvent.ExecutionError -> {
            val task = findAiTask(ui, event.promptId, null)
            // 带了 prompt_id 却不属于本会话: 不是我们的错, 不认领也不打扰用户
            if (task == null && event.promptId != null) return
            // ComfyUI 原文, 不翻译不截断 —— 用户排查的唯一依据
            ui.errorRaw = event.message.ifBlank { event.type }.ifBlank { null }
            ui.errorRes = null
            if (task != null) finishAiTask(ui, task.promptId) else maybeEndAiGeneration(ui)
        }
    }
}

/**
 * 结果归属。
 *
 * 首选 `prompt_id` (ComfyUI 每条消息都带, 多任务下最可靠): **消息带了 prompt_id 就只看它**,
 * 不在任务表里说明是别处的任务, 我们明确不认领 —— 这是「不串批」的关键。
 * 只有消息没带 prompt_id 时, 才退回用产图节点 id 匹配 `outputNodeId`, 再退回唯一在跑的任务。
 */
private fun findAiTask(
    ui: AiUiState,
    promptId: String?,
    node: String?,
): AiTask? {
    if (promptId != null) return ui.activeTasks[promptId]
    return node?.let { n -> ui.activeTasks.values.firstOrNull { it.outputNodeId == n } }
        ?: ui.activeTasks.values.firstOrNull()
}

/** 取图 → 解码 → 塞进变体条, 完成后收尾该任务 (网络与解码都在 IO) */
private fun PaintViewModel.fetchAiResults(
    task: AiTask,
    refs: List<ComfyImageRef>,
) {
    val vm = this
    val ui = aiUi
    val token = ui.sessionToken
    val client = ui.activeClient
    viewModelScope.launch {
        try {
            for (ref in refs) {
                if (ui.sessionToken != token) return@launch
                if (client == null) break
                when (val fetched = client.fetchImage(ref)) {
                    is ComfyCall.Ok -> {
                        val bitmap =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    BitmapFactory.decodeByteArray(fetched.value, 0, fetched.value.size)
                                }.getOrNull()
                            }
                        if (bitmap == null) {
                            ui.errorRes = R.string.ai_error_decode_failed
                        } else if (ui.sessionToken == token) {
                            ui.variants.add(AiUiVariant(index = ui.variants.size, bitmap = bitmap))
                        }
                    }
                    is ComfyCall.Fail -> ui.errorRes = R.string.ai_error_fetch_failed
                }
            }
        } finally {
            if (ui.sessionToken == token) vm.finishAiTask(ui, task.promptId)
        }
    }
}

/** 任务收尾: 移除任务, 刷新排队数, 可能结束整批 */
private fun PaintViewModel.finishAiTask(
    ui: AiUiState,
    promptId: String,
) {
    if (ui.activeTasks.remove(promptId) == null) return
    ui.queuedCount = (ui.activeTasks.size - 1).coerceAtLeast(0)
    maybeEndAiGeneration(ui)
}

/**
 * 所有任务与在途提交都结束后收尾: 退回形态 C (有变体) 或形态 A, 并关闭 WebSocket。
 * 有任何一个条件不满足就原地不动 —— 绝不把还在跑的任务提前判死。
 */
private fun PaintViewModel.maybeEndAiGeneration(ui: AiUiState) {
    if (ui.pendingSubmissions > 0 || ui.activeTasks.isNotEmpty()) return
    ui.runningStep = 0
    ui.form = if (ui.variants.isNotEmpty()) AiPanelForm.RESULT else AiPanelForm.READY
    closeAiSocket(ui)
}

/** 连接失败 / 断开: 明确报错, 清任务, 绝不把任务永远吊在「生成中」 */
private fun PaintViewModel.handleAiSocketFailure(
    error: ComfyConnectionError,
    detail: String?,
) {
    val ui = aiUi
    ui.socket = null
    if (ui.activeTasks.isEmpty() && ui.pendingSubmissions == 0) return
    ui.sessionToken += 1
    ui.activeTasks.clear()
    ui.pendingSubmissions = 0
    ui.queuedCount = 0
    ui.runningStep = 0
    ui.errorRes = errorResOf(error)
    ui.errorRaw = null
    ui.form = if (ui.variants.isNotEmpty()) AiPanelForm.RESULT else AiPanelForm.READY
}

/**
 * 中止会话: 作废在途提交 (token +1), 清任务表, 关 WebSocket, 按需 `POST /interrupt`。
 * 取消 / 放弃 / 应用 / 面板可见性清理都走这里, 行为一致。
 */
private fun PaintViewModel.abortAiSession(
    ui: AiUiState,
    interrupt: Boolean,
) {
    val hasTasks = ui.activeTasks.isNotEmpty()
    val client = ui.activeClient
    ui.sessionToken += 1
    ui.activeTasks.clear()
    ui.pendingSubmissions = 0
    ui.queuedCount = 0
    ui.runningStep = 0
    closeAiSocket(ui)
    if (interrupt && hasTasks && client != null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { client.interrupt() } }
        }
    }
}

private fun closeAiSocket(ui: AiUiState) {
    ui.socket?.close()
    ui.socket = null
}

// ---------------------------------------------------------------------------
// 提交前置条件 / 连接材料 / 错误文案
// ---------------------------------------------------------------------------

/** 未配置或未导入时给出明确提示, 返回 false 表示不能提交 */
private fun PaintViewModel.requireAiReady(ui: AiUiState): Boolean {
    if (!hasAppContext()) return failLocal(ui, R.string.ai_error_not_ready)
    if (comfyServer.baseUrl.isBlank()) return failLocal(ui, R.string.ai_error_no_server)
    val workflow = comfyWorkflows.current ?: return failLocal(ui, R.string.ai_error_no_workflow)
    if (workflow.binding == null) return failLocal(ui, R.string.ai_error_workflow_unusable)
    return true
}

/** 用当前服务器配置建客户端; 地址非法 / 没配地址返回 null */
private fun PaintViewModel.obtainComfyClient(): ComfyClient? {
    if (!hasAppContext()) return null
    val server = comfyServer
    if (server.baseUrl.isBlank()) return null
    val normalized = ComfyUrl.normalize(server.baseUrl)
    if (normalized !is ComfyUrl.UrlResult.Ok) return null
    val password =
        when (server.authMode) {
            ComfyAuthMode.BASIC -> ComfyCredentialStore.loadPassword(appContext)
            else -> ""
        }
    val creds =
        ComfyCredentials(
            baseUrl = normalized.base,
            username = if (server.authMode == ComfyAuthMode.BASIC) server.username else null,
            password = if (server.authMode == ComfyAuthMode.BASIC) password.ifEmpty { null } else null,
            extraHeaders =
                if (server.authMode == ComfyAuthMode.CUSTOM_HEADER) {
                    server.extraHeaders
                        .filter { it.key.isNotBlank() }
                        .associate { it.key.trim() to it.value }
                } else {
                    emptyMap()
                },
        )
    return ComfyClient.create(creds, SyncHttp.client(server.trustSelfSigned))
}

/** 一个会话一个 clientId; 提交与 WebSocket 必须一致 */
private fun PaintViewModel.ensureAiClientId(ui: AiUiState): String =
    ui.clientId ?: UUID.randomUUID().toString().also { ui.clientId = it }

/** 懒建 WebSocket, 已存在则复用 (多任务共享一条连接, 按 prompt_id 分流) */
private fun PaintViewModel.ensureAiSocket(
    ui: AiUiState,
    client: ComfyClient,
) {
    if (ui.socket != null) return
    val socket =
        ComfySocket(
            client = client,
            clientId = ensureAiClientId(ui),
            onEvent = { handleAiSocketEvent(it) },
            onFailure = { error, detail -> handleAiSocketFailure(error, detail) },
            onClosed = { handleAiSocketFailure(ComfyConnectionError.Unreachable, null) },
        )
    ui.socket = socket
    socket.connect()
}

/** 本地失败: 把文案 res 记进状态, 返回 false 便于 `return failLocal(...)` */
private fun PaintViewModel.failLocal(
    ui: AiUiState,
    res: Int,
): Boolean {
    ui.errorRes = res
    ui.errorRaw = null
    return false
}

/**
 * 网络类失败: 服务端错误带原文就显示原文 (如节点校验的 node_errors), 否则按分类给文案。
 * 这样 ComfyUI 的原始报错永远有机会露出来, 排查不至于被我们的翻译挡住。
 */
private fun PaintViewModel.failComfy(
    ui: AiUiState,
    fail: ComfyCall.Fail,
    fallbackRes: Int,
) {
    when (val error = fail.error) {
        is ComfyConnectionError.ServerError ->
            if (!fail.detail.isNullOrBlank()) {
                ui.errorRaw = fail.detail
                ui.errorRes = null
            } else {
                ui.errorRes = fallbackRes
                ui.errorRaw = null
            }
        else -> {
            ui.errorRes = errorResOf(error)
            ui.errorRaw = null
        }
    }
}

/** 把已分类的网络错误映射成面板文案 (复用服务器页已有的措辞, 不另造一套) */
private fun errorResOf(error: ComfyConnectionError): Int =
    when (error) {
        ComfyConnectionError.AuthFailed -> R.string.comfy_status_auth_failed
        ComfyConnectionError.AuthNotConfigured -> R.string.comfy_status_auth_not_configured
        ComfyConnectionError.Unreachable -> R.string.comfy_status_unreachable
        is ComfyConnectionError.ServerError -> R.string.ai_error_server
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