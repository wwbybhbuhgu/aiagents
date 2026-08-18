package com.aiagents.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 打开 Activity 工具
 *
 * 比"打开应用"更上一层楼:
 * - `query`: 列出某应用/全部应用中**无需 root 即可启动的公开(exported)Activity**,
 *   以及它们注册的 deep link(intent filter)与可传参数信息。
 * - `open`: 直接启动指定 Activity, 支持传 data URI 与 intent extra 参数
 *   (例如播放音乐可传作品/作者关键字、打开网页可传 URL、打开地图可传坐标等)。
 *
 * 公开 Activity = 其他应用无需 root/权限即可 startActivity 的目标, 即 ActivityInfo.exported == true。
 * 注意: 查询可传参数依赖 intent filter(action/data/mime)与常见 extra 约定,
 * 自定义 extra 无法静态枚举, 由 AI 依据常识/文档填写。
 */
internal fun buildActivityTool(context: Context): Tool = Tool(
    name = "activity",
    description = """
        Open specific ACTIVITIES directly (one level above launching an app).
        Two sub-actions:

        action=query
        - List PUBLIC (exported) activities that can be launched without root, for a given package
          (or search across all installed apps by keyword).
        - For each activity it reports: the fully-qualified component name, whether it's exported,
          its launchMode, and any registered deep-link intent filters (action/data/mimeType).
        - Use this to DISCOVER what deep activities exist and what parameters (deep link data) they accept.
        - Params: package (optional, e.g. com.tencent.qqmusic) OR keyword (optional) to search across apps.

        action=open
        - Launch a specific activity directly by its component name (from query).
        - Params: component (required, "package/ActivityName", e.g. "com.tencent.qqmusic/com.tencent.qqmusic.activity.PlayerActivity"),
          action (optional intent action), data (optional URI or deep link string, e.g. a media key/track URI),
          type (optional MIME type), extras (optional JSON object of string/number/boolean extra values,
          e.g. {"artist":"周杰伦","title":"青花瓷"} to hint a music player).
        - The activity must be exported (public) for this to work without root.

        Examples:
        - Discover music-player activities: activity query {"package":"com.tencent.qqmusic"}
        - Find any exported activity matching "player": activity query {"keyword":"player"}
        - Open a specific settings page: activity open {"component":"com.android.settings/com.android.settings.Settings"}
        - Open a music player with a track hint: activity open {"component":"<pkg>/<PlayerActivity>","extras":{"artist":"周杰伦","title":"青花瓷"}}
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "query | open")
                    put("enum", buildJsonArray {
                        add("query")
                        add("open")
                    })
                })
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package name to query activities from (action=query).")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Keyword to search exported activities/apps (action=query, when package not given).")
                })
                put("component", buildJsonObject {
                    put("type", "string")
                    put("description", "Component to open, format \"package/ActivityClass\" (action=open).")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional intent action for action=open, e.g. android.intent.action.VIEW.")
                })
                put("data", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional URI / deep link for action=open, e.g. a media track key URI.")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional MIME type for action=open.")
                })
                put("extras", buildJsonObject {
                    put("type", "object")
                    put("description", "Optional JSON object of string/number/boolean extra values to pass to the activity, e.g. {\"artist\":\"周杰伦\",\"title\":\"青花瓷\"}.")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { true },
    execute = {
        val args = it.jsonObject
        val action = args["action"]?.jsonPrimitive?.contentOrNull
        when (action) {
            "query" -> listOf(queryActivities(context, args))
            "open" -> listOf(openActivity(context, args))
            else -> listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "BAD_PARAMS")
                        put("message", "action must be query or open")
                    }.toString()
                )
            )
        }
    },
)

private fun queryActivities(context: Context, args: JsonObject): UIMessagePart.Text {
    val pm = context.packageManager
    val pkg = args["package"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val results = buildJsonArray {
        if (pkg != null) {
            // 查询指定包名的全部公开 Activity + 深链 intent filter
            val info = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_INTENT_FILTERS or PackageManager.GET_META_DATA)
            }.getOrNull() ?: runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_INTENT_FILTERS)
            }.getOrNull()
            if (info == null) {
                add(buildJsonObject {
                    put("error", "PACKAGE_NOT_FOUND")
                    put("package", pkg)
                })
            } else {
                info.activities?.filter { it.exported }?.forEach { a ->
                    val filters = intentFiltersOf(pm, info.packageName, a.name)
                    add(activityJson(info.packageName, a.name, a.launchMode, filters))
                }
            }
        } else {
            // 跨应用搜索: 枚举已安装应用, 逐个收集公开 Activity(限制数量)
            var scanned = 0
            pm.getInstalledPackages(PackageManager.GET_ACTIVITIES or PackageManager.GET_INTENT_FILTERS)
                .forEach { pkgInfo ->
                    if (scanned >= 60) return@forEach
                    scanned++
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkgInfo.packageName, 0)).toString()
                    }.getOrElse { pkgInfo.packageName }
                    val matched = keyword == null ||
                        label.contains(keyword, ignoreCase = true) ||
                        pkgInfo.packageName.contains(keyword, ignoreCase = true)
                    if (!matched) return@forEach
                    pkgInfo.activities?.filter { it.exported }?.forEach { a ->
                        add(activityJson(pkgInfo.packageName, a.name, a.launchMode, emptyList()))
                    }
                }
        }
    }
    return UIMessagePart.Text(
        buildJsonObject {
            put("ok", true)
            put("count", results.size)
            put("activities", results)
        }.toString()
    )
}

private fun activityJson(
    pkg: String,
    name: String,
    launchMode: Int,
    filters: List<JsonObject>,
): JsonObject = buildJsonObject {
    put("component", "$pkg/$name")
    put("package", pkg)
    put("activity", name)
    put("launchMode", launchModeName(launchMode))
    put("filters", buildJsonArray {
        filters.forEach { add(it) }
    })
}

private fun launchModeName(mode: Int): String = when (mode) {
    0 -> "standard"
    1 -> "singleTop"
    2 -> "singleTask"
    3 -> "singleInstance"
    else -> mode.toString()
}

/** 从 packageManager 读取某 Activity 注册的 intent filter(深链) */
private fun intentFiltersOf(
    pm: PackageManager,
    pkg: String,
    activityName: String,
): List<JsonObject> {
    val filters = mutableListOf<JsonObject>()
    runCatching {
        val intent = Intent().setClassName(pkg, activityName)
        pm.queryIntentActivities(intent, PackageManager.GET_INTENT_FILTERS)
            .filter { it.activityInfo?.name == activityName }
            .forEach { ri ->
                val filter = ri.filter ?: return@forEach
                val actions = buildJsonArray {
                    for (i in 0 until filter.countActions()) add(filter.getAction(i))
                }
                val types = buildJsonArray {
                    for (i in 0 until filter.countDataTypes()) add(filter.getDataType(i))
                }
                filters.add(buildJsonObject {
                    put("actions", actions)
                    put("mimeTypes", types)
                })
            }
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).setPackage(pkg)
        pm.queryIntentActivities(intent, PackageManager.GET_INTENT_FILTERS)
            .filter { it.activityInfo?.name == activityName }
            .forEach { ri ->
                val filter = ri.filter ?: return@forEach
                val schemes = buildJsonArray {
                    for (i in 0 until filter.countDataSchemes()) add(filter.getDataScheme(i))
                }
                val authorities = buildJsonArray {
                    for (i in 0 until filter.countDataAuthorities()) {
                        add(filter.getDataAuthority(i).host)
                    }
                }
                filters.add(buildJsonObject {
                    put("schemes", schemes)
                    put("authorities", authorities)
                })
            }
    }
    return filters.distinct()
}

private fun openActivity(context: Context, args: JsonObject): UIMessagePart.Text {
    val component = args["component"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    if (component.isNullOrBlank()) {
        return UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"component is required"}""")
    }
    val slash = component.indexOf('/')
    if (slash <= 0) {
        return UIMessagePart.Text(
            buildJsonObject {
                put("error", "BAD_PARAMS")
                put("message", "component must be \"package/ActivityClass\"")
            }.toString()
        )
    }
    val pkg = component.substring(0, slash)
    val activity = component.substring(slash + 1)
    val intent = Intent().setClassName(pkg, activity)
    args["action"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { intent.action = it }
    args["data"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { intent.data = Uri.parse(it) }
    args["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { intent.type = it }
    // extras
    args["extras"]?.let { extra ->
        runCatching {
            val obj = extra.jsonObject
            obj.forEach { (k, v) ->
                val prim = v.jsonPrimitive
                when {
                    prim.booleanOrNull != null -> intent.putExtra(k, prim.booleanOrNull!!)
                    prim.longOrNull != null -> intent.putExtra(k, prim.longOrNull!!)
                    else -> intent.putExtra(k, prim.contentOrNull ?: "")
                }
            }
        }
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val result = runCatching {
        val pm = context.packageManager
        val resolve = pm.resolveActivity(intent, 0)
        if (resolve == null) {
            "NO_HANDLER"
        } else {
            context.startActivity(intent)
            "OK"
        }
    }.getOrElse { e ->
        if (e is android.content.ActivityNotFoundException) "NO_HANDLER" else "FAIL:${e.message}"
    }
    return UIMessagePart.Text(
        buildJsonObject {
            put("ok", result == "OK")
            put("component", component)
            put("result", result)
        }.toString()
    )
}
