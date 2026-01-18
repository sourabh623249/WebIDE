// 文件: java/com/example/sorarunrun/terminal/TerminalConfig.kt
package com.web.webide.ui.terminal

object TerminalConfig {

    // === 动态颜色配置 ===
    const val VIRTUAL_KEYS_JSON = """
[
  [
    "ESC",
    {
      "key": "/",
      "popup": "\\"
    },
    {
      "key": "-",
      "popup": "|"
    },
    "HOME",
    "UP",
    "END",
    "PGUP"
  ],
  [
    "TAB",
    "CTRL",
    "ALT",
    "LEFT",
    "DOWN",
    "RIGHT",
    "PGDN"
  ]
]
"""
    // 获取背景色
    fun getBackgroundColor(isDark: Boolean): Int {
        return if (isDark) {
            0xFF000000.toInt() // 深色模式：纯黑
        } else {
            0xFFFFFFFF.toInt() // 浅色模式：纯白
        }
    }

}
