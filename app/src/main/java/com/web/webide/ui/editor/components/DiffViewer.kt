package com.web.webide.ui.editor.components

import android.graphics.Color as AndroidColor
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.web.webide.ui.editor.viewmodel.DiffEditorState
import com.web.webide.ui.editor.viewmodel.DiffViewMode
import com.web.webide.ui.editor.viewmodel.EditorViewModel
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedList
import kotlin.math.max

// ==========================================
// 1. 核心逻辑：Wake + Lock 同步器 (Ultimate Fix)
// ==========================================
// 核心修正版 Synchronizer
// ==========================================
// 1. 核心逻辑：纯净事件镜像 (Butter Smooth Version)
// ==========================================

class ScrollSynchronizer {
    private var leftEditor: CodeEditor? = null
    private var rightEditor: CodeEditor? = null

    // 0 = 无, 1 = 左控右, 2 = 右控左
    private var activeDriver = 0

    fun setEditors(left: CodeEditor?, right: CodeEditor?) {
        if (this.leftEditor === left && this.rightEditor === right) return
        unbind() // 先解绑旧的
        this.leftEditor = left
        this.rightEditor = right
        bindEvents()
    }

    private fun bindEvents() {
        setupMirroring(leftEditor, isLeft = true)
        setupMirroring(rightEditor, isLeft = false)
    }

    private fun unbind() {
        leftEditor?.setOnTouchListener(null)
        rightEditor?.setOnTouchListener(null)
    }

    private fun setupMirroring(editor: CodeEditor?, isLeft: Boolean) {
        if (editor == null) return

        editor.setOnTouchListener { v, event ->
            val selfId = if (isLeft) 1 else 2
            val targetEditor = if (isLeft) rightEditor else leftEditor

            // 如果没有对策编辑器，或者对方正在控制我们，则不处理
            if (targetEditor == null || (activeDriver != 0 && activeDriver != selfId)) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 抢占控制权
                    activeDriver = selfId

                    // 【核心优化 1】立即终止对方的物理惯性
                    // 防止对方还在惯性滚动时，新的触摸事件导致物理引擎冲突
                    if (!targetEditor.scroller.isFinished) {
                        targetEditor.scroller.forceFinished(true)
                    }

                    // 【核心优化 2】强制坐标对齐
                    // 在开始新的手势前，消除之前可能积累的微小像素偏差（drift）
                    val currentY = editor.scroller.currY // 或者 editor.firstVisibleLineY
                    val targetY = targetEditor.scroller.currY
                    if (kotlin.math.abs(currentY - targetY) > 0) {
                        targetEditor.scrollTo(targetEditor.scrollX, editor.scrollY)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 手指离开，延时释放或立即释放
                    // 这里我们保持 activeDriver 直到下一次 DOWN，或者依靠后续逻辑自然归零
                    // 但为了安全，建议在一定时间后重置，或者允许对方随时抢占（上面的 activeDriver check）
                    // 简单的策略：手指抬起，物理惯性开始，此时两边都在独立运行物理引擎
                    activeDriver = 0
                }
            }

            // 【核心优化 3】只镜像滚动相关的事件
            // 过滤掉点击、长按等可能导致光标乱跳的事件，除非你真的想同步光标
            // SoraEditor 内部处理 View 事件，这里直接透传通常是最高效的，
            // 但如果发现光标乱跳，可以只透传 ACTION_MOVE/DOWN/UP
            if (activeDriver == selfId) {
                val eventCopy = MotionEvent.obtain(event)
                // 修正坐标：虽然是全屏覆盖，但如果布局有 padding 差异，最好做 offsetLocation
                // eventCopy.offsetLocation(...)
                try {
                    targetEditor.dispatchTouchEvent(eventCopy)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    eventCopy.recycle()
                }
            }

            // 自己也要消费事件
            false
        }

        // 禁用 ScrollListener 避免循环调用，完全依赖 Touch 驱动
        editor.setOnScrollChangeListener(null)
    }
}

// ==========================================
// 2. 数据结构
// ==========================================
data class AlignedDiffResult(
    val leftContent: CharSequence,
    val rightContent: CharSequence,
    val adds: Int,
    val deletes: Int
)

// ==========================================
// 3. UI 组件
// ==========================================

@Composable
fun DiffViewer(
    viewModel: EditorViewModel,
    state: DiffEditorState,
    modifier: Modifier = Modifier
) {
    // 异步计算差异
    var diffData by remember(state.originalContent, state.currentContent) {
        mutableStateOf<AlignedDiffResult?>(null)
    }

    LaunchedEffect(state.originalContent, state.currentContent) {
        withContext(Dispatchers.Default) {
            diffData = DiffAligner.align(state.originalContent, state.currentContent)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        DiffToolbar(state, diffData)

        if (diffData == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Text("正在计算差异...", modifier = Modifier.padding(top = 48.dp))
            }
        } else {
            val data = diffData!!
            if (state.viewMode == DiffViewMode.SPLIT) {
                SplitDiffView(state, viewModel, data)
            } else {
                UnifiedDiffView(state, viewModel, data)
            }
        }
    }
}

@Composable
fun DiffToolbar(state: DiffEditorState, data: AlignedDiffResult?) {
    val added = data?.adds ?: 0
    val deleted = data?.deletes ?: 0

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(42.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(
                text = state.file.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.width(16.dp))
            if (added > 0) {
                Text("+$added", color = Color(0xFF4CAF50), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
            }
            if (deleted > 0) {
                Text("-$deleted", color = Color(0xFFEF5350), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { state.viewMode = DiffViewMode.SPLIT }) {
                Icon(
                    Icons.Default.ViewColumn, "Split",
                    tint = if (state.viewMode == DiffViewMode.SPLIT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { state.viewMode = DiffViewMode.UNIFIED }) {
                Icon(
                    Icons.AutoMirrored.Filled.ViewList, "Unified",
                    tint = if (state.viewMode == DiffViewMode.UNIFIED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SplitDiffView(
    state: DiffEditorState,
    viewModel: EditorViewModel,
    data: AlignedDiffResult
) {
    // 保持 Synchronizer 实例
    val synchronizer = remember { ScrollSynchronizer() }

    // 使用 ref 引用编辑器，避免 Compose 重组导致对象丢失
    var leftEditorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var rightEditorRef by remember { mutableStateOf<CodeEditor?>(null) }

    // 【重要】当编辑器实例变化时，重新绑定
    DisposableEffect(leftEditorRef, rightEditorRef) {
        if (leftEditorRef != null && rightEditorRef != null) {
            synchronizer.setEditors(leftEditorRef, rightEditorRef)
        }
        onDispose {
            // 组件销毁时可以在这里做清理，或者由 Synchronizer 内部处理
            // synchronizer.unbind()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(halfWidth).fillMaxHeight().clipToBounds()) {
                DiffHeader("HEAD", Color(0xFFD32F2F))
                DiffEditorInstance(
                    content = data.leftContent,
                    fileName = state.file.name,
                    viewModel = viewModel,
                    isLeft = true,
                    onEditorCreated = { leftEditorRef = it }
                )
            }
            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.width(halfWidth).fillMaxHeight().clipToBounds()) {
                DiffHeader("Working", Color(0xFF388E3C))
                DiffEditorInstance(
                    content = data.rightContent,
                    fileName = state.file.name,
                    viewModel = viewModel,
                    isLeft = false,
                    onEditorCreated = { rightEditorRef = it }
                )
            }
        }
    }
}

@Composable
fun UnifiedDiffView(state: DiffEditorState, viewModel: EditorViewModel, data: AlignedDiffResult) {
    Column(modifier = Modifier.fillMaxSize()) {
        DiffEditorInstance(
            content = data.rightContent,
            fileName = state.file.name,
            viewModel = viewModel,
            isLeft = false,
            onEditorCreated = {}
        )
    }
}

@Composable
fun DiffHeader(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(color, MaterialTheme.shapes.small))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DiffEditorInstance(
    content: CharSequence,
    fileName: String,
    viewModel: EditorViewModel,
    isLeft: Boolean,
    onEditorCreated: (CodeEditor) -> Unit
) {
    val editorConfig = viewModel.editorConfig

    AndroidView(
        factory = { ctx ->
            CodeEditor(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // --- 基础 ---
                isEditable = false
                isFocusable = true // 必须开启

                // --- 核心优化 ---
                // 1. 关闭换行：保证每一行的“高度”绝对一致，防止左右行高错位
                isWordwrap = false

                // 2. 移除边缘光晕：防止同步时一边闪蓝光一边不闪，影响视觉流畅度
                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                // 3. 字体强制等宽：这是对齐的基础
                typefaceText = android.graphics.Typeface.MONOSPACE
                typefaceLineNumber = android.graphics.Typeface.MONOSPACE

                // 4. 设置字号和Tab：必须完全一致
                tabWidth = editorConfig.tabWidth

                try {
                    viewModel.applyLanguageToEditor(this, java.io.File(fileName).extension)
                } catch (_: Exception) {}

                val scheme = colorScheme
                if (isLeft) {
                    scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF252526.toInt())
                } else {
                    scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF1E1E1E.toInt())
                }
                colorScheme = scheme

                onEditorCreated(this)
            }
        },
        update = { editor ->
            // 只有内容真变了才 Set，防止重置位置
            if (editor.text.toString() != content.toString()) {
                editor.setText(content)
                // 强制刷新一次
                editor.postInvalidate()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ==========================================
// 4. Diff 算法逻辑 (LCS + Padding)
// ==========================================
object DiffAligner {
    private const val COLOR_DELETE_BG = 0x40B71C1C
    private const val COLOR_ADD_BG = 0x401B5E20
    private const val PHANTOM_TEXT = " \n"

    fun align(oldText: String, newText: String): AlignedDiffResult {
        val oldSafe = oldText.ifEmpty { "" }
        val newSafe = newText.ifEmpty { "" }

        val oldLines = oldSafe.lines()
        val newLines = newSafe.lines()

        val m = oldLines.size
        val n = newLines.size

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = max(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val leftBuilder = SpannableStringBuilder()
        val rightBuilder = SpannableStringBuilder()

        var i = m
        var j = n
        var adds = 0
        var deletes = 0

        val leftStack = LinkedList<CharSequence>()
        val rightStack = LinkedList<CharSequence>()

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                leftStack.push(oldLines[i - 1] + "\n")
                rightStack.push(newLines[j - 1] + "\n")
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                leftStack.push(createPhantomLine())
                rightStack.push(createColoredLine(newLines[j - 1] + "\n", COLOR_ADD_BG))
                adds++
                j--
            } else {
                leftStack.push(createColoredLine(oldLines[i - 1] + "\n", COLOR_DELETE_BG))
                rightStack.push(createPhantomLine())
                deletes++
                i--
            }
        }

        leftStack.forEach { leftBuilder.append(it) }
        rightStack.forEach { rightBuilder.append(it) }

        if (leftBuilder.isNotEmpty() && leftBuilder.last() == '\n') leftBuilder.delete(leftBuilder.length - 1, leftBuilder.length)
        if (rightBuilder.isNotEmpty() && rightBuilder.last() == '\n') rightBuilder.delete(rightBuilder.length - 1, rightBuilder.length)

        return AlignedDiffResult(leftBuilder, rightBuilder, adds, deletes)
    }

    private fun createColoredLine(text: String, bgColor: Int): SpannableString {
        val span = SpannableString(text)
        span.setSpan(
            BackgroundColorSpan(bgColor),
            0,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    private fun createPhantomLine(): SpannableString {
        val span = SpannableString(PHANTOM_TEXT)
        span.setSpan(
            ForegroundColorSpan(AndroidColor.TRANSPARENT),
            0,
            PHANTOM_TEXT.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }
}

class SpannableString(source: CharSequence) : android.text.SpannableString(source)