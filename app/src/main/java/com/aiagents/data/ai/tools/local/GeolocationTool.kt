package com.aiagents.data.ai.tools.local

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * GPS 定位工具 — 获取设备真实 GPS 坐标。
 *
 * 返回纬度、经度。地名由 AI 通过 Nominatim API 反向地理编码获取。
 */
internal fun buildGeolocationTool(context: Context): Tool = Tool(
    name = "get_geolocation",
    description = """
        Get the device's GPS coordinates (latitude, longitude).
        To get the place name, call Nominatim API via shell:
        curl -s "https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lon}&format=json&accept-language=zh-CN"
        Then read the display_name or address fields from the JSON response.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = {
        val result = getLocation(context)
        listOf(UIMessagePart.Text(result))
    }
)

@SuppressLint("MissingPermission")
private suspend fun getLocation(context: Context): String = suspendCoroutine { cont ->
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // 优先 GPS，其次 network
    val provider = when {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> {
            cont.resume("ERROR: No location provider available. Enable GPS or network location.")
            return@suspendCoroutine
        }
    }

    // 先尝试获取最近一次位置
    val lastKnown = lm.getLastKnownLocation(provider)
    if (lastKnown != null && (System.currentTimeMillis() - lastKnown.time) < 60_000) {
        cont.resume(formatCoords(lastKnown))
        return@suspendCoroutine
    }

    // 否则请求新位置 (单次)
    var resumed = false
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!resumed) {
                resumed = true
                lm.removeUpdates(this)
                cont.resume(formatCoords(location))
            }
        }
        @Deprecated("Deprecated in API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (!resumed) {
                resumed = true
                cont.resume("ERROR: Location provider disabled.")
            }
        }
    }

    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())

    // 超时 10 秒
    android.os.Handler(Looper.getMainLooper()).postDelayed({
        if (!resumed) {
            resumed = true
            lm.removeUpdates(listener)
            cont.resume("ERROR: Location request timed out after 10s.")
        }
    }, 10_000)
}

private fun formatCoords(loc: Location): String {
    val lat = String.format("%.6f", loc.latitude)
    val lon = String.format("%.6f", loc.longitude)
    return "latitude: $lat, longitude: $lon"
}
