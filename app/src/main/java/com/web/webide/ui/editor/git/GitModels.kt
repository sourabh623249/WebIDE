/*
 * GitModels.kt
 */
package com.web.webide.ui.editor.git

import androidx.compose.ui.graphics.Color

// --- 以下保持原有的 Git 模型不变 ---

data class GitCommitUI(
    val hash: String,
    val shortHash: String,
    val message: String,
    val fullMessage: String,
    val author: String,
    val email: String,
    val time: Long,
    val parents: List<String>,
    val refs: List<GitRefUI>,
    val lane: Int,
    val totalLanes: Int,
    val childLanes: List<Int>,
    val parentLanes: List<Int>,
    val color: Color
)

data class GitRefUI(val name: String, val type: RefType)
enum class RefType { HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG }

data class GitBranch(
    val name: String,
    val fullRef: String,
    val type: BranchType,
    val isCurrent: Boolean
)

enum class BranchType { LOCAL, REMOTE }

data class GitFileChange(val filePath: String, val status: GitFileStatus)

data class GitAuth(
    val type: AuthType = AuthType.HTTPS,
    val username: String = "",
    val token: String = "",
    val privateKey: String = "",
    val passphrase: String = ""
)

enum class AuthType { HTTPS, SSH }

enum class GitFileStatus { ADDED, MODIFIED, UNTRACKED, MISSING, REMOVED, CONFLICTING }