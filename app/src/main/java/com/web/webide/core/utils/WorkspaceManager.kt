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


package com.web.webide.core.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

object WorkspaceManager {

    private const val PREFS_NAME = "webide_prefs"
    private const val KEY_WORKSPACE_PATH = "workspace_path"
    private const val KEY_IS_CONFIGURED = "is_workspace_configured"

    fun getDefaultPath(context: Context): String {
        val dir = context.getExternalFilesDir(null)
        return dir?.absolutePath ?: context.filesDir.absolutePath
    }

    /**
     * 获取工作目录（带自动纠错功能）
     */
    fun getWorkspacePath(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_WORKSPACE_PATH, null)

        // 1. 如果没存过，返回默认
        if (savedPath.isNullOrBlank()) {
            return getDefaultPath(context)
        }

        // 🔥🔥🔥 修复点 2：更稳健的Path检查逻辑 🔥🔥🔥
        // 之前的逻辑依赖绝对Path字符串匹配，容易因为 /sdcard 与 /storage/emulated/0 的差异导致误判
        // 现在的逻辑：只要Path包含 "Android/data"，就检查它YesNo包含"当前App的Package Name"
        if (savedPath.contains("/Android/data/")) {
            val packageName = context.packageName
            // 如果Path里连Package Name都不包含，说明这个Path肯定Yes其他App的（或者旧Package Name的），我们没有Permission，必须重置
            if (!savedPath.contains(packageName)) {
                android.util.Log.e("WorkspaceManager", "检测到失效Path(Package Name不匹配): $savedPath，重置为默认")
                val validPath = getDefaultPath(context)
                saveWorkspacePath(context, validPath) // 自动Save纠正后的Path
                return validPath
            }
        }

        return savedPath
    }

    fun isWorkspaceConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 只要这个值为 true，就说明用户点击过“Confirm并继续”
        return prefs.getBoolean(KEY_IS_CONFIGURED, false)
    }

    fun getWorkspacePathFlow(context: Context): Flow<String> = callbackFlow {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_WORKSPACE_PATH) {
                trySend(getWorkspacePath(context))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getWorkspacePath(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun saveWorkspacePath(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_WORKSPACE_PATH, path)
            // ✅ Off键：Settings为 true，表示用户已Done初始化向导
            putBoolean(KEY_IS_CONFIGURED, true)
        }
        ensurePathExists(context, path)
    }

    fun ensurePathExists(context: Context, path: String): Boolean {
        val file = File(path)
        if (file.exists() && file.isDirectory) return true

        try {
            if (path.contains(context.packageName)) {
                return file.mkdirs() || file.exists()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return file.mkdirs() || file.exists()
    }
}