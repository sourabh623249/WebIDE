package com.web.webide.ui.editor.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.web.webide.ui.editor.viewmodel.EditorViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 定义三个状态
enum class SheetPanelState {
    Collapsed,      // 底部 Peek
    HalfExpanded,   // 半屏
    Expanded        // 全屏
}

// 定义 Tab
enum class PanelPage(val title: String) {
    BUILD_LOG("构建"),
    DIAGNOSTICS("问题"),
}

@SuppressLint("FrequentlyChangingValue")
@Suppress("DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorPanelLayout(
    viewModel: EditorViewModel,
    symbols: List<String>,
    modifier: Modifier = Modifier,
    peekHeight: Dp = 86.dp,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 1. 初始化 AnchoredDraggableState (修复了之前的类型报错)
    val decayAnimationSpec = rememberSplineBasedDecay<Float>()
    val draggableState = remember {
        AnchoredDraggableState(
            initialValue = SheetPanelState.Collapsed,
            positionalThreshold = { distance: Float -> distance * 0.5f },
            velocityThreshold = { with(density) { 100.dp.toPx() } },
            snapAnimationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            decayAnimationSpec = decayAnimationSpec
        )
    }

    // 拦截返回键：如果是展开状态，则收起
    BackHandler(enabled = draggableState.currentValue != SheetPanelState.Collapsed) {
        scope.launch { draggableState.animateTo(SheetPanelState.Collapsed) }
    }

    // 2. 嵌套滚动处理 (允许拖动内容列表来带动 Sheet)
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 向下滑动时，如果不处于底部，且内容未消耗完，可选择在此处拦截
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 内容滑不动了，驱动 Sheet 移动
                return Offset(0f, draggableState.dispatchRawDelta(available.y))
            }
        }
    }

    // 3. 布局计算
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layoutHeight = constraints.maxHeight.toFloat()
        val peekHeightPx = with(density) { peekHeight.toPx() }

        // 更新锚点：全屏、半屏(50%)、底部
        SideEffect {
            val anchors = DraggableAnchors {
                SheetPanelState.Expanded at 0f
                SheetPanelState.HalfExpanded at (layoutHeight * 0.5f)
                SheetPanelState.Collapsed at (layoutHeight - peekHeightPx)
            }
            draggableState.updateAnchors(anchors)
        }

        // --- A. 编辑器主内容 ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = peekHeight) // 给底部留出空间，防止代码被遮挡
        ) {
            content()
        }

        // --- B. 底部抽屉 ---
        // 防止初始 NaN 崩溃
        val currentOffset = if (draggableState.offset.isNaN()) layoutHeight - peekHeightPx else draggableState.offset

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .offset { IntOffset(x = 0, y = currentOffset.roundToInt()) }
                .anchoredDraggable(state = draggableState, orientation = Orientation.Vertical)
                .nestedScroll(nestedScrollConnection),
            shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // --- B1. 顶部栏 (LSP/手柄/光标) ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 16.dp)
                ) {
                    val context = LocalContext.current
                    val prefs = remember { context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE) }
                    val lspEnabled = prefs.getBoolean("editor_lsp_enabled", false)

                    // LSP 状态
                    if (lspEnabled) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val lspConnected = viewModel.openFiles.getOrNull(viewModel.activeFileIndex)?.lspEditor != null
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (lspConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
                            )
                            Text(
                                text = if (lspConnected) "LSP Success" else "LSP Error",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 拖动手柄
                    Box(
                        modifier = Modifier
                            .width(36.dp).height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            .align(Alignment.Center)
                    )

                    // 光标位置
                    var cursorPosition by remember { mutableStateOf(Pair(1, 1)) }
                    LaunchedEffect(viewModel.activeFileIndex) {
                        while (true) {
                            cursorPosition = viewModel.getCursorPosition()
                            kotlinx.coroutines.delay(100)
                        }
                    }
                    Text(
                        text = "Ln ${cursorPosition.first}, Col ${cursorPosition.second}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }

                // --- B2. 符号栏 (仅折叠时显示) ---
                AnimatedVisibility(
                    visible = draggableState.targetValue == SheetPanelState.Collapsed,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SymbolBarRow(symbols) { viewModel.insertSymbol(it) }
                }

                // --- B3. Tabs ---
                var selectedTabIndex by remember { mutableIntStateOf(0) }
                val tabs = PanelPage.entries.toTypedArray()

                SecondaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.height(48.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(selectedTabIndex),
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    divider = { HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant) },
                    tabs = {
                        tabs.forEachIndexed { index, page ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = {
                                    selectedTabIndex = index
                                    // 体验优化：点击 Tab 自动从 Peek 展开到 Half
                                    if (draggableState.currentValue == SheetPanelState.Collapsed) {
                                        scope.launch { draggableState.animateTo(SheetPanelState.HalfExpanded) }
                                    }
                                },
                                text = { Text(page.title) }
                            )
                        }
                    }
                )

                // --- B4. 内容区域 ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (tabs[selectedTabIndex]) {
                        PanelPage.BUILD_LOG -> PlaceholderInfo("构建日志")
                        PanelPage.DIAGNOSTICS -> PlaceholderInfo("诊断信息")
                    }
                }

                // 底部留白
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// --- 辅助组件 (保持原样) ---

@Composable
fun SymbolBarRow(symbols: List<String>, onSymbolClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth().height(48.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(8.dp))
        symbols.forEach { symbol ->
            Box(
                modifier = Modifier
                    .clickable { onSymbolClick(symbol) }
                    .padding(horizontal = 14.dp).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = symbol, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
fun PlaceholderInfo(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}