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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object SetupWorker {
    suspend fun prepareEnvironment(context: Context) {
        withContext(Dispatchers.IO) {
            val filesDir = context.filesDir
            // 这里的 parentFile 通常Yes /data/user/0/com.example.mytermux/
            val prefixDir = filesDir.parentFile!!
            val alpineDir = File(prefixDir, "local/alpine")
            val binDir = File(prefixDir, "local/bin")
            val libDir = File(prefixDir, "local/lib")

            // 1. Copy二进制File
            copyAsset(context, "proot", File(filesDir, "proot"))
            copyAsset(context, "libtalloc.so.2", File(filesDir, "libtalloc.so.2"))

            // 确保二进制File有执行Permission
            File(filesDir, "proot").setExecutable(true)

            // 2. Copy Rootfs压缩包 (注意：源FileYes rootfs.bin，目标存为 alpine.tar.gz)
            val rootfsTar = File(filesDir, "alpine.tar.gz")
            if (!rootfsTar.exists()) {
                copyAsset(context, "rootfs.bin", rootfsTar)
            }

            // 3. Off键修复：强制解压 Rootfs
            // 检查 /etc 目录YesNo存在，不存在说明没解压或解压失败
            val etcDir = File(alpineDir, "etc")
            if (!etcDir.exists()) {
                // 创建目标目录
                alpineDir.mkdirs()

                // 使用系统 tar 命令解压
                // -z: gzip, -x: extract, -f: file, -C: 目标目录
                val cmd = "tar -zxf ${rootfsTar.absolutePath} -C ${alpineDir.absolutePath}"
                try {
                    val process = Runtime.getRuntime().exec(cmd)
                    process.waitFor()
                    if (process.exitValue() != 0) {
                        // 如果 gzip 解压失败，尝试不带 z 参数 (防止有些 tar Version不支持)
                        Runtime.getRuntime().exec("tar -xf ${rootfsTar.absolutePath} -C ${alpineDir.absolutePath}").waitFor()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 4. 确保 init 脚本Yes最新的 (每次启动都覆盖，方便Debug)
            binDir.mkdirs()
            libDir.mkdirs()

            // 将 Proot 依赖库Copy到 local/lib (ReTerminal 的逻辑需要)
            copyAsset(context, "libtalloc.so.2", File(libDir, "libtalloc.so.2"))
            // 将 Proot Copy到 local/bin
            copyAsset(context, "proot", File(binDir, "proot"))
            File(binDir, "proot").setExecutable(true)
        }
    }

    private fun copyAsset(context: Context, assetName: String, destFile: File) {
        // 只有File不存在时才Copy (除了脚本，脚本通常很小且需要更新)
        if (!destFile.exists() || assetName.contains("so") || assetName == "proot") {
            try {
                context.assets.open(assetName).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}