package com.aiagents.data.ai.tools.local

import android.content.Context
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 反向地理编码工具 — 通过 Nominatim API 将 GPS 坐标转换为中文地址。
 */
internal fun buildReverseGeocodingTool(context: Context): Tool = Tool(
    name = "reverse_geocode",
    description = """
        Convert GPS coordinates to a Chinese address using Nominatim API.
        Input: latitude and longitude from get_geolocation.
        Output: display_name (中文地址), plus address details (country, state, city, etc).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("latitude", buildJsonObject {
                    put("type", "string")
                    put("description", "Latitude coordinate")
                })
                put("longitude", buildJsonObject {
                    put("type", "string")
                    put("description", "Longitude coordinate")
                })
            },
            required = listOf("latitude", "longitude")
        )
    },
    execute = { args ->
        val lat = args.jsonObject["latitude"]?.jsonPrimitive?.contentOrNull
        val lon = args.jsonObject["longitude"]?.jsonPrimitive?.contentOrNull

        if (lat.isNullOrBlank() || lon.isNullOrBlank()) {
            listOf(UIMessagePart.Text("ERROR: latitude and longitude are required"))
        } else {
            val result = reverseGeocode(context, lat, lon)
            listOf(UIMessagePart.Text(result))
        }
    }
)

private fun reverseGeocode(context: Context, lat: String, lon: String): String {
    return try {
        val urlStr = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&accept-language=zh-CN"
        val url = URL(urlStr)

        // 获取代理设置
        val proxyAddr: String? = try {
            val pm = org.koin.java.KoinJavaComponent.get<com.aiagents.data.proxy.ProxyManager>(
                com.aiagents.data.proxy.ProxyManager::class.java
            )
            pm.localProxyAddress
        } catch (_: Exception) { null }

        val conn = run {
            if (!proxyAddr.isNullOrBlank()) {
                val parts = proxyAddr.split(":")
                val host = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
        }

        conn.apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", "AiAgents/1.0")
            setRequestProperty("Accept", "application/json")
        }

        val code = conn.responseCode
        val body = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }

        if (code !in 200..299) {
            return "ERROR: HTTP $code - $body"
        }

        val json = org.json.JSONObject(body)
        val displayName = json.optString("display_name", "")
        val address = json.optJSONObject("address")

        buildString {
            if (displayName.isNotBlank()) {
                append("地址: $displayName\n")
            }
            address?.let {
                val country = it.optString("country", "")
                val state = it.optString("state", "")
                val city = it.optString("city", it.optString("town", it.optString("village", "")))
                val district = it.optString("district", it.optString("county", ""))
                val road = it.optString("road", "")

                val parts = listOfNotNull(
                    country.ifBlank { null },
                    state.ifBlank { null },
                    city.ifBlank { null },
                    district.ifBlank { null },
                    road.ifBlank { null },
                )
                if (parts.isNotEmpty()) {
                    append("详细: ${parts.joinToString(", ")}")
                }
            }
        }.ifBlank { "坐标: $lat, $lon (无法解析地址)" }
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }
}
