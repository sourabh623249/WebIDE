package com.web.webide.ui.editor.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

// --- 数据类定义 ---
data class GitFileChange(val filePath: String, val status: GitFileStatus)
data class GitAuth(val username: String, val token: String)

enum class GitFileStatus {
    ADDED, MODIFIED, UNTRACKED, MISSING, REMOVED, CONFLICTING
}

// --- 核心逻辑类 ---
class GitManager(projectPath: String) {

    private val rootDir = File(projectPath)

    fun isGitRepo(): Boolean = File(rootDir, ".git").exists()

    // 1. 初始化
    suspend fun initRepo() = withContext(Dispatchers.IO) {
        Git.init().setDirectory(rootDir).call().close()
    }

    // 2. 获取状态
    suspend fun getStatus(): List<GitFileChange> = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext emptyList()
        val git = Git.open(rootDir)
        val status = git.status().call()
        val changes = mutableListOf<GitFileChange>()

        status.added.forEach { changes.add(GitFileChange(it, GitFileStatus.ADDED)) }
        status.changed.forEach { changes.add(GitFileChange(it, GitFileStatus.MODIFIED)) }
        status.modified.forEach { changes.add(GitFileChange(it, GitFileStatus.MODIFIED)) }
        status.untracked.forEach { changes.add(GitFileChange(it, GitFileStatus.UNTRACKED)) }
        status.missing.forEach { changes.add(GitFileChange(it, GitFileStatus.MISSING)) }
        status.removed.forEach { changes.add(GitFileChange(it, GitFileStatus.REMOVED)) }
        status.conflicting.forEach { changes.add(GitFileChange(it, GitFileStatus.CONFLICTING)) }

        git.close()
        changes.sortedBy { it.filePath }
    }

    // 3. 提交
    suspend fun commitAll(message: String, author: String, email: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        // Add all (untracked + modified)
        git.add().addFilepattern(".").call()

        // Handle deletions explicitly
        val status = git.status().call()
        if (status.missing.isNotEmpty() || status.removed.isNotEmpty()) {
            val rm = git.rm()
            status.missing.forEach { rm.addFilepattern(it) }
            status.removed.forEach { rm.addFilepattern(it) }
            rm.call()
        }

        git.commit()
            .setMessage(message)
            .setAuthor(PersonIdent(author, email))
            .call()
        git.close()
    }

    // 4. 分支管理
    suspend fun getCurrentBranch(): String = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext ""
        val git = Git.open(rootDir)
        val branch = git.repository.branch
        git.close()
        branch ?: "master"
    }

    suspend fun getLocalBranches(): List<String> = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext emptyList()
        val git = Git.open(rootDir)
        val branches = git.branchList().call().map { it.name.substringAfter("refs/heads/") }
        git.close()
        branches
    }

    suspend fun checkoutBranch(branchName: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        // 检查是否存在
        val isExists = git.branchList().call().any { it.name.endsWith(branchName) }

        git.checkout()
            .setName(branchName)
            .setCreateBranch(!isExists)
            .call()
        git.close()
    }

    // 5. 远程操作 (Push/Pull)
    suspend fun push(auth: GitAuth, remote: String = "origin") = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.push()
            .setRemote(remote)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.token))
            .call()
        git.close()
    }

    suspend fun pull(auth: GitAuth, remote: String = "origin") = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.pull()
            .setRemote(remote)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.token))
            .call()
        git.close()
    }

    // 6. 设置远程地址
    suspend fun addRemote(name: String, url: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        val config = git.repository.config
        config.setString("remote", name, "url", url)
        config.save()
        git.close()
    }
}