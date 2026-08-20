package com.aiagents.data.memes

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File

/**
 * 内置表情包图库安装器
 *
 * 把 assets/memes 下的图库(official-001 / dafeiyu-001)首次解压到 filesDir/memes,
 * 该目录作为全局共享目录通过 bind mount 挂载到每个工作区容器的 /memes,
 * AI 在 markdown / HTML 卡片中直接用容器内绝对路径 /memes/<packId>/<path> 引用图片,
 * 无需走 content:// 协议。带版本标记, 仅在图库版本变化时重装。
 */
object MemeAssetsInstaller {
    private const val TAG = "MemeAssetsInstaller"
    private const val VERSION_FILE = ".meme_version"
    private const val ASSET_VERSION = "1.0.0"

    fun ensureInstalled(context: Context) {
        runCatching {
            val targetDir = File(context.filesDir, MEMES_DIR)
            val versionFile = File(targetDir, VERSION_FILE)
            if (versionFile.exists() && versionFile.readText() == ASSET_VERSION) return

            targetDir.deleteRecursively()
            targetDir.mkdirs()

            val assetManager = context.assets
            // 顶层包目录: official-001 / dafeiyu-001 / index.jsonl
            val packs = assetManager.list(MEMES_DIR) ?: arrayOf()
            var count = 0
            for (entry in packs) {
                if (entry.endsWith(".jsonl")) {
                    assetManager.open("$MEMES_DIR/$entry").use { input ->
                        File(targetDir, entry).outputStream().use { input.copyTo(it) }
                    }
                } else {
                    copyDir(assetManager, "$MEMES_DIR/$entry", File(targetDir, entry))
                    count++
                }
            }
            versionFile.writeText(ASSET_VERSION)
            Log.i(TAG, "Installed $count meme packs to $targetDir")
        }.onFailure {
            Log.e(TAG, "ensureInstalled failed", it)
        }
    }

    private fun copyDir(assets: AssetManager, assetPath: String, target: File) {
        val children = assets.list(assetPath) ?: arrayOf()
        if (children.isEmpty()) {
            // 文件
            assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { input.copyTo(it) }
            }
        } else {
            target.mkdirs()
            for (child in children) {
                copyDir(assets, "$assetPath/$child", File(target, child))
            }
        }
    }

    const val MEMES_DIR = "memes"
}