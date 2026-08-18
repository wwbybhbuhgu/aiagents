package com.aiagents.data.automation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import com.aiagents.data.files.FileFolders
import com.aiagents.di.AI_AGENT_SHARED_DIR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.File
import java.io.InputStream

/**
 * Shizuku 控制器: 通过 Shizuku 以 shell/root 权限执行命令并截图。
 *
 * 截图是 Shizuku 在本项目中的核心用途——避免 MediaProjection 的二次授权弹窗:
 * 以 shell 权限直接运行 `screencap -p` 把 PNG 写入应用私有目录。
 */
object ShizukuController {

    private const val TAG = "ShizukuController"
    const val PERMISSION_REQUEST_CODE = 10001

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null
    }

    fun isBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    fun getVersion(): Int =
        runCatching { Shizuku.getVersion() }.getOrDefault(-1)

    fun getUid(): Int =
        runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** 设备是否可用 root(su)。Shizuku 不可用时作为兜底通道。 */
    fun hasRoot(): Boolean = rootShellAvailable()

    /** 是否存在可用特权通道: Shizuku 或 root。 */
    fun hasPrivilege(): Boolean = isBinderAlive() || hasRoot()

    private fun rootShellAvailable(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
        val ok = p.waitFor() == 0 && p.inputStream.readBytes().decodeToString().trim().toIntOrNull() == 0
        runCatching { p.destroy() }
        ok
    }.getOrDefault(false)

    /** 请求权限并等待用户授权(最多 [timeoutMs])。Shizuku server 未运行时直接返回 false。 */
    suspend fun ensurePermission(timeoutMs: Long = 30_000): Boolean =
        withContext(Dispatchers.IO) {
            if (isPermissionGranted()) return@withContext true
            if (!isBinderAlive()) return@withContext false
            requestPermission()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                delay(500)
                if (isPermissionGranted()) return@withContext true
            }
            isPermissionGranted()
        }

    /** 通过反射调用已下线的私有 `Shizuku.newProcess`(服务端仍支持)。 */
    private fun createProcess(cmd: Array<String>): ShizukuRemoteProcess? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, cmd, null, null) as ShizukuRemoteProcess
    }.onFailure {
        Log.w(TAG, "newProcess reflection failed", it)
    }.getOrNull()

    suspend fun exec(command: String, timeoutMs: Long = 30000): ShellResult =
        withContext(Dispatchers.IO) {
            // Shizuku 不可用时回退到 root(su) 通道
            if (!isBinderAlive() && rootShellAvailable()) {
                return@withContext execRoot(command, timeoutMs)
            }
            val process = createProcess(arrayOf("sh", "-c", command))
                ?: return@withContext ShellResult(-1, "", "", "Shizuku 不可用或 newProcess 失败")
            try {
                val stdout = process.inputStream.readUpToTimeout(timeoutMs)
                val stderr = process.errorStream.readUpToTimeout(timeoutMs)
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                ShellResult(code, stdout.decodeToString(), stderr.decodeToString(), null)
            } catch (e: Exception) {
                ShellResult(-1, "", "", e.message)
            } finally {
                runCatching { process.destroy() }
            }
        }

    private fun execRoot(command: String, timeoutMs: Long): ShellResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).start()
        val stdout = process.inputStream.readUpToTimeout(timeoutMs)
        val stderr = process.errorStream.readUpToTimeout(timeoutMs)
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        ShellResult(code, stdout.decodeToString(), stderr.decodeToString(), null)
    }.getOrElse {
        ShellResult(-1, "", "", it.message)
    }

    /** 以 `screencap -p` 截取全屏 PNG 并写入 [outFile]。 */
    suspend fun screenshotPng(outFile: File, timeoutMs: Long = 20000): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // Shizuku 不可用时回退到 root(su) 通道
                if (!isBinderAlive() && rootShellAvailable()) {
                    val p = ProcessBuilder("su", "-c", "screencap -p").start()
                    val bytes = p.inputStream.readUpToTimeout(timeoutMs)
                    val code = runCatching { p.waitFor() }.getOrDefault(-1)
                    if (code != 0 || bytes.size < 16) return@withContext false
                    outFile.parentFile?.mkdirs()
                    outFile.writeBytes(bytes)
                    return@withContext true
                }
                val process = createProcess(arrayOf("screencap", "-p"))
                    ?: return@withContext false
                val bytes = try {
                    process.inputStream.readUpToTimeout(timeoutMs)
                } finally {
                    runCatching { process.destroy() }
                }
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                if (code != 0 || bytes.size < 16) return@withContext false
                outFile.parentFile?.mkdirs()
                outFile.writeBytes(bytes)
                true
            }.getOrDefault(false)
        }

    /** 截屏保存目录: 共享存储 `AI-Agent/screenshots`, 与 workspace 的 `/screenshots` bind mount 对应。 */
    fun screenshotDir(context: Context): File {
        val dir = File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/${FileFolders.SCREENSHOTS}")
        dir.mkdirs()
        runCatching { dir.setReadable(true, false) }
        runCatching { Runtime.getRuntime().exec(arrayOf("chmod", "771", dir.absolutePath)).waitFor() }
        return dir
    }

    private fun InputStream.readUpToTimeout(timeoutMs: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            try {
                val chunk = ByteArray(8192)
                while (true) {
                    val n = read(chunk)
                    if (n <= 0) break
                    buffer.write(chunk, 0, n)
                }
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }
        thread.start()
        done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return buffer.toByteArray()
    }
}
