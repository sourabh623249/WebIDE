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

import android.view.KeyEvent
import android.view.View
import android.widget.Button
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeyButton
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.termux.view.TerminalView

// 🔥 修改构造函数：接收 TerminalView 而不仅仅是 Session
class VirtualKeysListener(
    private val terminalView: TerminalView
) : VirtualKeysView.IVirtualKeysView {

    override fun onVirtualKeyButtonClick(
        view: View?,
        buttonInfo: VirtualKeyButton?,
        button: Button?,
    ) {
        val key = buttonInfo?.key ?: return
        val session = terminalView.currentSession ?: return

        // 🔥 核心修改：方向键使用 dispatchKeyEvent 模拟物理按键
        // 这样 TerminalEmulator 会自动处理 Application Cursor Mode (Vim等需要)
        when (key) {
            "UP" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
            "LEFT" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT" -> sendKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
            "HOME" -> sendKeyEvent(KeyEvent.KEYCODE_MOVE_HOME)
            "END" -> sendKeyEvent(KeyEvent.KEYCODE_MOVE_END)
            "PGUP" -> sendKeyEvent(KeyEvent.KEYCODE_PAGE_UP)
            "PGDN" -> sendKeyEvent(KeyEvent.KEYCODE_PAGE_DOWN)

            // 其他字符按键保持原样，直接写入 Session
            "ESC" -> session.write("\u001B")
            "TAB" -> session.write("\u0009")
            "ENTER" -> session.write("\r")
            else -> session.write(key)
        }
    }

    // 辅助函数：发送按下和抬起事件
    private fun sendKeyEvent(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        terminalView.dispatchKeyEvent(downEvent)
        terminalView.dispatchKeyEvent(upEvent)
    }

    override fun performVirtualKeyButtonHapticFeedback(
        view: View?,
        buttonInfo: VirtualKeyButton?,
        button: Button?
    ): Boolean {
        return false
    }
}