/*
 * WebIDE - DiffViewer.kt
 *
 * 修改点：
 * 1. 滚动同步逻辑由 scrollTo(x,y) 改为 scrollBy(dx,dy)。
 * 2. 模拟手指/滚动条的“滑动”操作，而非强制坐标对齐。
 * 3. 依然保留 DiffAligner 占位符逻辑，确保行高度一致。
 */
package com.web.webide.ui.editor.components

import android.graphics.Color as AndroidColor
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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

// --- 1. 数据模型 ---
data class AlignedDiffResult(
    val leftContent: CharSequence,
    val rightContent: CharSequence,
    val adds: Int,
    val deletes: Int
)

@Composable
fun DiffViewer(
    viewModel: EditorViewModel,
    state: DiffEditorState,
    modifier: Modifier = Modifier
) {
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
    // 使用 MutableState 持有引用，确保 Compose 重组时引用不丢失
    var leftEditor by remember { mutableStateOf<CodeEditor?>(null) }
    var rightEditor by remember { mutableStateOf<CodeEditor?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2

        Row(modifier = Modifier.fillMaxSize()) {
            // ================= 左侧 (HEAD) =================
            Column(
                modifier = Modifier.width(halfWidth).fillMaxHeight().clipToBounds()
            ) {
                DiffHeader("HEAD", Color(0xFFD32F2F))

                DiffEditorInstance(
                    content = data.leftContent,
                    fileName = state.file.name,
                    viewModel = viewModel,
                    isLeft = true,
                    onEditorCreated = { leftEditor = it },
                    // 🔥 修改点：接收绝对坐标 x, y
                    onScrollChanged = { x, y ->
                        val target = rightEditor ?: return@DiffEditorInstance
                        // 🔥 核心防抖逻辑：只有当目标位置不同时才滚动
                        // 这能完美阻断死循环：A动->B动->B回调发现位置一样->停止
                        if (target.scrollX != x || target.scrollY != y) {
                            target.scrollTo(x, y)
                        }
                    }
                )
            }

            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // ================= 右侧 (Working) =================
            Column(
                modifier = Modifier.width(halfWidth).fillMaxHeight().clipToBounds()
            ) {
                DiffHeader("Working", Color(0xFF388E3C))

                DiffEditorInstance(
                    content = data.rightContent,
                    fileName = state.file.name,
                    viewModel = viewModel,
                    isLeft = false,
                    onEditorCreated = { rightEditor = it },
                    // 🔥 修改点：接收绝对坐标 x, y
                    onScrollChanged = { x, y ->
                        val target = leftEditor ?: return@DiffEditorInstance
                        // 🔥 核心防抖逻辑
                        if (target.scrollX != x || target.scrollY != y) {
                            target.scrollTo(x, y)
                        }
                    }
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
            onEditorCreated = {},
            onScrollChanged  = { _, _ -> }
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
    onEditorCreated: (CodeEditor) -> Unit,
    // 🔥 回调改为绝对坐标
    onScrollChanged: (Int, Int) -> Unit
) {
    val editorConfig = viewModel.editorConfig

    AndroidView(
        factory = { ctx ->
            CodeEditor(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isEditable = false
                isFocusable = true
                isLineNumberEnabled = true
                isWordwrap = false // 必须关闭换行保证对齐

                typefaceText = android.graphics.Typeface.MONOSPACE
                typefaceLineNumber = android.graphics.Typeface.MONOSPACE
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

                // 🔥🔥 核心修改：直接传递当前坐标 🔥🔥
                // Rosemonde Editor 的 setOnScrollChangeListener 回调是 (View, x, y, oldX, oldY)
                setOnScrollChangeListener { _, x, y, _, _ ->
                    onScrollChanged(x, y)
                }

                onEditorCreated(this)
            }
        },
        update = { editor ->
            // 避免重复设置 text 导致重置滚动位置
            if (editor.text.toString() != content.toString()) {
                editor.setText(content)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ==========================================
// Diff 对齐算法 (保持不变)
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