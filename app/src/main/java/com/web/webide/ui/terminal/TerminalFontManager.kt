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
package com.web.webide.ui.terminal

import android.content.Context
import android.graphics.Typeface

object TerminalFontManager {
    // 默认FontPath
    private const val DEFAULT_FONT_PATH = "ttf/JetBrainsMono-Regular.ttf"

    // 缓存 Typeface，避免重复加载
    private var cachedTypeface: Typeface? = null

    /**
     * 获取全局TerminalFont
     */
    fun getTypeface(context: Context): Typeface {
        // 如果已经加载过，直接返回缓存
        if (cachedTypeface != null) {
            return cachedTypeface!!
        }

        // 尝试加载Font
        return try {
            val font = Typeface.createFromAsset(context.assets, DEFAULT_FONT_PATH)
            cachedTypeface = font
            font
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果加载失败（File不存在），回退到系统等宽Font
            Typeface.MONOSPACE
        }
    }

}