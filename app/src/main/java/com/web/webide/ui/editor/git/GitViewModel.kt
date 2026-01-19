package com.web.webide.ui.editor.git

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.web.webide.core.utils.LogCatcher
import kotlinx.coroutines.launch
import androidx.core.content.edit

// 继承 AndroidViewModel 以获取 Context 进行配置保存
class GitViewModel(application: Application) : AndroidViewModel(application) {

    var isGitProject by mutableStateOf(false)
        private set
    var changedFiles by mutableStateOf<List<GitFileChange>>(emptyList())
        private set
    var currentBranch by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null) // 用于 Snackbar 提示

    private var gitManager: GitManager? = null

    // 配置信息
    var savedAuth by mutableStateOf<GitAuth?>(null)
    var remoteUrl by mutableStateOf("")

    fun initialize(projectPath: String) {
        gitManager = GitManager(projectPath)
        loadConfig() // 初始化时加载配置
        refreshStatus()
    }

    private fun loadConfig() {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("git_config", Context.MODE_PRIVATE)

        remoteUrl = prefs.getString("remote_url", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val token = prefs.getString("token", "") ?: ""

        if (user.isNotEmpty() && token.isNotEmpty()) {
            savedAuth = GitAuth(user, token)
        }
    }

    fun saveConfig(remote: String, username: String, token: String) {
        viewModelScope.launch {
            // 1. 存到 JGit 配置
            gitManager?.addRemote("origin", remote)
            remoteUrl = remote
            savedAuth = GitAuth(username, token)

            // 2. 持久化存到手机
            val context = getApplication<Application>()
            val prefs = context.getSharedPreferences("git_config", Context.MODE_PRIVATE)
            prefs.edit {
                putString("remote_url", remote)
                    .putString("username", username)
                    .putString("token", token)
            }

            statusMessage = "配置已保存"
        }
    }

    fun refreshStatus() {
        val manager = gitManager ?: return
        viewModelScope.launch {
            isLoading = true
            isGitProject = manager.isGitRepo()
            if (isGitProject) {
                try {
                    changedFiles = manager.getStatus()
                    currentBranch = manager.getCurrentBranch()
                } catch (e: Exception) {
                    LogCatcher.e("Git", "Status Error", e)
                }
            }
            isLoading = false
        }
    }

    fun initGitRepo() {
        viewModelScope.launch {
            gitManager?.initRepo()
            refreshStatus()
        }
    }

    fun commit(message: String) {
        viewModelScope.launch {
            isLoading = true
            try {
                // TODO: 可以在设置里开放自定义用户名
                gitManager?.commitAll(message, "AndroidIDE User", "user@android.ide")
                statusMessage = "提交成功 (Local)"
                refreshStatus()
            } catch (e: Exception) {
                statusMessage = "提交失败: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun push() {
        val auth = savedAuth
        if (auth == null) {
            statusMessage = "请先配置远程仓库账号"
            return
        }
        viewModelScope.launch {
            isLoading = true
            try {
                gitManager?.push(auth)
                statusMessage = "推送成功 (Pushed to Remote)"
            } catch (e: Exception) {
                statusMessage = "推送失败: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun pull() {
        val auth = savedAuth
        if (auth == null) {
            statusMessage = "请先配置远程仓库账号"
            return
        }
        viewModelScope.launch {
            isLoading = true
            try {
                gitManager?.pull(auth)
                statusMessage = "拉取成功 (Pulled from Remote)"
                refreshStatus()
            } catch (e: Exception) {
                statusMessage = "拉取失败: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    suspend fun getBranchList(): List<String> {
        return gitManager?.getLocalBranches() ?: emptyList()
    }

    fun checkoutBranch(branchName: String) {
        viewModelScope.launch {
            isLoading = true
            try {
                gitManager?.checkoutBranch(branchName)
                statusMessage = "切换到分支: $branchName"
                refreshStatus()
            } catch (e: Exception) {
                statusMessage = "切换失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun clearMessage() {
        statusMessage = null
    }
}