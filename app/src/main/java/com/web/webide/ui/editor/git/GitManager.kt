package com.web.webide.ui.editor.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitManager(private val projectPath: String) {
    private val rootDir = File(projectPath)

    fun isGitRepo(): Boolean = File(rootDir, ".git").exists()

    suspend fun getBranches(): List<GitBranch> = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext emptyList()
        val git = Git.open(rootDir)
        val repo = git.repository
        val currentBranchRef = repo.fullBranch // 例如 refs/heads/main

        val branchList = mutableListOf<GitBranch>()

        // 1. 获取所有引用
        val refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()

        refs.forEach { ref ->
            val fullName = ref.name
            var displayName = fullName
            var type = BranchType.LOCAL

            if (fullName.startsWith(Constants.R_HEADS)) {
                // 本地分支: refs/heads/xxx
                displayName = fullName.substring(Constants.R_HEADS.length)
                type = BranchType.LOCAL
            } else if (fullName.startsWith(Constants.R_REMOTES)) {
                // 远程分支: refs/remotes/origin/xxx
                displayName = fullName.substring(Constants.R_REMOTES.length)
                type = BranchType.REMOTE
            }

            // 判断是否是当前 HEAD 指向的分支
            val isCurrent = (fullName == currentBranchRef)

            branchList.add(GitBranch(displayName, fullName, type, isCurrent))
        }

        git.close()
        // 排序：当前分支在前，然后按本地/远程排序，最后按名字
        branchList.sortedWith(compareByDescending<GitBranch> { it.isCurrent }
            .thenBy { it.type } // LOCAL (0) 在前, REMOTE (1) 在后 (enum 顺序)
            .thenBy { it.name }
        )
    }
    // --- 基础操作 ---
    suspend fun initRepo() = withContext(Dispatchers.IO) {
        Git.init().setDirectory(rootDir).call().close()
    }

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

    // --- 提交与推送 ---
    suspend fun commitAll(message: String, author: String, email: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.add().addFilepattern(".").call()

        // 处理删除的文件
        val status = git.status().call()
        if (status.missing.isNotEmpty() || status.removed.isNotEmpty()) {
            val rm = git.rm()
            status.missing.forEach { rm.addFilepattern(it) }
            status.removed.forEach { rm.addFilepattern(it) }
            rm.call()
        }

        // 🔥 核心修复：同时设置 Author 和 Committer，避免显示 "root"
        val person = PersonIdent(author, email)
        git.commit()
            .setMessage(message)
            .setAuthor(person)
            .setCommitter(person)
            .call()

        git.close()
    }

    suspend fun push(auth: GitAuth, remote: String = "origin") = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.push()
            .setRemote(remote)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.token))
            .call()
        git.close()
    }

    // --- 拉取与更新 ---
    suspend fun fetch(auth: GitAuth, remote: String = "origin") = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.fetch()
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

    suspend fun pullRebase(auth: GitAuth, remote: String = "origin") = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.pull()
            .setRemote(remote)
            .setRebase(true)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.token))
            .call()
        git.close()
    }

    // --- 分支与标签 ---
    suspend fun createBranch(name: String, checkout: Boolean = true) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.branchCreate().setName(name).call()
        if (checkout) {
            git.checkout().setName(name).call()
        }
        git.close()
    }

    suspend fun createTag(name: String, message: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.tag().setName(name).setMessage(message).call()
        git.close()
    }


    suspend fun checkout(name: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.checkout().setName(name).call()
        git.close()
    }

    suspend fun getCurrentBranch(): String = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext ""
        val git = Git.open(rootDir)
        val b = git.repository.branch
        git.close()
        b ?: "HEAD"
    }

    suspend fun addRemote(name: String, url: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        val config = git.repository.config
        config.setString("remote", name, "url", url)
        config.save()
        git.close()
    }

    // --- 🔥 获取提交历史树 (带 Ref 信息) ---
    suspend fun getCommitLog(): Pair<List<RevCommit>, Map<String, List<GitRefUI>>> = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext Pair(emptyList(), emptyMap())
        val git = Git.open(rootDir)
        val repo = git.repository

        // 1. 获取所有 Ref (HEAD, branches, tags) 并建立 Hash -> RefList 的映射
        val refMap = mutableMapOf<String, MutableList<GitRefUI>>()

        // 获取 HEAD
        val head = repo.resolve(Constants.HEAD)
        if (head != null) {
            val headId = head.name
            refMap.getOrPut(headId) { mutableListOf() }.add(GitRefUI("HEAD", RefType.HEAD))
        }

        // 获取所有引用
        repo.refDatabase.getRefs().forEach { ref ->
            val id = ref.objectId.name
            val name = ref.name
            val simpleName = RepositoryUtils.shortenRefName(name)

            val type = when {
                name.startsWith(Constants.R_HEADS) -> RefType.LOCAL_BRANCH
                name.startsWith(Constants.R_REMOTES) -> RefType.REMOTE_BRANCH
                name.startsWith(Constants.R_TAGS) -> RefType.TAG
                else -> RefType.LOCAL_BRANCH
            }

            // 如果不是单纯的 HEAD 指针，才添加
            if (name != Constants.HEAD) {
                refMap.getOrPut(id) { mutableListOf() }.add(GitRefUI(simpleName, type))
            }
        }

        // 2. 遍历 Commit
        val walk = RevWalk(repo)
        val allRefs = repo.refDatabase.getRefs()
        allRefs.forEach { ref ->
            if (ref.objectId != null) {
                try {
                    walk.markStart(walk.parseCommit(ref.objectId))
                } catch (e: Exception) { /* ignore */ }
            }
        }
        walk.sort(org.eclipse.jgit.revwalk.RevSort.COMMIT_TIME_DESC)
        walk.sort(org.eclipse.jgit.revwalk.RevSort.TOPO)

        val commits = mutableListOf<RevCommit>()
        for (commit in walk) {
            commits.add(commit)
        }

        walk.dispose()
        git.close()

        Pair(commits, refMap)
    }
}

// 辅助工具类
object RepositoryUtils {
    fun shortenRefName(refName: String): String {
        if (refName.startsWith(Constants.R_HEADS)) return refName.substring(Constants.R_HEADS.length)
        if (refName.startsWith(Constants.R_TAGS)) return refName.substring(Constants.R_TAGS.length)
        if (refName.startsWith(Constants.R_REMOTES)) return refName.substring(Constants.R_REMOTES.length)
        return refName
    }
}