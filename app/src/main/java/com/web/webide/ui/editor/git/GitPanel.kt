package com.web.webide.ui.editor.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

// 🔥🔥 修复编译错误的关键：定义枚举 🔥🔥
enum class SidebarTab {
    FILES, GIT
}

@Composable
fun GitPanel(
    projectPath: String,
    modifier: Modifier = Modifier,
    // 因为改为 AndroidViewModel，viewModel() 会自动处理 Context
    viewModel: GitViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 弹窗状态
    var showConfigDialog by remember { mutableStateOf(false) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var branchList by remember { mutableStateOf<List<String>>(emptyList()) }

    // 监听消息
    LaunchedEffect(viewModel.statusMessage) {
        viewModel.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 初始化
    LaunchedEffect(projectPath) {
        viewModel.initialize(projectPath)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(8.dp)) {

            // --- 1. 顶部操作栏 ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                // 分支切换按钮
                AssistChip(
                    onClick = {
                        scope.launch {
                            branchList = viewModel.getBranchList()
                            showBranchDialog = true
                        }
                    },
                    label = {
                        Text(
                            text = viewModel.currentBranch.ifEmpty { "Master" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null, Modifier.size(16.dp)) }
                )

                Spacer(Modifier.weight(1f))

                // 操作按钮 (Git 初始化后显示)
                if (viewModel.isGitProject) {
                    IconButton(onClick = { viewModel.push() }) {
                        Icon(Icons.Default.CloudUpload, "推送到远程")
                    }
                    IconButton(onClick = { viewModel.pull() }) {
                        Icon(Icons.Default.CloudDownload, "从远程拉取")
                    }
                }

                IconButton(onClick = { viewModel.refreshStatus() }) {
                    Icon(Icons.Default.Refresh, "刷新")
                }
                IconButton(onClick = { showConfigDialog = true }) {
                    Icon(Icons.Default.Settings, "设置")
                }
            }

            if (viewModel.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // --- 2. 主体区域 ---
            if (!viewModel.isGitProject) {
                // 未初始化状态
                EmptyGitState(onInit = { viewModel.initGitRepo() })
            } else {
                // 已初始化
                // 智能提示：如果未配置远程仓库，显示显眼的卡片
                if (viewModel.remoteUrl.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .clickable { showConfigDialog = true }
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("连接远程仓库", style = MaterialTheme.typography.titleMedium)
                                Text("点击配置 GitHub/GitLab 链接", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // 文件变更列表
                GitChangesList(
                    files = viewModel.changedFiles,
                    onCommit = { msg -> viewModel.commit(msg) }
                )
            }
        }
    }

    // --- 弹窗组件 ---

    // 1. 配置弹窗
    if (showConfigDialog) {
        GitConfigDialog(
            initialRemote = viewModel.remoteUrl,
            initialUser = viewModel.savedAuth?.username ?: "",
            initialToken = viewModel.savedAuth?.token ?: "",
            onDismiss = { showConfigDialog = false },
            onSave = { remote, user, token ->
                viewModel.saveConfig(remote, user, token)
                showConfigDialog = false
            }
        )
    }

    // 2. 分支选择弹窗
    if (showBranchDialog) {
        AlertDialog(
            onDismissRequest = { showBranchDialog = false },
            title = { Text("切换分支") },
            text = {
                LazyColumn {
                    items(branchList) { branch ->
                        ListItem(
                            headlineContent = { Text(branch) },
                            leadingContent = {
                                if (branch == viewModel.currentBranch) Icon(Icons.Default.Check, null)
                            },
                            modifier = Modifier.clickable {
                                viewModel.checkoutBranch(branch)
                                showBranchDialog = false
                            }
                        )
                    }
                    item {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("新建分支 (演示)") },
                            leadingContent = { Icon(Icons.Default.Add, null) },
                            modifier = Modifier.clickable {
                                viewModel.checkoutBranch("dev-${System.currentTimeMillis()%100}")
                                showBranchDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBranchDialog = false }) { Text("关闭") } }
        )
    }
}

// ---------------- 子组件 ----------------

@Composable
fun EmptyGitState(onInit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Source, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("当前文件夹不是 Git 仓库", color = MaterialTheme.colorScheme.outline)
        Button(onClick = onInit, modifier = Modifier.padding(top = 16.dp)) {
            Text("初始化仓库")
        }
    }
}

@Composable
fun GitConfigDialog(
    initialRemote: String,
    initialUser: String,
    initialToken: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var remote by remember { mutableStateOf(initialRemote) }
    var user by remember { mutableStateOf(initialUser) }
    var token by remember { mutableStateOf(initialToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Git 账号配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = remote, onValueChange = { remote = it },
                    label = { Text("Remote URL (https)") },
                    placeholder = { Text("https://github.com/user/repo.git") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Personal Access Token") },
                    placeholder = { Text("ghp_xxxxxxxxxxxx") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("注意：请使用 Token 而非密码进行认证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(remote, user, token) }, enabled = remote.isNotBlank() && token.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun GitChangesList(files: List<GitFileChange>, onCommit: (String) -> Unit) {
    var commitMessage by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = commitMessage,
            onValueChange = { commitMessage = it },
            label = { Text("提交信息 (Message)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
        Button(
            onClick = {
                onCommit(commitMessage)
                commitMessage = ""
            },
            enabled = files.isNotEmpty() && commitMessage.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Commit (Local)")
        }

        HorizontalDivider()
        Text("变更 (${files.size})", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(files) { file ->
                GitFileItem(file)
            }
        }
    }
}

@Composable
fun GitFileItem(file: GitFileChange) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = when (file.status) {
            GitFileStatus.ADDED -> Color(0xFF4CAF50)
            GitFileStatus.MODIFIED -> Color(0xFF2196F3)
            GitFileStatus.UNTRACKED -> Color(0xFF9E9E9E)
            else -> Color(0xFFF44336)
        }
        // 状态指示点
        Box(modifier = Modifier.size(8.dp).background(color, MaterialTheme.shapes.small))
        Spacer(Modifier.width(8.dp))
        Text(
            text = file.filePath,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = file.status.name.take(1),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}