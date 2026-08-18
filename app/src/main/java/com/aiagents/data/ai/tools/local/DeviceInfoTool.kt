package com.aiagents.data.ai.tools.local

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.provider.Telephony
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager
import androidx.core.content.ContextCompat
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

/**
 * 设备信息工具: 读取通知/短信, 供 AI 回答用户"最新通知/短信"类问题。
 * 需要对应权限; 未授权时提示用户手动开启(不代替用户授权)。
 */
internal fun buildDeviceInfoTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "get_device_info",
    description = """
        Read device information to answer questions about the user's device.
        Supported sources:
        - notifications: recent notifications (requires Notification access on Android 13+)
        - sms: recent text messages (requires SMS permission)
        - battery: current battery level (no permission needed)
        - system: comprehensive system info (Android version, device model, screen resolution,
          default input method, Shizuku status, network, storage, etc.) — no special permissions needed.
        Returns an error with instructions if the required permission is not granted;
        the user must grant it manually in system settings.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("notifications")
                            add("sms")
                            add("battery")
                            add("system")
                        }
                    )
                    put("description", "Which information to read")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max number of items to return. Default 10, max 50.")
                })
            },
            required = listOf("source"),
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val source = params["source"]?.jsonPrimitive?.contentOrNull ?: error("source is required")
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        when (source) {
            "notifications" -> {
                // 优先用通知监听服务读取其他应用的系统通知
                if (com.aiagents.service.NotificationRepository.connected) {
                    val items = com.aiagents.service.NotificationRepository.latest(limit, excludePackage = context.packageName)
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("source", "notifications")
                                put("count", items.size)
                                put("notifications", buildJsonArray {
                                    items.forEach { item ->
                                        add(buildJsonObject {
                                            put("package", item.packageName)
                                            put("title", item.title)
                                            put("text", item.text)
                                            put("when_ms", item.timestamp)
                                        })
                                    }
                                })
                            }.toString()
                        )
                    )
                } else {
                    // 通知监听未开启: 引导用户去系统设置开启"通知使用权"
                    eventBus.emit(AppEvent.OpenNotificationListenerSettings)
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "NO_PERMISSION")
                                put("source", "notifications")
                                put(
                                    "message",
                                    "Notification access is not enabled. The system notification access settings page has been opened; please ask the user to enable the AI Agents notification reader and try again."
                                )
                            }.toString()
                        )
                    )
                }
            }

            "sms" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    eventBus.emit(AppEvent.RequestSmsPermission)
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "NO_PERMISSION")
                                put("source", "sms")
                                put(
                                    "message",
                                    "SMS permission is not granted. The system permission dialog has been opened; please ask the user to allow SMS access and try again."
                                )
                            }.toString()
                        )
                    )
                } else {
                    val messages = readSms(context, limit)
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("source", "sms")
                                put("count", messages.size)
                                put("messages", buildJsonArray {
                                    messages.forEach { msg ->
                                        add(buildJsonObject {
                                            put("address", msg.address)
                                            put("body", msg.body)
                                            put("date_ms", msg.date)
                                            put("type", msg.type)
                                        })
                                    }
                                })
                            }.toString()
                        )
                    )
                }
            }

            "battery" -> {
                val batteryLevel = readBatteryLevel(context)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("source", "battery")
                            put("level_percent", batteryLevel)
                        }.toString()
                    )
                )
            }

            "system" -> {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val display = wm?.defaultDisplay
                val displayMetrics = android.util.DisplayMetrics().also { display?.getRealMetrics(it) }

                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = cm?.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val hasMobile = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

                val storage = Environment.getDataDirectory()
                val stat = StatFs(storage.path)
                val totalBytes = stat.totalBytes
                val freeBytes = stat.freeBytes

                val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("source", "system")
                            put("android", buildJsonObject {
                                put("version", Build.VERSION.RELEASE)
                                put("sdk", Build.VERSION.SDK_INT)
                                put("codename", Build.VERSION.CODENAME)
                            })
                            put("device", buildJsonObject {
                                put("manufacturer", Build.MANUFACTURER)
                                put("brand", Build.BRAND)
                                put("model", Build.MODEL)
                                put("product", Build.PRODUCT)
                                put("display", "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
                                put("density", displayMetrics.density)
                            })
                            put("input_method", buildJsonObject {
                                put("default_ime", defaultIme ?: "unknown")
                            })
                            put("shizuku", buildJsonObject {
                                put("alive", ShizukuController.isBinderAlive())
                                put("permission_granted", ShizukuController.isPermissionGranted())
                                put("version", ShizukuController.getVersion())
                                put("uid", ShizukuController.getUid())
                                put("has_privilege", ShizukuController.hasPrivilege())
                            })
                            put("network", buildJsonObject {
                                put("connected", isConnected)
                                put("wifi", hasWifi)
                                put("mobile", hasMobile)
                            })
                            put("storage", buildJsonObject {
                                put("total_bytes", totalBytes)
                                put("free_bytes", freeBytes)
                                put("total_gb", "%.1f".format(totalBytes / 1e9))
                                put("free_gb", "%.1f".format(freeBytes / 1e9))
                            })
                        }.toString()
                    )
                )
            }

            else -> error("Unknown source: $source")
        }
    },
)

private data class SmsInfo(
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
)

private fun readSms(context: Context, limit: Int): List<SmsInfo> {
    val cursor = context.contentResolver.query(
        Telephony.Sms.CONTENT_URI,
        arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        ),
        null, null,
        "${Telephony.Sms.DATE} DESC",
    ) ?: return emptyList()
    return runCatching {
        val list = mutableListOf<SmsInfo>()
        while (cursor.moveToNext() && list.size < limit) {
            list.add(
                SmsInfo(
                    address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).orEmpty(),
                    body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty(),
                    date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                    type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)),
                )
            )
        }
        list
    }.getOrElse { emptyList() }.also { cursor.close() }
}

private fun readBatteryLevel(context: Context): Int {
    val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return -1
    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
    return if (scale > 0) (level * 100f / scale).toInt() else -1
}
