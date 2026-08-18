package com.aiagents.data.ai.tools.local

import android.Manifest
import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.ShizukuController
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

private fun granted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private object TorchState {
    @Volatile
    var on: Boolean = false
}

private suspend fun runtimeBlocked(eventBus: AppEventBus, permissions: List<String>): List<UIMessagePart> {
    eventBus.emit(AppEvent.RequestRuntimePermissions(permissions))
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Missing permission(s) ${permissions.joinToString(", ")}. " +
                        "The system permission dialog has been requested; ask the user to allow it, then retry."
                )
            }.toString()
        )
    )
}

private fun launchIntentFor(context: Context, target: String): Intent? {
    val pm = context.packageManager
    pm.getLaunchIntentForPackage(target)?.let { return it }
    // 未命中包名时按应用名(标签)查找
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val exact = pm.queryIntentActivities(query, 0)
        .mapNotNull { it.activityInfo }
        .firstOrNull { labelOf(context, it.packageName) == target }
    val fuzzy = pm.queryIntentActivities(query, 0)
        .mapNotNull { it.activityInfo }
        .firstOrNull { labelOf(context, it.packageName).contains(target, ignoreCase = true) }
    return (exact ?: fuzzy)?.let {
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(it.packageName, it.name)
    }
}

private fun labelOf(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrElse { packageName }

/** 列出已安装的可启动应用(包名/应用名), 供 AI 定位要打开的应用。 */
internal fun buildAppListTool(context: Context): Tool = Tool(
    name = "app_list",
    description = """
        List installed launchable apps with their package names and display names.
        Use before app_launch to find the correct package name for an app.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword to filter apps by name or package (case-insensitive).")
                })
                put("max", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of results. Default 40.")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        val p = it.jsonObject
        val query = p["query"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val max = p["max"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 200) ?: 40
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        val results = mutableListOf<Pair<String, String>>()
        for (ri in launchable) {
            val activity = ri.activityInfo ?: continue
            val pkg = activity.packageName
            val name = labelOf(context, pkg)
            if (query != null && !name.contains(query, ignoreCase = true) && !pkg.contains(query, ignoreCase = true)) {
                continue
            }
            results.add(pkg to name)
        }
        results.sortBy { it.second.lowercase() }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("total", results.size)
                    put("apps", buildJsonArray {
                        for ((pkg, name) in results.take(max)) {
                            add(
                                buildJsonObject {
                                    put("package", pkg)
                                    put("name", name)
                                }
                            )
                        }
                    })
                }.toString()
            )
        )
    },
)

/** 快捷启动应用: 按包名或应用名启动。 */
internal fun buildAppLaunchTool(context: Context): Tool = Tool(
    name = "app_launch",
    description = """
        Launch an installed app. Accept a package name or an app display name (matched by app_list).
        Brings the app to the foreground.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("app", buildJsonObject {
                    put("type", "string")
                    put("description", "Package name (e.g. com.android.settings) or app display name.")
                })
            },
            required = listOf("app"),
        )
    },
    needsApproval = { false },
    execute = {
        val target = it.jsonObject["app"]?.jsonPrimitive?.contentOrNull
        if (target.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"app is required"}"""))
        } else {
            val intent = launchIntentFor(context, target)
            if (intent == null) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "APP_NOT_FOUND")
                            put("message", "No launchable app matched: $target. Use app_list first.")
                        }.toString()
                    )
                )
            } else {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                runCatching { context.startActivity(intent) }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("launched", true)
                            put("package", intent.component?.packageName ?: target)
                        }.toString()
                    )
                )
            }
        }
    },
)

/** 强制停止应用(需 Shizuku shell 权限)。 */
internal fun buildStopAppTool(context: Context): Tool = Tool(
    name = "stop_app",
    description = """
        Force-stop a running app by package name (like swiping it away / Force Stop).
        Requires Shizuku shell permission.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package name to force-stop.")
                })
            },
            required = listOf("package"),
        )
    },
    needsApproval = { false },
    execute = {
        val pkg = it.jsonObject["package"]?.jsonPrimitive?.contentOrNull
        if (pkg.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"package is required"}"""))
        } else if (!ShizukuController.isBinderAlive() || !ShizukuController.isPermissionGranted()) {
            shizukuBlocked()
        } else {
            val result = ShizukuController.exec("am force-stop $pkg")
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", result.exitCode == 0)
                        put("package", pkg)
                        result.error?.let { put("error", it) }
                    }.toString()
                )
            )
        }
    },
)

/** 打开链接(URL/深链接)。 */
internal fun buildOpenUrlTool(context: Context): Tool = Tool(
    name = "open_url",
    description = """
        Open a URL or deep link in the default browser/app.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The http(s) URL or deep link to open, e.g. https://example.com or app-scheme://")
                })
            },
            required = listOf("url"),
        )
    },
    needsApproval = { false },
    execute = {
        val url = it.jsonObject["url"]?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"url is required"}"""))
        } else {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "OPEN_FAILED")
                            put("message", "No handler for: $url")
                        }.toString()
                    )
                )
            }
            listOf(UIMessagePart.Text("""{"opened":true}"""))
        }
    },
)

/** 打开拨号盘(预填号码, 不直接拨出)。 */
internal fun buildOpenDialerTool(context: Context): Tool = Tool(
    name = "open_dialer",
    description = """
        Open the dialer with a phone number pre-filled (does not place the call automatically).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("number", buildJsonObject {
                    put("type", "string")
                    put("description", "Phone number, e.g. 10086.")
                })
            },
            required = listOf("number"),
        )
    },
    needsApproval = { false },
    execute = {
        val number = it.jsonObject["number"]?.jsonPrimitive?.contentOrNull
        if (number.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"number is required"}"""))
        } else {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "OPEN_FAILED")
                            put("message", "No dialer found.")
                        }.toString()
                    )
                )
            }
            listOf(UIMessagePart.Text("""{"opened":true}"""))
        }
    },
)

/** 音量控制: get/up/down/mute/unmute/set。 */
internal fun buildVolumeControlTool(context: Context): Tool = Tool(
    name = "volume_control",
    description = """
        Control the media volume. action: get (returns current/max level),
        up, down, mute, unmute, or set (with level 0-100 percent).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "get | up | down | mute | unmute | set")
                })
                put("level", buildJsonObject {
                    put("type", "integer")
                    put("description", "Target volume 0-100 (only for action=set).")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { false },
    execute = {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            listOf(UIMessagePart.Text("""{"error":"NO_AUDIO","message":"Audio service unavailable"}"""))
        } else {
            val action = it.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: "get"
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            when (action) {
                "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                "set" -> {
                    val level = it.jsonObject["level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val scaled = (level.coerceIn(0, 100) * max / 100)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, scaled, 0)
                }
            }
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("action", action)
                        put("level", current)
                        put("max", max)
                        put("percent", if (max > 0) current * 100 / max else 0)
                    }.toString()
                )
            )
        }
    },
)

/** 媒体控制: 播放/暂停/上一首/下一首(需 Shizuku shell 权限)。 */
internal fun buildMediaControlTool(context: Context): Tool = Tool(
    name = "media_control",
    description = """
        Control media playback globally. action: play | pause | play_pause | next | previous | stop.
        Works with any media player (music/video). Requires Shizuku shell permission.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "play | pause | play_pause | next | previous | stop")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { false },
    execute = {
        val action = it.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: "play_pause"
        val keyCode = when (action) {
            "play" -> 126
            "pause" -> 127
            "next" -> 87
            "previous" -> 88
            "stop" -> 86
            else -> 85
        }
        if (!ShizukuController.isBinderAlive() || !ShizukuController.isPermissionGranted()) {
            shizukuBlocked()
        } else {
            val result = ShizukuController.exec("input keyevent $keyCode")
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", result.exitCode == 0)
                        put("action", action)
                        result.error?.let { put("error", it) }
                    }.toString()
                )
            )
        }
    },
)

/** 设置屏幕亮度(需 Shizuku shell 权限)。 */
internal fun buildSetBrightnessTool(context: Context): Tool = Tool(
    name = "set_brightness",
    description = """
        Set the screen brightness (disables auto brightness). level is 0-100 percent.
        Requires Shizuku shell permission.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("level", buildJsonObject {
                    put("type", "integer")
                    put("description", "Brightness 0-100 percent.")
                })
            },
            required = listOf("level"),
        )
    },
    needsApproval = { false },
    execute = {
        val level = it.jsonObject["level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
        if (level < 0 || level > 100) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"level must be 0-100"}"""))
        } else if (!ShizukuController.isBinderAlive() || !ShizukuController.isPermissionGranted()) {
            shizukuBlocked()
        } else {
            val value = level * 255 / 100
            val result = ShizukuController.exec(
                "settings put system screen_brightness_mode 0 && settings put system screen_brightness $value"
            )
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", result.exitCode == 0)
                        put("level", level)
                        result.error?.let { put("error", it) }
                    }.toString()
                )
            )
        }
    },
)

/** 震动(普通权限, 无需 Shizuku)。 */
internal fun buildVibrateTool(context: Context): Tool = Tool(
    name = "vibrate",
    description = "Vibrate the device for a short duration (default 300ms).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Vibration length in milliseconds. Default 300.")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        val duration = it.jsonObject["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 300L
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator == null) {
            listOf(UIMessagePart.Text("""{"error":"NO_VIBRATOR","message":"No vibrator available"}"""))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(duration.coerceIn(50, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
            listOf(UIMessagePart.Text("""{"vibrated":true,"duration_ms":$duration}"""))
        }
    },
)

/** 手电筒开关(需 CAMERA 运行时权限)。 */
internal fun buildFlashlightTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "flashlight",
    description = "Turn the camera flashlight/torch on, off, or toggle it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "on | off | toggle")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!granted(context, Manifest.permission.CAMERA)) {
            runtimeBlocked(eventBus, listOf(Manifest.permission.CAMERA))
        } else {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cm?.cameraIdList?.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cm == null || cameraId == null) {
                listOf(UIMessagePart.Text("""{"error":"NO_FLASH","message":"No camera flash available"}"""))
            } else {
                val action = it.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: "toggle"
                val target = when (action) {
                    "on" -> true
                    "off" -> false
                    else -> !TorchState.on
                }
                runCatching { cm.setTorchMode(cameraId, target) }.onFailure {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "TORCH_FAILED")
                                put("message", it.message ?: "setTorchMode failed")
                            }.toString()
                        )
                    )
                }
                TorchState.on = target
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("action", if (target) "on" else "off")
                        }.toString()
                    )
                )
            }
        }
    },
)

/** Wi-Fi 开关: 优先走 Shizuku `svc wifi`, 否则退化为 WifiManager(部分新系统无效)。 */
internal fun buildWifiToggleTool(context: Context): Tool = Tool(
    name = "wifi_toggle",
    description = """
        Turn Wi-Fi on/off or query its status.
        Uses Shizuku (svc wifi) when available; falls back to the Wi-Fi manager otherwise.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "on | off | status")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { false },
    execute = {
        val action = it.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: "status"
        if (action == "status") {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("enabled", wm?.isWifiEnabled ?: false) }.toString()
                )
            )
        } else if (!ShizukuController.isBinderAlive() || !ShizukuController.isPermissionGranted()) {
            shizukuBlocked()
        } else {
            val cmd = when (action) {
                "on" -> "svc wifi enable"
                "off" -> "svc wifi disable"
                else -> ""
            }
            if (cmd.isEmpty()) {
                listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"action must be on/off/status"}"""))
            } else {
                val result = ShizukuController.exec(cmd)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result.exitCode == 0)
                            put("action", action)
                            result.error?.let { put("error", it) }
                        }.toString()
                    )
                )
            }
        }
    },
)

/** 下拉/收起系统通知栏(需 Shizuku shell 权限)。 */
internal fun buildNotificationShadeTool(context: Context): Tool = Tool(
    name = "notification_shade",
    description = """
        Expand or collapse the system notification shade. action: expand | collapse.
        Requires Shizuku shell permission.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "expand | collapse")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { false },
    execute = {
        val action = it.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: "expand"
        val cmd = when (action) {
            "expand" -> "cmd statusbar expand-notifications"
            "collapse" -> "cmd statusbar collapse"
            else -> ""
        }
        if (cmd.isEmpty()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"action must be expand/collapse"}"""))
        } else if (!ShizukuController.isBinderAlive() || !ShizukuController.isPermissionGranted()) {
            shizukuBlocked()
        } else {
            val result = ShizukuController.exec(cmd)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", result.exitCode == 0)
                        put("action", action)
                        result.error?.let { put("error", it) }
                    }.toString()
                )
            )
        }
    },
)

/** 安装/更新 APK(需 Shizuku shell 权限, 免去安装确认)。 */
internal fun buildInstallApkTool(
    context: Context,
    pathResolver: WorkspacePathResolver,
): Tool = Tool(
    name = "install_apk",
    description = """
        Install or update an APK from a device file path. Uses Shizuku `pm install`, no user confirmation needed.
        PATH CORRESPONDENCE: this tool works on the REAL Android filesystem. You may pass either:
        - a device path, e.g. /sdcard/Download/xxx.apk or /storage/emulated/0/xxx.apk, or
        - a workspace path, e.g. /workspace/xxx.apk or /sd/xxx.apk or /screenshots/xxx.apk.
        Workspace paths are resolved automatically (the file is copied to the shared /sdcard/AI-Agent/ dir when needed).
        To install an APK you downloaded into the container, download it under /sd (visible on device at /sdcard/AI-Agent/sd/) and pass that path.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("apk_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to the .apk on the device OR in the workspace (auto-resolved).")
                })
            },
            required = listOf("apk_path"),
        )
    },
    needsApproval = { false },
    execute = {
        val apkPath = it.jsonObject["apk_path"]?.jsonPrimitive?.contentOrNull
        if (apkPath.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"apk_path is required"}"""))
        } else if (!ShizukuController.isBinderAlive() || !ShizukuController.isPermissionGranted()) {
            shizukuBlocked()
        } else {
            val devicePath = pathResolver.toDevicePath(apkPath, needsShellRead = true)
            if (devicePath == null) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "FILE_NOT_FOUND")
                            put("message", "No such APK file: $apkPath (also tried resolving it as a workspace path). Download or copy it into /sd or /workspace first, then retry.")
                        }.toString()
                    )
                )
            } else {
                val result = ShizukuController.exec("pm install -r -d ${devicePath.shellQuote()}", timeoutMs = 120_000)
                val output = (result.stdout + result.stderr).take(1500)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result.exitCode == 0 || output.contains("Success"))
                            put("device_path", devicePath)
                            put("output", output)
                            result.error?.let { put("error", it) }
                        }.toString()
                    )
                )
            }
        }
    },
)

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

/** 设置壁纸(普通权限, 从设备图片路径加载)。 */
internal fun buildSetWallpaperTool(
    context: Context,
    pathResolver: WorkspacePathResolver,
): Tool = Tool(
    name = "set_wallpaper",
    description = """
        Set the device wallpaper from an image file.
        PATH CORRESPONDENCE: this tool works on the REAL Android filesystem. You may pass either:
        - a device path, e.g. /sdcard/Download/photo.jpg or /sdcard/AI-Agent/screenshots/x.png, or
        - a workspace path, e.g. /screenshots/x.png, /workspace/img.png or /sd/img.png (auto-resolved).
        To use an image you generated/saved in the container, save it under /screenshots or /sd and pass that path.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("image_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to the image on the device OR in the workspace (auto-resolved).")
                })
            },
            required = listOf("image_path"),
        )
    },
    needsApproval = { false },
    execute = {
        val path = it.jsonObject["image_path"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"image_path is required"}"""))
        } else {
            val devicePath = pathResolver.toDevicePath(path, needsShellRead = false)
            if (devicePath == null || !File(devicePath).exists()) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "FILE_NOT_FOUND")
                            put("message", "No such image file: $path (also tried resolving it as a workspace path).")
                        }.toString()
                    )
                )
            } else {
                val bitmap = runCatching { BitmapFactory.decodeFile(devicePath) }.getOrNull()
                if (bitmap == null) {
                    listOf(UIMessagePart.Text("""{"error":"DECODE_FAILED","message":"Not a valid image"}"""))
                } else {
                    runCatching { WallpaperManager.getInstance(context).setBitmap(bitmap) }.onFailure {
                        bitmap.recycle()
                        return@Tool listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("error", "SET_FAILED")
                                    put("message", it.message ?: "setBitmap failed")
                                }.toString()
                            )
                        )
                    }
                    bitmap.recycle()
                    listOf(UIMessagePart.Text("""{"ok":true,"image":$path}"""))
                }
            }
        }
    },
)

/** 发送短信(需 SEND_SMS 运行时权限)。 */
internal fun buildSendSmsTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "send_sms",
    description = "Send an SMS message. Requires the SEND_SMS permission.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("number", buildJsonObject {
                    put("type", "string")
                    put("description", "Recipient phone number.")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Message body.")
                })
            },
            required = listOf("number", "text"),
        )
    },
    needsApproval = { false },
    execute = {
        val p = it.jsonObject
        val number = p["number"]?.jsonPrimitive?.contentOrNull
        val text = p["text"]?.jsonPrimitive?.contentOrNull
        if (number.isNullOrBlank() || text.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"number and text are required"}"""))
        } else if (!granted(context, Manifest.permission.SEND_SMS)) {
            runtimeBlocked(eventBus, listOf(Manifest.permission.SEND_SMS))
        } else {
            runCatching { SmsManager.getDefault().sendTextMessage(number, null, text, null, null) }.onFailure {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "SEND_FAILED")
                            put("message", it.message ?: "sendTextMessage failed")
                        }.toString()
                    )
                )
            }
            listOf(UIMessagePart.Text("""{"sent":true,"number":$number}"""))
        }
    },
)

/** 读取联系人(需 READ_CONTACTS 运行时权限)。 */
internal fun buildReadContactsTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "read_contacts",
    description = """
        List contacts with their names and phone numbers.
        Requires the READ_CONTACTS permission. Optional query filters by name/number.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword to filter contacts (case-insensitive).")
                })
                put("max", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of results. Default 30.")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) {
            runtimeBlocked(eventBus, listOf(Manifest.permission.READ_CONTACTS))
        } else {
            val p = it.jsonObject
            val query = p["query"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val max = p["max"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 200) ?: 30
            val result = buildJsonObject {
                val list = mutableListOf<Pair<String, String>>()
                val cursor = runCatching {
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        null, null, null,
                    )
                }.getOrNull()
                cursor?.use { c ->
                    val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val name = c.getString(nameIdx) ?: ""
                        val number = c.getString(numIdx) ?: ""
                        if (query != null &&
                            !name.contains(query, ignoreCase = true) &&
                            !number.contains(query, ignoreCase = true)
                        ) continue
                        list.add(name to number)
                    }
                }
                put("total", list.size)
                put("contacts", buildJsonArray {
                    for ((name, number) in list.take(max)) {
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("number", number)
                            }
                        )
                    }
                })
            }
            listOf(UIMessagePart.Text(result.toString()))
        }
    },
)

/** 获取最后已知定位(需定位运行时权限)。 */
internal fun buildGetLocationTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "get_location",
    description = """
        Return the last known GPS/network location (latitude, longitude, accuracy).
        Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION.
    """.trimIndent().replace("\n", " "),
    parameters = { null },
    needsApproval = { false },
    execute = {
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            runtimeBlocked(
                eventBus,
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        } else {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            val location = lm?.let {
                (if (fine) it.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) else null)
                    ?: it.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            }
            if (location == null) {
                eventBus.emit(AppEvent.OpenLocationSettings)
                listOf(
                    UIMessagePart.Text(
                        """{"error":"NO_LOCATION","message":"No recent location fix available. The location settings page has been opened; ask the user to enable location, then retry."}"""
                    )
                )
            } else {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("latitude", location.latitude)
                            put("longitude", location.longitude)
                            put("accuracy_m", location.accuracy)
                            put("time", location.time)
                        }.toString()
                    )
                )
            }
        }
    },
)

/** 直接拨打电话(需 CALL_PHONE 运行时权限)。 */
internal fun buildPhoneCallTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "phone_call",
    description = """
        Place a phone call to the given number immediately.
        Requires the CALL_PHONE permission. Use open_dialer for a non-calling preview.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("number", buildJsonObject {
                    put("type", "string")
                    put("description", "Phone number to call.")
                })
            },
            required = listOf("number"),
        )
    },
    needsApproval = { false },
    execute = {
        val number = it.jsonObject["number"]?.jsonPrimitive?.contentOrNull
        if (number.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"number is required"}"""))
        } else if (!granted(context, Manifest.permission.CALL_PHONE)) {
            runtimeBlocked(eventBus, listOf(Manifest.permission.CALL_PHONE))
        } else {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "CALL_FAILED")
                            put("message", it.message ?: "startActivity failed")
                        }.toString()
                    )
                )
            }
            listOf(UIMessagePart.Text("""{"calling":true,"number":$number}"""))
        }
    },
)
