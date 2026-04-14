/*
 * WebIDE - A powerful IDE for Android web development.
 * Copyright (C) 2025  如日中天  <3382198490@qq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package com.web.webide.ui.editor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.web.webide.files.FileTreeConfig
import com.web.webide.files.SortBy
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.web.webide.R
import com.web.webide.build.ApkInstaller
import com.web.webide.core.utils.LogCatcher
import com.web.webide.core.utils.WorkspaceManager
import com.web.webide.files.FileTree
import com.web.webide.safeNavigate
import com.web.webide.ui.components.ColorPickerDialog
import com.web.webide.ui.components.colorToHex
import com.web.webide.ui.editor.aicoding.AICodingPanel
import com.web.webide.ui.editor.components.EditorPanelLayout
import com.web.webide.ui.editor.components.EditorToolbar
import com.web.webide.ui.editor.components.JumpLinePanel
import com.web.webide.ui.editor.components.SearchPanel
import com.web.webide.ui.editor.git.GitPanel
import com.web.webide.ui.editor.git.GitViewModel
import com.web.webide.ui.editor.git.SidebarTab.*
import com.web.webide.ui.editor.viewmodel.EditorViewModel
import com.web.webide.ui.terminal.AlpineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.core.content.edit

// Build结果状态
sealed class BuildResultState {
    data class Finished(val message: String, val apkPath: String? = null) : BuildResultState()
}

@SuppressLint("ConfigurationScreenWidthHeight", "UseKtx")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditScreen(folderName: String, navController: NavController, viewModel: EditorViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isMoreMenuExpanded by remember { mutableStateOf(false) }
    val workspacePath = WorkspaceManager.getWorkspacePath(context)
    var projectPath by remember { mutableStateOf(File(workspacePath, folderName).absolutePath) }
    val currentFolderName = remember(projectPath) { File(projectPath).name }
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val gitViewModel: GitViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    // 初始化 Git (加载配置等)
    LaunchedEffect(projectPath) {
        gitViewModel.initialize(projectPath)
    }

    // 1. 定义状态来持有自动Save的时间间隔
    var autoSaveInterval by remember { mutableLongStateOf(0L) }
    // 🔥 优化：在初始化时直接读取 SharedPreferences，避免闪烁
    var isAiEnabled by remember {
        val editorPrefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
        mutableStateOf(editorPrefs.getBoolean("editor_ai_enabled", true))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    // 2. 监听生命周期：当从Settings页返回时，重新读取 SharedPreferences
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val prefs = context.getSharedPreferences("WebIDE_Settings", Context.MODE_PRIVATE)
                autoSaveInterval = prefs.getLong("auto_save_interval", 0L)

                val editorPrefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
                isAiEnabled = editorPrefs.getBoolean("editor_ai_enabled", true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(projectPath) {
        viewModel.loadInitialFile(projectPath)
        // 这样即使之后跳转Terminal不传参，AlpineManager 也能知道当前Yes在哪个项目
        AlpineManager.currentProject = projectPath
    }
    LaunchedEffect(autoSaveInterval) {
        if (autoSaveInterval > 0) {
            while (isActive) {
                delay(autoSaveInterval)
                // 执行自动Save
                viewModel.autoSaveProject(context, projectPath)

                // ➡️ 顺便Refresh Git 状态！(实现实时更新的Off键)
                gitViewModel.refreshAll()
            }
        }
    }
    val focusManager = LocalFocusManager.current
    // 🔥 进阶：自动检测该项目下YesNo存在已Build的 APK
    LaunchedEffect(projectPath) {
        // 如果 ViewModel 里还没记录（比如刚打OnAPP），尝试去硬盘找最新的 release 包
        if (viewModel.lastBuiltApk == null) {
            val buildDir = File(projectPath, "build")
            if (buildDir.exists() && buildDir.isDirectory) {
                // 找 build 目录下以 _release.apk 结尾且最新的File
                val lastApk = buildDir.listFiles()
                    ?.filter { it.name.endsWith("_release.apk") }
                    ?.maxByOrNull { it.lastModified() }

                if (lastApk != null) {
                    viewModel.updateLastBuild(lastApk.absolutePath)
                }
            }
        }
    }


    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }
    // 加载配置
    LaunchedEffect(Unit) {
        viewModel.reloadEditorConfig(context)
    }
    val editorConfig = viewModel.editorConfig

    // 初始加载进度条状态
    var showInitialLoader by remember { mutableStateOf(!viewModel.hasShownInitialLoader) }
    // Build过程中的进度条状态
    var isBuilding by remember { mutableStateOf(false) }
    val hasOpenFiles = viewModel.openFiles.isNotEmpty()
    // Build结果状态
    var buildResult by remember { mutableStateOf<BuildResultState?>(null) }

    // 检测 webapp.json 和 src/main/assets/ YesNo同时存在
    val hasWebAppConfig = remember(projectPath) {
        val configFile = File(projectPath, "webapp.json")
        val assetsDir = File(projectPath, "src/main/assets")
        configFile.exists() && assetsDir.exists() && assetsDir.isDirectory
    }

    // FileTree Config (Hoisted to persist across drawer toggles)
    val prefs = remember { context.getSharedPreferences("FileTreeSettings", Context.MODE_PRIVATE) }
    var fileTreeConfig by remember {
        mutableStateOf(
            FileTreeConfig(
                sortBy = try {
                    SortBy.valueOf(prefs.getString("sortBy", SortBy.NAME.name) ?: SortBy.NAME.name)
                } catch (_: Exception) {
                    SortBy.NAME
                },
                foldersAlwaysOnTop = prefs.getBoolean("foldersAlwaysOnTop", true),
                showDetails = prefs.getBoolean("showDetails", false), // User requested default: false
                compactMiddlePackages = prefs.getBoolean("compactMiddlePackages", false),
                compactMiddlePackageCount = prefs.getInt("compactMiddlePackageCount", 3),
                alwaysSelectOpenedFile = prefs.getBoolean("alwaysSelectOpenedFile", false), // User requested default: false
                showIndentGuides = prefs.getBoolean("showIndentGuides", false) // User requested default: false
            )
        )
    }

    var isOpenSearch by remember { mutableStateOf(false) }
    var currentSearchText by remember { mutableStateOf("") }
    LaunchedEffect(viewModel.activeFileIndex) {
        if (isOpenSearch && currentSearchText.isNotEmpty()) {
            delay(200)
            viewModel.searchText(currentSearchText)
        }
    }
    var ignoreCaseState by remember { mutableStateOf(true) }
    LaunchedEffect(viewModel.activeFileIndex) {
        if (isOpenSearch && currentSearchText.isNotEmpty()) {
            viewModel.searchText(currentSearchText, ignoreCaseState)
        }
    }

    var isOpenJump by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    LaunchedEffect(currentFolderName) {
        if (viewModel.openFiles.isNotEmpty()) {
            val firstFile = viewModel.openFiles.first().file
            if (!firstFile.absolutePath.startsWith(projectPath)) {
                viewModel.closeAllFiles()
            }
        }
    }

    LaunchedEffect(projectPath) {
        viewModel.loadInitialFile(projectPath)
    }

    LaunchedEffect(Unit) {
        if (showInitialLoader) {
            delay(500L)
            showInitialLoader = false
            viewModel.onInitialLoaderShown()
        }
    }
    var selectedTab by remember { mutableStateOf(FILES) }
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    // 2. 计算动态宽度
    // 逻辑：如果Yes平板(宽>600dp)，侧边栏给 400dp；如果Yes手机，给屏幕宽度的 85%
    val drawerWidth = if (screenWidth > 600.dp) 400.dp else (screenWidth * 0.85f)

    DismissibleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            // --- 修改：增加宽度以容纳 NavigationRail ---
            ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // --- 左侧：NavigationRail ---
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface, // 与Drawer背景融合
                        modifier = Modifier.width(80.dp) // 固定Rail宽度
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        // 1. File Tree按钮
                        NavigationRailItem(
                            selected = selectedTab == FILES,
                            onClick = { selectedTab = FILES },
                            icon = { Icon(Icons.Default.Folder, contentDescription = "File") },
                            label = { Text("File Tree") }
                        )
                        // 2. Git 按钮
                        NavigationRailItem(
                            selected = selectedTab == GIT,
                            onClick = {
                                selectedTab = GIT
                                gitViewModel.refreshAll()
                                      },
                            icon = {
                                val changeCount = gitViewModel.changedFiles.size
                                BadgedBox(
                                    badge = {
                                        if (changeCount > 0) {
                                            Badge {
                                                // 如果数字太大，显示 99+，No则显示具体数字
                                                Text(if(changeCount > 99) "99+" else changeCount.toString())
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        // 🔥 这里修改：使用 painterResource 加载 xml 资源
                                        painter = painterResource(id = R.drawable.ic_git),
                                        contentDescription = "Git",
                                        modifier = Modifier.size(24.dp) // 建议加上尺寸限制，防止图标过大
                                    )
                                }
                            },
                            label = { Text("Git") }
                        )
                    }

                    // --- 中间分割线 (可选) ---
                    VerticalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // --- 右侧：内容区域 ---
                    Box(modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()) {
                        when (selectedTab) {
                            FILES -> {
                                // 原有的File管理器
                                FileManagerDrawer(
                                    projectPath = projectPath,
                                    activeFile = viewModel.openFiles.getOrNull(viewModel.activeFileIndex)?.file,
                                    fileTreeConfig = fileTreeConfig,
                                    onConfigChange = { newConfig ->
                                        fileTreeConfig = newConfig
                                        prefs.edit {
                                            putString("sortBy", newConfig.sortBy.name)
                                                .putBoolean(
                                                    "foldersAlwaysOnTop",
                                                    newConfig.foldersAlwaysOnTop
                                                )
                                                .putBoolean("showDetails", newConfig.showDetails)
                                                .putBoolean(
                                                    "compactMiddlePackages",
                                                    newConfig.compactMiddlePackages
                                                )
                                                .putInt(
                                                    "compactMiddlePackageCount",
                                                    newConfig.compactMiddlePackageCount
                                                )
                                                .putBoolean(
                                                    "alwaysSelectOpenedFile",
                                                    newConfig.alwaysSelectOpenedFile
                                                )
                                                .putBoolean(
                                                    "showIndentGuides",
                                                    newConfig.showIndentGuides
                                                )
                                        }
                                    },
                                    onFileClick = { file ->
                                        viewModel.openFile(file)
                                        scope.launch { drawerState.close() }
                                    },
                                    onFileRenamed = { oldFile, newFile ->
                                        viewModel.updateRenamedFile(oldFile, newFile)
                                        gitViewModel.refreshAll()
                                    }
                                )
                            }
                            GIT -> {
                                // Added的空 Git 面板
                                GitPanel(
                                    projectPath = projectPath,
                                    viewModel = gitViewModel,
                                    editorViewModel = viewModel // 🔥 加上这一行
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.statusBars,
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("WebIDE", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = currentFolderName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            navigationIcon = {
                                AnimatedDrawerToggle(
                                    isOpen = drawerState.targetValue == DrawerValue.Open,
                                    onClick = {
                                        scope.launch {
                                            if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                        }
                                    }
                                )
                            },
                            actions = {
                                IconButton(onClick = { viewModel.undo() }) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                                }
                                IconButton(onClick = { viewModel.redo() }) {
                                    Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                                }
                                IconButton(onClick = {
                                    // 🔥 修改：使用一个协程顺序执行
                                    scope.launch {
                                        // 1. 先Save所有File (这Yes一个 suspend 函数，会等待 IO Done)
                                        val success = viewModel.saveAllModifiedFiles(snackbarHostState)

                                        // 2. SaveDone后，再跳转 (Undo 栈不会丢失，因为 Editor 实例没变)
                                        if (success) {
                                            navController.safeNavigate("preview/$folderName")
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.PlayArrow, "运行")
                                }
                                Box {
                                    IconButton(onClick = { isMoreMenuExpanded = true }) {
                                        Icon(Icons.Filled.MoreVert, "More选项")
                                    }
                                    DropdownMenu(
                                        expanded = isMoreMenuExpanded,
                                        onDismissRequest = { isMoreMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("全部Save") },
                                            onClick = {
                                                scope.launch {
                                                    viewModel.saveAllModifiedFiles(snackbarHostState)
                                                    gitViewModel.refreshAll() // <--- 加这一行
                                                }
                                                isMoreMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (isOpenSearch) "CloseSearch" else "Search") },
                                            onClick = {
                                                if (isOpenSearch) viewModel.stopSearch()
                                                isOpenSearch = !isOpenSearch
                                                isMoreMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Terminal") },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                // 🔥 跳转到独立的Terminal页面
                                                navController.navigate("terminal")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("JS 接口文档") },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                navController.safeNavigate("js_interface_doc")
                                            }
                                        )

                                        // 只有File存在且Path属于当前项目(可选逻辑)时才显示
                                        if (viewModel.lastBuiltApk != null && viewModel.lastBuiltApk!!.exists()) {
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text("Install上次Build的 APK")
                                                        Text(
                                                            text = "Version: ${viewModel.lastBuiltApk!!.name}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.secondary
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    isMoreMenuExpanded = false
                                                    // 调用Install器
                                                    ApkInstaller.install(context, viewModel.lastBuiltApk!!)
                                                }
                                            )
                                            HorizontalDivider() // 加个分割线好看点
                                        }

                                        if (hasWebAppConfig) {
                                            DropdownMenuItem(
                                                text = { Text("Build APK") },
                                                enabled = !isBuilding,
                                                onClick = {
                                                    isMoreMenuExpanded = false
                                                    scope.launch {
                                                        isBuilding = true
                                                        performBuild(
                                                            context = context,
                                                            projectPath = projectPath,
                                                            folderName = folderName,
                                                            viewModel = viewModel,
                                                            snackbarHostState = snackbarHostState,
                                                            onResult = { resultState ->
                                                                buildResult = resultState
                                                                isBuilding = false
                                                                // 🔥 Added：如果Build successful，SavePath到 ViewModel
                                                                if (resultState is BuildResultState.Finished && resultState.apkPath != null) {
                                                                    viewModel.updateLastBuild(resultState.apkPath)
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        // 工具栏 (根据配置显示)
                        if (editorConfig.showToolbar) {
                            EditorToolbar(
                                onSave = {
                                    scope.launch {
                                        viewModel.saveAllModifiedFiles(
                                            snackbarHostState
                                        )
                                        gitViewModel.refreshAll() // <--- 加这一行
                                    }
                                },
                                onSearch = {
                                    isOpenSearch = !isOpenSearch
                                    isOpenJump = false
                                },
                                onJump = {
                                    isOpenJump = !isOpenJump
                                    isOpenSearch = false
                                },
                                onFormat = { viewModel.formatCode() },
                                onCreate = { showCreateDialog = true },
                                onPalette = { showColorPicker = true },
                                isBuilding = isBuilding,
                                hasWebAppConfig = hasWebAppConfig,
                                onBuild = {
                                    scope.launch {
                                        isBuilding = true
                                        performBuild(
                                            context = context,
                                            projectPath = projectPath,
                                            folderName = folderName,
                                            viewModel = viewModel,
                                            snackbarHostState = snackbarHostState,
                                            onResult = { resultState ->
                                                buildResult = resultState
                                                isBuilding = false
                                            }
                                        )
                                    }
                                },
                            )
                        }

                        // Search面板
                        AnimatedVisibility(visible = isOpenSearch) {
                            Column {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                SearchPanel(
                                    viewModel = viewModel,
                                    searchText = currentSearchText,
                                    onSearchTextChange = { currentSearchText = it },
                                    onClose = {
                                        viewModel.stopSearch()
                                        isOpenSearch = false
                                    }
                                )
                            }
                        }
                        AnimatedVisibility(visible = isOpenJump) {
                            Column {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                JumpLinePanel(
                                    onJump = { line -> viewModel.jumpToLine(line) },
                                    onClose = {
                                        isOpenJump = false
                                        viewModel.getActiveEditor()?.requestFocus()
                                    }
                                )
                            }
                        }
                    }
                },

                content = { innerPadding ->
                    BoxWithConstraints(modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .imePadding()) {
                        val availableEditorHeight = maxHeight // 这就Yes EditorPanelLayout 可以用的全部高度
                        
                        // 🔥 修改：在代码编辑器和Diff模式下显示符号栏，仅在媒体查看器或NoneFile时隐藏
                        val activeTab = viewModel.openFiles.getOrNull(viewModel.activeFileIndex)
                        val shouldShowSymbols = activeTab is com.web.webide.ui.editor.viewmodel.CodeEditorState || 
                                              activeTab is com.web.webide.ui.editor.viewmodel.DiffEditorState
                        val currentSymbols = if (hasOpenFiles && shouldShowSymbols) editorConfig.getSymbolList() else emptyList()

                        EditorPanelLayout(
                            viewModel = viewModel,
                            symbols = currentSymbols,
                            modifier = Modifier.height(availableEditorHeight) // 将精确高度传递给 EditorPanelLayout
                            // 将精确高度传递给 EditorPanelLayout
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                AnimatedVisibility(visible = showInitialLoader || isBuilding) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        strokeCap = StrokeCap.Butt
                                    )
                                }
                                // The content here is just a placeholder because EditorPanelLayout handles the content structure.
                                // But wait, EditorPanelLayout expects `content` to be the main editor area.
                                // However, we have a HorizontalPager below that switches tabs.
                                // The structure seems to be: 
                                // CodeEditScreen -> Scaffold -> EditorPanelLayout -> Content (Main Area)
                                
                                // If we are here, it means we are in the single-pane mode (maybe?) or this is the common area.
                                // Actually, `CodeEditScreen` uses `EditCode` which contains the `HorizontalPager`.
                                // Let's look at `EditCode`.
                                
                                EditCode(
                                    modifier = Modifier.fillMaxSize(),
                                    viewModel = viewModel,
                                    onShowSearch = { isOpenSearch = true },
                                    snackbarHostState = snackbarHostState,
                                    navController = navController,
                                    folderName = folderName,
                                    onNavigateToTerminal = { navController.safeNavigate("terminal") },
                                    onShowJumpLine = { isOpenJump = true },
                                    onShowCreate = { showCreateDialog = true },
                                    onShowColorPicker = { showColorPicker = true }
                                )
                            }
                        }
                        
                        // 配置File可视化入口
                        // Removed duplicate button from here

                        
                        if (isAiEnabled) {
                            AICodingPanel()
                        }
                    }
                }
            )
        }
        if (drawerState.isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // 点击时：Close侧滑栏
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
            )
        }
    }
        // 1. 新建对话框
        if (showCreateDialog) {
            var nameInput by remember { mutableStateOf("") }
            var isFileType by remember { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("新建内容") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = isFileType, onClick = { isFileType = true })
                            Text("File")
                            Spacer(Modifier.width(16.dp))
                            RadioButton(selected = !isFileType, onClick = { isFileType = false })
                            Text("File夹")
                        }
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("例test.txt") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (nameInput.isNotBlank()) {
                            viewModel.createNewItem(projectPath, nameInput, isFileType) { newItem ->
                                if (isFileType) viewModel.openFile(newItem)
                                gitViewModel.refreshAll()
                            }
                        }
                        showCreateDialog = false
                    }) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
                }
            )
        }

        // 2. 调色板
        if (showColorPicker) {
            ColorPickerDialog(
                initialColor = MaterialTheme.colorScheme.primary,
                onDismiss = { showColorPicker = false },
                onColorSelected = { color ->
                    val hex = colorToHex(color, color.alpha < 1f)
                    viewModel.insertText(hex)
                    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData.newPlainText("Hex Color", hex)
                    clipboardManager.setPrimaryClip(clipData)
                    showColorPicker = false
                }
            )
        }

        // 3. Build结果弹窗
        buildResult?.let { result ->
            if (result is BuildResultState.Finished) {
                val isSuccess = result.apkPath != null
                AlertDialog(
                    onDismissRequest = { buildResult = null },
                    title = { Text(if (isSuccess) "Build successful" else "Build failed") },
                    text = {
                        Column {
                            if (isSuccess) {
                                Text("APK 已生成，YesNo立即Install？")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("输出Path:", style = MaterialTheme.typography.titleSmall)
                                Text(result.apkPath, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("ErrorInfo：", color = MaterialTheme.colorScheme.error)
                                Text(result.message)
                            }
                        }
                    },
                    confirmButton = {
                        if (isSuccess) {
                            TextButton(onClick = {
                                val apkFile = File(result.apkPath)
                                ApkInstaller.install(context, apkFile)
                                buildResult = null
                            }) { Text("Install") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { buildResult = null }) { Text("Close") }
                    }
                )
            }
        }
    }


// ---------------- 辅助函数 ----------------



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCode(
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel,
    onShowSearch: () -> Unit,
    snackbarHostState: SnackbarHostState,
    navController: NavController,
    folderName: String,
    onNavigateToTerminal: () -> Unit,
    onShowJumpLine: () -> Unit,
    onShowCreate: () -> Unit,
    onShowColorPicker: () -> Unit
) {
    val openFiles = viewModel.openFiles
    val activeFileIndex = viewModel.activeFileIndex
    val scope = rememberCoroutineScope()
    var expandedTabIndex by remember { mutableStateOf<Int?>(null) }
    val currentFiles by rememberUpdatedState(openFiles)
    val currentIndex by rememberUpdatedState(activeFileIndex)

    val pagerState = rememberPagerState(
        initialPage = activeFileIndex.coerceIn(0, maxOf(0, openFiles.size - 1)),
        pageCount = { currentFiles.size }
    )

    LaunchedEffect(currentIndex, currentFiles.size) {
        if (currentFiles.isNotEmpty() && currentIndex >= 0 && currentIndex < currentFiles.size && pagerState.currentPage != currentIndex) {
            pagerState.scrollToPage(currentIndex)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (currentFiles.isNotEmpty() && page in currentFiles.indices && page != currentIndex) {
                viewModel.changeActiveFileIndex(page)
            }
        }
    }

    Column(modifier = modifier) {
        if (openFiles.isEmpty()) {
            // History Menu for Empty State (Attached to the button)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("未打On任何File")
                    if (viewModel.editorConfig.showHistory && viewModel.closedFilesHistory.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Box {
                            TextButton(onClick = { expandedTabIndex = -2 }) {
                                Icon(Icons.Default.History, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Restore最近Close")
                            }
                            
                            DropdownMenu(
                                expanded = expandedTabIndex == -2,
                                onDismissRequest = { expandedTabIndex = null }
                            ) {
                                if (viewModel.closedFilesHistory.isEmpty()) {
                                     DropdownMenuItem(
                                        text = { Text("None最近Close记录", color = MaterialTheme.colorScheme.secondary) },
                                        onClick = { expandedTabIndex = null }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("清空历史", color = MaterialTheme.colorScheme.error) },
                                        onClick = { expandedTabIndex = null; viewModel.clearClosedHistory() }
                                    )
                                    HorizontalDivider()
                                    viewModel.closedFilesHistory.forEach { tab ->
                                        DropdownMenuItem(
                                            text = { 
                                                Column {
                                                    Text(tab.title)
                                                    Text(tab.file.parentFile?.name ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                                }
                                            },
                                            onClick = { expandedTabIndex = null; viewModel.restoreClosedFile(tab) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                SecondaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceIn(0, openFiles.size - 1),
                    edgePadding = 0.dp,
                    modifier = Modifier.weight(1f),
                    divider = { },
                    indicator = {
                        Box(
                            modifier = Modifier
                                .tabIndicatorOffset(pagerState.currentPage.coerceIn(0, openFiles.size - 1))
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(percent = 50))
                        )
                    }
                ) {
                    openFiles.forEachIndexed { index, tab ->
                        Box {
                            // 1. 获取标题
                            val displayName = tab.title

                            // 2. 区分颜色 (Diff 模式显示不同颜色)
                            val isDiff = tab is com.web.webide.ui.editor.viewmodel.DiffEditorState
                            val tabColor = if (isDiff) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface

                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    if (pagerState.currentPage == index) expandedTabIndex = index
                                    else scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                text = {
                                    Text(
                                        text = displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else tabColor
                                    )
                                }
                            )

                            DropdownMenu(
                                expanded = expandedTabIndex == index,
                                onDismissRequest = { expandedTabIndex = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Close") },
                                    onClick = { expandedTabIndex = null; viewModel.closeFile(index) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Close其他") },
                                    onClick = { expandedTabIndex = null; viewModel.closeOtherFiles(index) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Close全部") },
                                    onClick = { expandedTabIndex = null; viewModel.closeAllFiles() }
                                )
                            }
                        }
                    }
                }
                
                // History Button
                if (viewModel.editorConfig.showHistory) {
                    Box {
                        IconButton(onClick = { expandedTabIndex = -1 }) {
                            Icon(Icons.Default.History, "历史记录", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        DropdownMenu(
                            expanded = expandedTabIndex == -1,
                            onDismissRequest = { expandedTabIndex = null }
                        ) {
                             if (viewModel.closedFilesHistory.isEmpty()) {
                                 DropdownMenuItem(
                                    text = { Text("None最近Close记录", color = MaterialTheme.colorScheme.secondary) },
                                    onClick = { expandedTabIndex = null }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("清空历史", color = MaterialTheme.colorScheme.error) },
                                    onClick = { expandedTabIndex = null; viewModel.clearClosedHistory() }
                                )
                                HorizontalDivider()
                                viewModel.closedFilesHistory.forEach { tab ->
                                    DropdownMenuItem(
                                        text = { 
                                            Column {
                                                Text(tab.title)
                                                Text(tab.file.parentFile?.name ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                            }
                                        },
                                        onClick = { expandedTabIndex = null; viewModel.restoreClosedFile(tab) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false,
                    key = { index -> if (index < openFiles.size) openFiles[index].uniqueId else "empty_$index" }
                ) { page ->
                    if (page in openFiles.indices) {
                        val tab = openFiles[page]

                        // 🔥🔥 根据Type渲染组件 🔥🔥
                        when (tab) {
                            is com.web.webide.ui.editor.viewmodel.MediaEditorState -> {
                                com.web.webide.ui.editor.components.MediaViewer(
                                    file = tab.file,
                                    type = tab.mediaType
                                )
                            }
                            is com.web.webide.ui.editor.viewmodel.CodeEditorState -> {
                                com.web.webide.ui.editor.components.CodeEditorView(
                                    modifier = Modifier.fillMaxSize(),
                                    state = tab,
                                    viewModel = viewModel,
                                    onShowSearch = onShowSearch,
                                    onRun = {
                                        scope.launch {
                                            viewModel.saveAllModifiedFiles(snackbarHostState)
                                            navController.safeNavigate("preview/$folderName")
                                        }
                                    },
                                    onNavigateToTerminal = onNavigateToTerminal,
                                    onShowJumpLine = onShowJumpLine,
                                    onShowCreate = onShowCreate,
                                    onShowColorPicker = onShowColorPicker
                                )
                            }
                            is com.web.webide.ui.editor.viewmodel.DiffEditorState -> {
                                com.web.webide.ui.editor.components.DiffViewer(
                                    viewModel = viewModel,
                                    state = tab,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }

                val activeTab = openFiles.getOrNull(pagerState.currentPage)
                if (activeTab?.file?.name == "webapp.json") {
                    FilledIconButton(
                        onClick = {
                            val encodedPath = URLEncoder.encode(activeTab.file.absolutePath, StandardCharsets.UTF_8.toString())
                            navController.navigate("project_config/$encodedPath")
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 16.dp, end = 16.dp)
                            .size(35.dp)
                    ) {
                        Icon(Icons.Outlined.Settings, "可视化配置", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun FileManagerDrawer(
    projectPath: String,
    activeFile: File? = null,
    fileTreeConfig: FileTreeConfig,
    onConfigChange: (FileTreeConfig) -> Unit,
    onFileClick: (File) -> Unit,
    onFileRenamed: (File, File) -> Unit
) {
    var showSettingsMenu by remember { mutableStateOf(false) }
    var locateTrigger by remember { mutableLongStateOf(0L) }
    var collapseTrigger by remember { mutableLongStateOf(0L) }
    var expandTrigger by remember { mutableLongStateOf(0L) }
    var refreshTrigger by remember { mutableLongStateOf(0L) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showCreateMenu) {
        AlertDialog(
            onDismissRequest = { showCreateMenu = false },
            title = { Text("新建") },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showCreateMenu = false
                                showNewFileDialog = true
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text("File")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showCreateMenu = false
                                showNewFolderDialog = true
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text("File夹")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreateMenu = false }) { Text("Cancel") }
            }
        )
    }

    if (showNewFileDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("New File") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Filename") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val parent = if (activeFile != null) activeFile.parentFile else File(projectPath)
                                val target = if (parent != null && parent.exists()) parent else File(projectPath)
                                val newFile = File(target, name)
                                if (!newFile.exists()) {
                                    newFile.createNewFile()
                                    withContext(Dispatchers.Main) {
                                        refreshTrigger = System.currentTimeMillis()
                                        onFileClick(newFile) // Open created file
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        showNewFileDialog = false
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showNewFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File夹名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val parent = if (activeFile != null) activeFile.parentFile else File(projectPath)
                                val target = if (parent != null && parent.exists()) parent else File(projectPath)
                                val newFile = File(target, name)
                                if (!newFile.exists()) {
                                    newFile.mkdirs()
                                    withContext(Dispatchers.Main) {
                                        refreshTrigger = System.currentTimeMillis()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        showNewFolderDialog = false
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSettingsMenu) {
        AlertDialog(
            onDismissRequest = { showSettingsMenu = false },
            title = { Text("File TreeSettings") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("排序方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onConfigChange(fileTreeConfig.copy(sortBy = SortBy.NAME)) }.padding(vertical = 8.dp)) {
                        RadioButton(selected = fileTreeConfig.sortBy == SortBy.NAME, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text("按Name排序")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onConfigChange(fileTreeConfig.copy(sortBy = SortBy.TYPE)) }.padding(vertical = 8.dp)) {
                        RadioButton(selected = fileTreeConfig.sortBy == SortBy.TYPE, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text("按Type排序")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onConfigChange(fileTreeConfig.copy(sortBy = SortBy.DATE_NEWEST)) }.padding(vertical = 8.dp)) {
                        RadioButton(selected = fileTreeConfig.sortBy == SortBy.DATE_NEWEST, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text("按时间排序 (最新)")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("外观", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onConfigChange(fileTreeConfig.copy(foldersAlwaysOnTop = !fileTreeConfig.foldersAlwaysOnTop)) }.padding(vertical = 8.dp)) {
                        Checkbox(checked = fileTreeConfig.foldersAlwaysOnTop, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("File夹置顶")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onConfigChange(fileTreeConfig.copy(showDetails = !fileTreeConfig.showDetails)) }.padding(vertical = 8.dp)) {
                        Checkbox(checked = fileTreeConfig.showDetails, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("显示详细Info")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onConfigChange(fileTreeConfig.copy(compactMiddlePackages = !fileTreeConfig.compactMiddlePackages)) }.padding(vertical = 8.dp)) {
                        Checkbox(checked = fileTreeConfig.compactMiddlePackages, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("压缩空中间包")
                    }
                    
                    if (fileTreeConfig.compactMiddlePackages) {
                        Column(modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)) {
                             Text("最大压缩层级: ${fileTreeConfig.compactMiddlePackageCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                             Slider(
                                 value = fileTreeConfig.compactMiddlePackageCount.toFloat(),
                                 onValueChange = { onConfigChange(fileTreeConfig.copy(compactMiddlePackageCount = it.toInt())) },
                                 valueRange = 1f..10f,
                                 steps = 8
                             )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onConfigChange(fileTreeConfig.copy(showIndentGuides = !fileTreeConfig.showIndentGuides)) }.padding(vertical = 8.dp)) {
                        Checkbox(checked = fileTreeConfig.showIndentGuides, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("显示缩进参考线")
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("行为", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onConfigChange(fileTreeConfig.copy(alwaysSelectOpenedFile = !fileTreeConfig.alwaysSelectOpenedFile)) }.padding(vertical = 8.dp)) {
                        Checkbox(checked = fileTreeConfig.alwaysSelectOpenedFile, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("始终选中打On的File")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsMenu = false }) { Text("Close") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "File Tree",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
            
            Row(
                modifier = Modifier.padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // New File/Folder
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "New",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showCreateMenu = true }
                        .padding(4.dp)
                )

                // Locate File
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = "Locate File",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { locateTrigger = System.currentTimeMillis() }
                        .padding(4.dp)
                )
                
                // Collapse All
                Icon(
                    imageVector = Icons.Filled.UnfoldLess,
                    contentDescription = "Collapse All",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { collapseTrigger = System.currentTimeMillis() }
                        .padding(4.dp)
                )

                // Expand All
                Icon(
                    imageVector = Icons.Filled.UnfoldMore,
                    contentDescription = "Expand All",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expandTrigger = System.currentTimeMillis() }
                        .padding(4.dp)
                )

                // Settings
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Options",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showSettingsMenu = true }
                        .padding(4.dp)
                )
            }
        }
        
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        FileTree(
            rootPath = projectPath,
            activeFile = activeFile,
            config = fileTreeConfig,
            locateTrigger = locateTrigger,
            collapseTrigger = collapseTrigger,
            expandTrigger = expandTrigger,
            refreshTrigger = refreshTrigger,
            modifier = Modifier.fillMaxSize(),
            onFileClick = onFileClick,
            onFileRenamed = onFileRenamed
        )
    }
}





@Composable
fun AnimatedDrawerToggle(
    isOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 0f = 菜单, 1f = 箭头
    val progress by animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        label = "DrawerToggleProgress",
        animationSpec = tween(durationMillis = 300)
    )

    IconButton(onClick = onClick, modifier = modifier) {
        val color = LocalContentColor.current

        Canvas(
            modifier = Modifier.size(24.dp)
        ) {
            val strokeWidth = 2.dp.toPx()
            val cap = StrokeCap.Square // 保持直角

            val w = size.width
            val h = size.height
            val centerX = w / 2
            val centerY = h / 2

            // --- 1. 菜单状态参数 (宽一点) ---
            val menuWidth = 18.dp.toPx() // 菜单总宽 18dp (标准 Material 尺寸)
            val startX = (w - menuWidth) / 2
            val endX = w - startX
            val menuGap = 6.dp.toPx() // 汉堡菜单间距

            // --- 2. 箭头状态参数 (短一点) ---
            // 箭头尖端 X (保持在 endX，即最右侧，作为旋转中心)
            val arrowTipX = endX

            // 缩短箭头的Off键：
            // 让箭头的“尾巴”比菜单的“左边”更靠右
            val shrinkOffset = 3.dp.toPx() // 缩进量
            val arrowShaftStartX = startX + shrinkOffset

            // 箭头翅膀的起始 X (决定翅膀长度)
            // 稍微往右移一点，让翅膀不要太长
            val arrowWingStartX = centerX + 1.dp.toPx()

            // 计算直角(90度)所需的 Y 偏移量
            // 翅膀长度在 X轴的投影 = arrowTipX - arrowWingStartX
            val arrowWingYOffset = (arrowTipX - arrowWingStartX)

            rotate(degrees = progress * 180f, pivot = Offset(centerX, centerY)) {

                // --- 中间线 (轴) ---
                // 菜单: startX -> endX
                // 箭头: arrowShaftStartX -> endX (变短了)
                val midStartX = lerp(startX, arrowShaftStartX, progress)
                drawLine(
                    color = color,
                    strokeWidth = strokeWidth,
                    cap = cap,
                    start = Offset(midStartX, centerY),
                    end = Offset(endX, centerY)
                )

                // --- 上面的线 ---
                // 菜单: 左侧 -> 右侧
                // 箭头: 翅膀末端 -> 尖端
                val topStartX = lerp(startX, arrowWingStartX, progress)
                val topStartY = lerp(centerY - menuGap, centerY - arrowWingYOffset, progress)

                val topEndX = lerp(endX, arrowTipX, progress) // 始终固定在右侧
                val topEndY = lerp(centerY - menuGap, centerY, progress) // 右侧下沉到中心

                drawLine(
                    color = color,
                    strokeWidth = strokeWidth,
                    cap = cap,
                    start = Offset(topStartX, topStartY),
                    end = Offset(topEndX, topEndY)
                )

                // --- 下面的线 ---
                val bottomStartX = lerp(startX, arrowWingStartX, progress)
                val bottomStartY = lerp(centerY + menuGap, centerY + arrowWingYOffset, progress)

                val bottomEndX = lerp(endX, arrowTipX, progress)
                val bottomEndY = lerp(centerY + menuGap, centerY, progress) // 右侧上浮到中心

                drawLine(
                    color = color,
                    strokeWidth = strokeWidth,
                    cap = cap,
                    start = Offset(bottomStartX, bottomStartY),
                    end = Offset(bottomEndX, bottomEndY)
                )
            }
        }
    }
}



private suspend fun performBuild(
    context: Context,
    projectPath: String,
    folderName: String,
    viewModel: EditorViewModel,
    snackbarHostState: SnackbarHostState,
    onResult: (BuildResultState) -> Unit
) {
    if (!viewModel.saveAllModifiedFiles(snackbarHostState)) {
        onResult(BuildResultState.Finished("Save失败，Build已Cancel"))
        return
    }
    val prefs = context.getSharedPreferences("WebIDE_Project_Settings", Context.MODE_PRIVATE)
    val isDebug = prefs.getBoolean("debug_$folderName", false)

    val configFile = File(projectPath, "webapp.json")

    // 默认基础配置
    var pkg = "com.example.webapp"
    var verName = "1.0"
    var verCode = "1"
    var iconPath = ""
    var permissions: Array<String>? = null

    // 🔥 Added：Sign变量初始化
    var customKeyPath: String? = null
    var customStorePass: String? = null
    var customAlias: String? = null
    var customKeyPass: String? = null

    // 加密配置 (默认为 true)
    var enableEncryption = true

    if (configFile.exists()) {
        try {
            var jsonStr = withContext(Dispatchers.IO) {
                configFile.readText()
            }
            // 过滤注释
            jsonStr = jsonStr.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

            val json = org.json.JSONObject(jsonStr)

            // 1. 基础Info解析 (保持原有)
            pkg = json.optString("package", pkg)
            verName = json.optString("versionName", verName)
            verCode = json.optString("versionCode", verCode)
            enableEncryption = json.optBoolean("encryption", true)

            val iconName = json.optString("icon", "")
            if (iconName.isNotEmpty()) {
                val iconFile = File(projectPath, iconName)
                if (iconFile.exists()) iconPath = iconFile.absolutePath
            }

            val jsonPerms = json.optJSONArray("permissions")
            if (jsonPerms != null && jsonPerms.length() > 0) {
                val list = ArrayList<String>()
                for (i in 0 until jsonPerms.length()) {
                    list.add(jsonPerms.getString(i))
                }
                permissions = list.toTypedArray()
            }

            // 🔥 2. 解析Sign配置 (Added逻辑)
            val signingObj = json.optJSONObject("signing")
            if (signingObj != null) {
                val keyFileName = signingObj.optString("keystore")
                if (keyFileName.isNotEmpty()) {
                    // 拼接完整Path：项目目录 + keyFilename
                    val keyFile = File(projectPath, keyFileName)
                    // 只有File存在时才传递Path，No则 ApkBuilder 会回退到默认
                    if (keyFile.exists()) {
                        customKeyPath = keyFile.absolutePath
                        customStorePass = signingObj.optString("storePassword")
                        customAlias = signingObj.optString("alias")
                        // 如果别名Password为空，通常默认与库Password相同，或者尝试读取 keyPassword
                        customKeyPass = signingObj.optString("keyPassword", customStorePass)
                    } else {
                        LogCatcher.w("Build", "webapp.json 中指定了 keystore 但File未找到: $keyFileName")
                    }
                }
            }

        } catch (e: Exception) {
            LogCatcher.e("Build", "JSON Error", e)
            onResult(BuildResultState.Finished("webapp.json 格式Error: ${e.message}"))
            return
        }
    }

    val result = withContext(Dispatchers.IO) {
        com.web.webide.build.ApkBuilder.bin(
            context,
            WorkspaceManager.getWorkspacePath(context),
            projectPath,
            folderName,
            pkg,
            verName,
            verCode,
            iconPath,
            permissions,
            isDebug,
            enableEncryption, // 🔥 传入加密配置
            // 🔥 传入解析出的Sign参数 (如果上面没解析到，这些就Yes null)
            customKeyPath,
            customStorePass,
            customAlias,
            customKeyPass
        )
    }

    if (result.startsWith("error:")) {
        onResult(BuildResultState.Finished(result, null))
    } else {
        onResult(BuildResultState.Finished("Build successful", result))
    }
}