/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.reverie.paint.core.comfy.AiBindingSet
import com.reverie.paint.core.comfy.BindNote
import com.reverie.paint.core.comfy.ComfyAutoBinder
import com.reverie.paint.core.comfy.ComfyNode
import com.reverie.paint.core.comfy.ComfyWorkflowParser
import com.reverie.paint.core.comfy.ComfyWorkflowStore
import com.reverie.paint.core.comfy.ComfyWorkflowType
import com.reverie.paint.core.comfy.EditableBindings
import com.reverie.paint.core.comfy.StoredWorkflow
import com.reverie.paint.core.comfy.WorkflowKind
import com.reverie.paint.core.comfy.toEditable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 已导入工作流的 UI 状态镜像。
 *
 * 与 [ComfyServerState] 分开: 服务器是"连谁", 工作流是"提交什么", 生命周期独立
 * (换服务器不丢已导入的工作流)。挂载点见 `PaintViewModel.comfyWorkflows`。
 */
internal class ComfyWorkflowState {
    val workflows = mutableStateListOf<StoredWorkflow>()

    /** 当前选中的工作流 id; 没有导入过就是 null */
    var currentId by mutableStateOf<String?>(null)

    /** 导入对话框是否打开 */
    var importOpen by mutableStateOf(false)

    /** 待确认删除的工作流 (非 null 时弹确认框) */
    var deleteTarget by mutableStateOf<StoredWorkflow?>(null)

    /** 正在查看/编辑绑定的工作流 (非 null 时弹详情对话框) */
    var detailTarget by mutableStateOf<StoredWorkflow?>(null)

    val current: StoredWorkflow? get() = workflows.firstOrNull { it.id == currentId }
}

/**
 * 解析结果预览。
 *
 * 绑定做成**可点击修改**的四个角色, [bindings] 是当前值 (自动识别后用户可覆盖),
 * [auto] 保留自动识别原值供「恢复自动识别」回退。文案一律由 UI 层 `stringResource` 补,
 * [unresolved] 也只给语义标记 (i18n 归 UI 层)。
 *
 * [bindingSet] 为 null 表示当前绑定还缺正向提示词或输出节点 ([usable] = false),
 * 这种工作流仍可导入 (留给用户在详情里补绑), 但会被明确标注"暂不能生成"。
 */
internal data class ComfyImportPreview(
    val kind: WorkflowKind,
    /** 工作流节点 (节点选择器要用) */
    val nodes: List<ComfyNode>,
    /** 当前绑定 (可被用户手动覆盖) */
    val bindings: EditableBindings,
    /** 自动识别原值, 供「恢复自动识别」 */
    val auto: EditableBindings,
    /** 是否被用户手动改过 */
    val manual: Boolean,
    val unresolved: List<BindNote>,
) {
    val bindingSet: AiBindingSet? get() = bindings.toBindingSet()

    val usable: Boolean get() = bindingSet != null
}

/**
 * 详情编辑器加载出的数据: 节点表 + 自动识别原值 + 当前落盘绑定。
 *
 * @property autoNotes 自动识别时的提示 (导入时算的, 重解析后重算, 与当前绑定无关)
 * @property manual 当前落盘绑定是否手动改过
 */
internal data class ComfyWorkflowEdit(
    val nodes: List<ComfyNode>,
    val auto: EditableBindings,
    val autoNotes: List<BindNote>,
    val current: EditableBindings,
    val manual: Boolean,
)

/** 单份内容的解析结果 */
internal sealed interface ComfyImportParse {
    data class Ok(val preview: ComfyImportPreview) : ComfyImportParse

    data class NotApiFormat(val reason: String) : ComfyImportParse

    data class Invalid(val reason: String) : ComfyImportParse
}

/** 待导入的一条 (名字 + 正文 + 已解析的预览) */
internal data class PendingComfyImport(
    val name: String,
    val json: String,
    val preview: ComfyImportPreview,
)

// ---------------------------------------------------------------------------
// 读取 / 加载
// ---------------------------------------------------------------------------

/**
 * 启动时 (及增删后) 读一次工作流列表。
 *
 * 顺带校正"当前"指针: 指向的条目可能已被删 / 文件缺失, 这时回退到第一条 (或清空)。
 */
internal fun PaintViewModel.loadComfyWorkflows() {
    if (!hasAppContext()) return
    val stored = ComfyWorkflowStore.list(appContext)
    val state = comfyWorkflows
    state.workflows.clear()
    state.workflows.addAll(stored)
    val persisted = ComfyWorkflowStore.currentId(appContext)
    val resolved = persisted?.takeIf { id -> stored.any { it.id == id } } ?: stored.firstOrNull()?.id
    state.currentId = resolved
    if (resolved != persisted) ComfyWorkflowStore.setCurrent(appContext, resolved)
}

/**
 * 从 `content://` 读取工作流文本。
 *
 * 文件可能很大, 一律走 [Dispatchers.IO]; 读失败返回 null (由调用方提示)。
 */
internal suspend fun PaintViewModel.readComfyJsonText(uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

// ---------------------------------------------------------------------------
// 解析 (纯逻辑, 无 IO)
// ---------------------------------------------------------------------------

/**
 * 解析 + 自动绑定, 产出给用户看的预览。
 *
 * 与已提交的 [ComfyWorkflowParser] / [ComfyAutoBinder] 一一对应:
 * 解析失败原样转发原因; 成功后自动绑定并抽出"认出了哪些"的标签。
 * `hasReference` 传 false —— 导入时尚无参考图, 相关提醒留到生成时再提示。
 */
internal fun buildComfyImportPreview(json: String): ComfyImportParse {
    return when (val parsed = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)) {
        is ComfyWorkflowParser.ParseResult.NotApiFormat -> ComfyImportParse.NotApiFormat(parsed.reason)
        is ComfyWorkflowParser.ParseResult.Invalid -> ComfyImportParse.Invalid(parsed.reason)
        is ComfyWorkflowParser.ParseResult.Success -> {
            val result = ComfyAutoBinder.bind(parsed.rawPrompt, parsed.workflow, hasReference = false)
            val auto = result.toEditable()
            ComfyImportParse.Ok(
                ComfyImportPreview(
                    kind = result.workflowKind,
                    nodes = parsed.workflow.nodes,
                    bindings = auto,
                    auto = auto,
                    manual = false,
                    unresolved = result.unresolved,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 导入 / 删除 / 切换
// ---------------------------------------------------------------------------

/**
 * 落盘一批已确认的导入。
 *
 * 正文写文件 + 元数据写 prefs 都在 [Dispatchers.IO]; 全部完成后重新加载列表。
 * 之前没有任何当前项时, 自动把"当前"设为本次导入的第一条。
 */
internal suspend fun PaintViewModel.commitComfyImports(items: List<PendingComfyImport>) {
    if (items.isEmpty() || !hasAppContext()) return
    withContext(Dispatchers.IO) {
        runCatching {
            val hadCurrent = ComfyWorkflowStore.currentId(appContext) != null
            var firstId: String? = null
            for (item in items) {
                val id = UUID.randomUUID().toString()
                ComfyWorkflowStore.save(
                    context = appContext,
                    id = id,
                    name = item.name,
                    json = item.json,
                    kind = item.preview.kind,
                    binding = item.preview.bindingSet,
                    unresolved = item.preview.unresolved,
                    manual = item.preview.manual,
                )
                if (firstId == null) firstId = id
            }
            if (!hadCurrent) firstId?.let { ComfyWorkflowStore.setCurrent(appContext, it) }
        }
    }
    loadComfyWorkflows()
}

/** 删除一份工作流 (正文 + 元数据) */
internal fun PaintViewModel.deleteComfyWorkflow(id: String) {
    if (!hasAppContext()) return
    viewModelScope.launch {
        withContext(Dispatchers.IO) { ComfyWorkflowStore.delete(appContext, id) }
        loadComfyWorkflows()
    }
}

/** 切换当前工作流 */
internal fun PaintViewModel.selectComfyWorkflow(id: String) {
    comfyWorkflows.currentId = id
    if (!hasAppContext()) return
    viewModelScope.launch {
        withContext(Dispatchers.IO) { ComfyWorkflowStore.setCurrent(appContext, id) }
    }
}

// ---- 对话框开关 (状态在 [ComfyWorkflowState], 这里只做转发) ----

internal fun PaintViewModel.openComfyWorkflowImport() {
    comfyWorkflows.importOpen = true
}

internal fun PaintViewModel.closeComfyWorkflowImport() {
    comfyWorkflows.importOpen = false
}

internal fun PaintViewModel.requestDeleteComfyWorkflow(workflow: StoredWorkflow) {
    comfyWorkflows.deleteTarget = workflow
}

internal fun PaintViewModel.cancelDeleteComfyWorkflow() {
    comfyWorkflows.deleteTarget = null
}

internal fun PaintViewModel.confirmDeleteComfyWorkflow() {
    val target = comfyWorkflows.deleteTarget ?: return
    comfyWorkflows.deleteTarget = null
    deleteComfyWorkflow(target.id)
}

// ---------------------------------------------------------------------------
// 节点绑定详情 (手动覆盖自动识别)
// ---------------------------------------------------------------------------

/** 打开某份工作流的绑定详情 (可在里面手动改四个角色) */
internal fun PaintViewModel.openComfyWorkflowDetail(workflow: StoredWorkflow) {
    comfyWorkflows.detailTarget = workflow
}

internal fun PaintViewModel.closeComfyWorkflowDetail() {
    comfyWorkflows.detailTarget = null
}

/**
 * 详情编辑器加载: 重解析原始 JSON 拿到节点表, 并重算一次自动绑定。
 *
 * 走 [Dispatchers.IO] (读文件 + 解析 JSON 都不上主线程)。文件缺失 / 解析失败返回 null,
 * 由 UI 显示可读的空态, 不静默。
 */
internal suspend fun PaintViewModel.loadComfyWorkflowEdit(id: String): ComfyWorkflowEdit? {
    if (!hasAppContext()) return null
    // 在主线程先取状态镜像, 文件读取与 JSON 解析再下 IO 线程
    val stored = comfyWorkflows.workflows.firstOrNull { it.id == id }
    return withContext(Dispatchers.IO) {
        val json = ComfyWorkflowStore.readJson(appContext, id) ?: return@withContext null
        val parsed = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
        if (parsed !is ComfyWorkflowParser.ParseResult.Success) return@withContext null
        val auto = ComfyAutoBinder.bind(parsed.rawPrompt, parsed.workflow, hasReference = false)
        ComfyWorkflowEdit(
            nodes = parsed.workflow.nodes,
            auto = auto.toEditable(),
            autoNotes = auto.unresolved,
            current = stored?.binding?.toEditable() ?: auto.toEditable(),
            manual = stored?.manual ?: false,
        )
    }
}

/**
 * 保存手动绑定 (或恢复自动识别后保存)。
 *
 * 生成链路读的就是这里写下的 [bindings]; [manual] = false 即"当前等于自动识别结果"。
 * 写盘走 IO, 完成后重载列表让"当前工作流"读到新绑定。
 */
internal fun PaintViewModel.saveComfyWorkflowBinding(
    id: String,
    bindings: EditableBindings,
    manual: Boolean,
) {
    if (!hasAppContext()) return
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            ComfyWorkflowStore.updateBinding(appContext, id, bindings.toBindingSet(), manual)
        }
        loadComfyWorkflows()
    }
}
