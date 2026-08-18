package com.aiagents.data.ai.tools

import android.content.Context
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.ai.tools.local.WorkspacePathResolver
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.workspace.WorkspaceStorageArea
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

private const val MEDIA_TIMEOUT_MS = 600_000L

/**
 * 工作区容器内用 apt 安装软件包的脚本(MediaTool/向导/AI 工具自动安装共用)。
 * 动态测速: 先测国内镜像, 再测官方源, 取最快可达者; 国内/海外用户均可用。
 * 首次会清理历史遗留的错误 apt 源并写入选中的镜像源; 之后复用该源, 幂等可重复执行。
 */
internal fun buildAptInstallScript(
    packages: String,
    verifyCommand: String? = null,
): String = """
    set -e
    mkdir -p /tmp /var/tmp /etc/apt/sources.list.d
    export DEBIAN_FRONTEND=noninteractive
    if [ ! -f /etc/apt/sources.list.d/aiagents-mirror.list ]; then
      . /etc/os-release 2>/dev/null || true
      suite=${'$'}{VERSION_CODENAME:-noble}
      arch=${'$'}(dpkg --print-architecture 2>/dev/null || uname -m)
      case "${'$'}arch" in
        arm64|aarch64) ports=ubuntu-ports; official=http://ports.ubuntu.com/ubuntu-ports/ ;;
        amd64|x86_64)  ports=ubuntu;       official=http://archive.ubuntu.com/ubuntu/ ;;
        *)             ports=ubuntu-ports; official=http://ports.ubuntu.com/ubuntu-ports/ ;;
      esac
      cands="http://mirrors.aliyun.com/${'$'}ports/ http://mirrors.tuna.tsinghua.edu.cn/${'$'}ports/ http://mirrors.ustc.edu.cn/${'$'}ports/ http://mirrors.cloud.tencent.com/${'$'}ports/ http://repo.huaweicloud.com/${'$'}ports/ ${'$'}official"
      best=""
      best_ms=99999
      for b in ${'$'}cands; do
        host=${'$'}{b#http://}; host=${'$'}{host%%/*}
        ms=${'$'}(timeout 6 /bin/bash -c '
          h="${'$'}1"; p="${'$'}2"
          s=${'$'}(date +%s%N)
          if exec 3<>/dev/tcp/${'$'}h/80 2>/dev/null; then
            printf "HEAD %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" "${'$'}p" "${'$'}h" >&3
            if read -r _ code _ <&3; then
              case "${'$'}code" in 2*) e=${'$'}(date +%s%N); echo ${'$'}(((e-s)/1000000)) ;; *) echo 99999 ;; esac
            else echo 99999; fi
          else echo 99999; fi
        ' _ "${'$'}host" "/dists/${'$'}suite/InRelease" 2>/dev/null) || ms=99999
        ms=${'$'}{ms:-99999}
        if [ "${'$'}ms" -lt "${'$'}best_ms" ]; then best="${'$'}b"; best_ms="${'$'}ms"; fi
      done
      [ -n "${'$'}best" ] || best="${'$'}official"
      rm -f /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
      for f in /etc/apt/sources.list.d/*.sources; do [ -f "${'$'}f" ] && rm -f "${'$'}f"; done 2>/dev/null || true
      printf 'deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] %s %s main restricted universe multiverse\n' "${'$'}best" "${'$'}suite" > /etc/apt/sources.list.d/aiagents-mirror.list
    fi
    apt-get update -qq 2>&1 | tail -5
    apt-get install -y -qq $packages 2>&1 | tail -30
    ${verifyCommand ?: "true"}
""".trimIndent()

internal val FFMPEG_INSTALL_SCRIPT = buildAptInstallScript(
    packages = "ffmpeg",
    verifyCommand = "command -v ffmpeg && command -v ffprobe",
)

/**
 * media_tool: 基于 ffmpeg 的音视频处理工具。
 * ffmpeg 运行在助手的工作区容器内(无需打包原生库), 首次使用会自动安装。
 * `input`/`inputs` 同时支持工作区路径(/workspace/...、/screenshots/...、/sd/...)
 * 与安卓目录路径(/storage/emulated/0/...、/sdcard/...); 安卓路径会被先复制进工作区再处理。
 * 输出文件保存到 /workspace/media/, 返回工作区路径与设备路径。
 */
fun buildMediaTool(
    context: Context,
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
): Tool {
    val pathResolver = WorkspacePathResolver(context, workspaceRepository)
    return Tool(
        name = "media_tool",
        description = """
            Audio/video processing toolkit powered by ffmpeg, run inside the assistant's workspace container.
            `input` / `inputs` accept workspace paths (/workspace/..., /screenshots/..., /sd/...) or Android paths (/storage/emulated/0/...); external files are copied into the workspace first.
            Operations:
            - info: show duration, resolution, codecs of a video/audio.
            - trim: cut a segment (start, duration in seconds).
            - concat: merge multiple videos into one (inputs, same codecs recommended).
            - frames: extract frames as JPGs (fps, optional start/duration).
            - image_to_video: turn a list of images (inputs) into a video slideshow (fps).
            - speed: change playback speed (factor, e.g. 2.0 = double speed).
            - audio: extract audio track (codec aac|mp3).
            - convert: convert format by output extension.
            - rotate: rotate by degrees (90/180/270).
            - watermark: overlay an image watermark (watermark path, x, y, scale).
            - filters: apply an arbitrary ffmpeg -vf filter string.
            - command: run an arbitrary ffmpeg command (raw args after "ffmpeg").
            Output is saved to /workspace/media by default (or `output`), and the workspace path + device path are returned.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("operation", buildJsonObject {
                        put("type", "string")
                        put("description", "Operation: info, trim, concat, frames, image_to_video, speed, audio, convert, rotate, watermark, filters, command")
                    })
                    put("input", buildJsonObject {
                        put("type", "string")
                        put("description", "Primary input: workspace path or Android path")
                    })
                    put("inputs", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Input list (concat / image_to_video)")
                    })
                    put("output", buildJsonObject {
                        put("type", "string")
                        put("description", "Output path inside workspace (default /workspace/media/<auto>). Use a .mp4/.jpg/.m4a/.mp3 extension to set format.")
                    })
                    put("start", buildJsonObject { put("type", "number"); put("description", "Start time in seconds (trim/frames)") })
                    put("duration", buildJsonObject { put("type", "number"); put("description", "Duration in seconds (trim/frames)") })
                    put("fps", buildJsonObject { put("type", "number"); put("description", "Frames per second (frames/image_to_video, default 1)") })
                    put("factor", buildJsonObject { put("type", "number"); put("description", "Speed factor (speed)") })
                    put("degrees", buildJsonObject { put("type", "number"); put("description", "Rotation degrees (rotate)") })
                    put("watermark", buildJsonObject {
                        put("type", "string")
                        put("description", "Watermark image path (watermark)")
                    })
                    put("x", buildJsonObject { put("type", "integer"); put("description", "Watermark X (watermark)") })
                    put("y", buildJsonObject { put("type", "integer"); put("description", "Watermark Y (watermark)") })
                    put("scale", buildJsonObject { put("type", "number"); put("description", "Watermark scale (watermark, default 1.0)") })
                    put("filters", buildJsonObject { put("type", "string"); put("description", "Raw ffmpeg -vf filter string (filters)") })
                    put("codec", buildJsonObject { put("type", "string"); put("description", "Audio codec aac|mp3 (audio, default aac)") })
                    put("command", buildJsonObject { put("type", "string"); put("description", "Raw ffmpeg arguments after 'ffmpeg' (command)") })
                },
                required = listOf("operation"),
            )
        },
        needsApproval = { false },
        execute = { payload ->
            val id = workspaceId ?: error("media_tool 需要已就绪的工作区(请先创建并初始化工作区)")
            val params = payload.jsonObject
            ensureFfmpeg(id, workspaceRepository)
            val operation = params["operation"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("operation is required")
            val result: JsonElement = when (operation) {
                "info" -> runInfo(id, workspaceRepository, pathResolver, params)
                "trim" -> runTrim(id, workspaceRepository, pathResolver, params)
                "concat" -> runConcat(id, workspaceRepository, pathResolver, params)
                "frames" -> runFrames(id, workspaceRepository, pathResolver, params)
                "image_to_video" -> runImageToVideo(id, workspaceRepository, pathResolver, params)
                "speed" -> runSpeed(id, workspaceRepository, pathResolver, params)
                "audio" -> runExtractAudio(id, workspaceRepository, pathResolver, params)
                "convert" -> runConvert(id, workspaceRepository, pathResolver, params)
                "rotate" -> runRotate(id, workspaceRepository, pathResolver, params)
                "watermark" -> runWatermark(id, workspaceRepository, pathResolver, params)
                "filters" -> runFilters(id, workspaceRepository, pathResolver, params)
                "command" -> runRawCommand(id, workspaceRepository, params)
                else -> error("未知 operation: $operation")
            }
            listOf(UIMessagePart.Text(result.toString()))
        },
    )
}

private fun paramsStr(params: kotlinx.serialization.json.JsonObject, key: String): String? =
    params[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun paramsNum(params: kotlinx.serialization.json.JsonObject, key: String): Double? =
    params[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

private fun paramsInt(params: kotlinx.serialization.json.JsonObject, key: String): Int? =
    params[key]?.jsonPrimitive?.intOrNull

private fun paramsList(params: kotlinx.serialization.json.JsonObject, key: String): List<String> =
    params[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
        ?: emptyList()

/** shell 单引号转义, 供 bash -c eval 使用 */
private fun q(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

private suspend fun WorkspaceRepository.run(id: String, command: String, timeout: Long = MEDIA_TIMEOUT_MS): String {
    val result = executeCommand(id, command, timeoutMillis = timeout)
    if (result.exitCode != 0) {
        error("命令失败(exit=${result.exitCode}):\n${result.stderr.ifBlank { result.stdout }}")
    }
    return result.stdout
}

/** 把输入路径解析为容器内可访问的 /workspace 路径; 安卓路径自动复制进工作区 */
private suspend fun containerInputPath(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    path: String,
): String {
    val p = path.trim()
    if (p.startsWith("/workspace")) {
        return p.trimEnd('/')
    }
    val device = resolver.toDevicePath(p) ?: error("找不到文件: $p")
    val src = File(device)
    require(src.isFile) { "输入不是文件: $p" }
    val dir = "/workspace/media/imports"
    repository.run(id, "mkdir -p $dir")
    val entry = repository.importFile(
        id = id,
        area = WorkspaceStorageArea.FILES,
        destinationPath = dir,
        fileName = "${System.currentTimeMillis()}_${src.name}",
        inputStream = src.inputStream(),
    )
    return "$dir/${entry.name}"
}

private suspend fun resolveOutput(
    id: String,
    repository: WorkspaceRepository,
    output: String?,
    defaultName: String,
): String {
    repository.run(id, "mkdir -p /workspace/media")
    val out = output?.trim()?.takeIf { it.isNotBlank() }
    val workspacePath = if (out != null) {
        if (out.startsWith("/workspace")) out.trimEnd('/') else "/workspace/media/${out.trimStart('/')}"
    } else {
        "/workspace/media/$defaultName"
    }
    repository.run(id, "mkdir -p ${q(workspacePath.substringBeforeLast('/', "").ifBlank { "/workspace/media" })}")
    return workspacePath
}

private suspend fun resultJson(
    id: String,
    repository: WorkspaceRepository,
    workspacePath: String,
    extra: kotlinx.serialization.json.JsonObject = buildJsonObject { },
): kotlinx.serialization.json.JsonObject {
    val host = runCatching { repository.resolveRootfsHostFile(id, workspacePath) }.getOrNull()
    return buildJsonObject {
        put("output_workspace_path", workspacePath)
        put("output_device_path", host?.absolutePath)
        put("sizeBytes", host?.length())
        extra.forEach { (k, v) -> put(k, v) }
    }
}

private suspend fun runInfo(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("info 需要 input")
    val containerIn = containerInputPath(id, repository, resolver, input)
    val extra = buildJsonObject {
        put("input", input)
        put("container_path", containerIn)
    }
    return probeMedia(repository, id, containerIn, extra)
}

/** 尽可能拿到媒体元数据: 优先 ffprobe(-of 优先), 失败时回退 ffmpeg -i 的 stderr 解析。 */
private suspend fun probeMedia(
    repository: WorkspaceRepository,
    id: String,
    containerPath: String,
    extra: kotlinx.serialization.json.JsonObject = buildJsonObject { },
): JsonElement {
    val quoted = q(containerPath)

    // 1) 尝试 ffprobe: -of 是 -print_format 的等价别名, 兼容性更好
    for (probeArgs in listOf(
        "-v error -of json -show_format -show_streams",
        "-print_format json -v error -show_format -show_streams",
        "-of json -show_format -show_streams",
    )) {
        val res = repository.executeCommand(id, "ffprobe $probeArgs $quoted", timeoutMillis = 60_000)
        val out = res.stdout.trim()
        val looksValid = out.startsWith("{") &&
            (out.contains("\"format\"") || out.contains("\"streams\""))
        if (res.exitCode == 0 && looksValid) {
            return buildJsonObject {
                extra.forEach { (k, v) -> put(k, v) }
                put("probe_json", out)
            }
        }
    }

    // 2) 回退: 用 ffmpeg -i 解析 stderr (-i 信息写到 stderr)
    val f = repository.executeCommand(id, "ffmpeg -hide_banner -i $quoted 2>&1 || true", timeoutMillis = 60_000)
    val info = f.stdout + f.stderr
    val duration = Regex("Duration:\\s*([0-9:.]+)").find(info)?.groupValues?.get(1)
    val streams = Regex("Stream #0:[^\\n]*").findAll(info)
        .map { it.value.trim() }
        .toList()
    return buildJsonObject {
        extra.forEach { (k, v) -> put(k, v) }
        if (duration != null) put("duration", duration)
        put(
            "streams",
            buildJsonArray {
                streams.forEach { add(JsonPrimitive(it)) }
            }
        )
        if (info.isNotBlank()) put("ffmpeg_info", info.take(4000))
    }
}

private suspend fun runTrim(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("trim 需要 input")
    val containerIn = containerInputPath(id, repository, resolver, input)
    val ext = paramsStr(params, "output")?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 } ?: "mp4"
    val out = resolveOutput(id, repository, paramsStr(params, "output"), "trim_${System.currentTimeMillis()}.$ext")
    val start = paramsNum(params, "start") ?: 0.0
    val duration = paramsNum(params, "duration")
    val cmd = buildString {
        append("ffmpeg -y -hide_banner -loglevel error -ss ${fmt(start)} -i ${q(containerIn)}")
        if (duration != null) append(" -t ${fmt(duration)}")
        if (ext == "mp4") append(" -c:v libx264 -preset veryfast -c:a aac -movflags +faststart")
        append(" ${q(out)}")
    }
    repository.run(id, cmd)
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "trim")
        put("start", start)
        put("duration", duration)
    })
}

private suspend fun runConcat(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val inputs = paramsList(params, "inputs").ifEmpty {
        paramsStr(params, "input")?.let { listOf(it) } ?: error("concat 需要 inputs 列表")
    }
    require(inputs.size >= 2) { "concat 至少需要两个输入" }
    val containerIns = inputs.map { containerInputPath(id, repository, resolver, it) }
    val out = resolveOutput(id, repository, paramsStr(params, "output"), "concat_${System.currentTimeMillis()}.mp4")
    val list = containerIns.joinToString("\n") { "file ${q(it)}" }
    val result = repository.executeCommand(
        id,
        "ffmpeg -y -hide_banner -loglevel error -f concat -safe 0 -i - -c copy ${q(out)}",
        timeoutMillis = MEDIA_TIMEOUT_MS,
        stdin = list.toByteArray(),
    )
    if (result.exitCode != 0) {
        error("concat 失败(exit=${result.exitCode}, 通常因编码/参数不一致):\n${result.stderr.ifBlank { result.stdout }}")
    }
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "concat")
        put("count", inputs.size)
    })
}

private suspend fun runFrames(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("frames 需要 input")
    val containerIn = containerInputPath(id, repository, resolver, input)
    val fps = paramsNum(params, "fps") ?: 1.0
    val outDir = "/workspace/media/frames_${System.currentTimeMillis()}"
    val cmd = buildString {
        append("ffmpeg -y -hide_banner -loglevel error")
        paramsNum(params, "start")?.let { append(" -ss ${fmt(it)}") }
        append(" -i ${q(containerIn)}")
        paramsNum(params, "duration")?.let { append(" -t ${fmt(it)}") }
        append(" -vf fps=${fmt(fps)} ${q("$outDir/frame_%04d.jpg")}")
    }
    repository.run(id, cmd)
    val count = repository.run(id, "ls $outDir | wc -l").trim().toIntOrNull() ?: 0
    return resultJson(id, repository, outDir, buildJsonObject {
        put("operation", "frames")
        put("frame_count", count)
        put("fps", fps)
        put("frames_dir", outDir)
        put("frame_glob", "$outDir/frame_*.jpg")
    })
}

private suspend fun runImageToVideo(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val images = paramsList(params, "inputs").ifEmpty {
        paramsStr(params, "input")?.let { listOf(it) } ?: error("image_to_video 需要 inputs 图片列表")
    }
    require(images.isNotEmpty()) { "image_to_video 至少需要一张图片" }
    val fps = paramsNum(params, "fps") ?: 1.0
    val out = resolveOutput(id, repository, paramsStr(params, "output"), "slideshow_${System.currentTimeMillis()}.mp4")
    val dir = "/workspace/media/slides_${System.currentTimeMillis()}"
    repository.run(id, "mkdir -p $dir")
    val ext = images.first().substringAfterLast('.', "jpg").lowercase()
    images.forEachIndexed { idx, imagePath ->
        val containerIn = containerInputPath(id, repository, resolver, imagePath)
        repository.run(id, "cp ${q(containerIn)} $dir/img_${idx.toString().padStart(4, '0')}.$ext")
    }
    repository.run(id, "ffmpeg -y -hide_banner -loglevel error -framerate ${fmt(fps)} -i $dir/img_%04d.$ext -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" -c:v libx264 -preset veryfast -pix_fmt yuv420p ${q(out)}")
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "image_to_video")
        put("image_count", images.size)
        put("fps", fps)
    })
}

private suspend fun runSpeed(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("speed 需要 input")
    val factor = paramsNum(params, "factor") ?: error("speed 需要 factor")
    require(factor in 0.05..100.0) { "factor 超出范围" }
    val containerIn = containerInputPath(id, repository, resolver, input)
    val out = resolveOutput(id, repository, paramsStr(params, "output"), "speed_${System.currentTimeMillis()}.mp4")
    val videoFilter = "setpts=${fmt(1.0 / factor)}*PTS"
    val audioFilter = buildString {
        var remaining = factor
        append("atempo=")
        if (remaining >= 0.5 && remaining <= 2.0) {
            append(fmt(remaining))
        } else {
            val halves = if (remaining < 0.5) 2 else 2
            val chunk = if (remaining < 0.5) {
                val c = 0.5
                remaining = (remaining / c).coerceIn(0.5, 2.0)
                c
            } else {
                val c = 2.0
                remaining = (remaining / c).coerceIn(0.5, 2.0)
                c
            }
            append(fmt(chunk))
            append(",atempo=").append(fmt(remaining.coerceIn(0.5, 2.0)))
        }
        // 兼容 extra halves: 简单起见仅支持一次拆分
    }
    repository.run(
        id,
        "ffmpeg -y -hide_banner -loglevel error -i ${q(containerIn)} " +
            "-vf \"$videoFilter\" -af \"$audioFilter\" -c:v libx264 -preset veryfast -c:a aac ${q(out)}"
    )
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "speed")
        put("factor", factor)
    })
}

private suspend fun runExtractAudio(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("audio 需要 input")
    val codec = paramsStr(params, "codec") ?: "aac"
    val ext = if (codec == "mp3") "mp3" else "m4a"
    val containerIn = containerInputPath(id, repository, resolver, input)
    val out = resolveOutput(id, repository, paramsStr(params, "output"), "audio_${System.currentTimeMillis()}.$ext")
    val audioCodec = if (codec == "mp3") "libmp3lame" else "aac"
    repository.run(id, "ffmpeg -y -hide_banner -loglevel error -i ${q(containerIn)} -vn -c:a $audioCodec ${q(out)}")
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "audio")
        put("codec", codec)
    })
}

private suspend fun runConvert(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("convert 需要 input")
    val output = paramsStr(params, "output") ?: error("convert 需要 output (带扩展名)")
    val containerIn = containerInputPath(id, repository, resolver, input)
    val out = resolveOutput(id, repository, output, "convert_${System.currentTimeMillis()}.mp4")
    val ext = out.substringAfterLast('.', "").lowercase()
    val extra = if (ext == "mp4") " -c:v libx264 -preset veryfast -c:a aac -movflags +faststart" else ""
    repository.run(id, "ffmpeg -y -hide_banner -loglevel error -i ${q(containerIn)}$extra ${q(out)}")
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "convert")
        put("input", input)
        put("format", ext)
    })
}

private suspend fun runRotate(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("rotate 需要 input")
    val degrees = paramsNum(params, "degrees") ?: 90.0
    val containerIn = containerInputPath(id, repository, resolver, input)
    val out = resolveOutput(id, repository, paramsStr(params, "output"), "rotate_${System.currentTimeMillis()}.mp4")
    repository.run(id, "ffmpeg -y -hide_banner -loglevel error -i ${q(containerIn)} -vf \"rotate=${fmt(degrees)}:fillcolor=black\" -c:a copy ${q(out)}")
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "rotate")
        put("degrees", degrees)
    })
}

private suspend fun runWatermark(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("watermark 需要 input")
    val watermark = paramsStr(params, "watermark") ?: error("watermark 需要 watermark 图片路径")
    val containerIn = containerInputPath(id, repository, resolver, input)
    val containerWm = containerInputPath(id, repository, resolver, watermark)
    val out = resolveOutput(id, repository, paramsStr(params, "output"), "watermark_${System.currentTimeMillis()}.mp4")
    val x = paramsInt(params, "x") ?: 10
    val y = paramsInt(params, "y") ?: 10
    val scale = paramsNum(params, "scale") ?: 1.0
    val scaleFilter = if (scale == 1.0) "[1:v]null[wm]" else "[1:v]scale=iw*${fmt(scale)}:ih*${fmt(scale)}[wm]"
    repository.run(
        id,
        "ffmpeg -y -hide_banner -loglevel error -i ${q(containerIn)} -i ${q(containerWm)} " +
            "-filter_complex \"$scaleFilter;[0:v][wm]overlay=$x:$y[outv]\" -map \"[outv]\" -map 0:a? -c:a copy ${q(out)}"
    )
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "watermark")
        put("x", x)
        put("y", y)
        put("scale", scale)
    })
}

private suspend fun runFilters(
    id: String,
    repository: WorkspaceRepository,
    resolver: WorkspacePathResolver,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val input = paramsStr(params, "input") ?: error("filters 需要 input")
    val filters = paramsStr(params, "filters") ?: error("filters 需要 filters 字符串")
    val containerIn = containerInputPath(id, repository, resolver, input)
    val out = resolveOutput(id, repository, paramsStr(params, "output"), "filtered_${System.currentTimeMillis()}.mp4")
    repository.run(id, "ffmpeg -y -hide_banner -loglevel error -i ${q(containerIn)} -vf \"$filters\" -c:a copy ${q(out)}")
    return resultJson(id, repository, out, buildJsonObject {
        put("operation", "filters")
        put("filters", filters)
    })
}

private suspend fun runRawCommand(
    id: String,
    repository: WorkspaceRepository,
    params: kotlinx.serialization.json.JsonObject,
): JsonElement {
    val command = paramsStr(params, "command") ?: error("command 需要 command 字符串(ffmpeg 之后的参数)")
    val output = paramsStr(params, "output")
    val outputWorkspace = output?.let { resolveOutput(id, repository, it, "out_${System.currentTimeMillis()}.mp4") }
    val cmd = "ffmpeg -y -hide_banner -loglevel error $command"
    val result = repository.executeCommand(id, cmd, timeoutMillis = MEDIA_TIMEOUT_MS)
    if (result.exitCode != 0) {
        error("ffmpeg 命令失败(exit=${result.exitCode}):\n${result.stderr.ifBlank { result.stdout }}")
    }
    return buildJsonObject {
        put("operation", "command")
        put("command", command)
        if (outputWorkspace != null) {
            val host = runCatching { repository.resolveRootfsHostFile(id, outputWorkspace) }.getOrNull()
            put("output_workspace_path", outputWorkspace)
            put("output_device_path", host?.absolutePath)
            put("sizeBytes", host?.length())
        }
        put("stdout", result.stdout)
    }
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

@Volatile
private var ffmpegReady = false
private val ffmpegReadyWorkspaces = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

private suspend fun ensureFfmpeg(id: String, repository: WorkspaceRepository) {
    if (ffmpegReadyWorkspaces.contains(id)) return
    val probe = repository.executeCommand(id, "command -v ffmpeg && command -v ffprobe", timeoutMillis = 30_000)
    if (probe.exitCode == 0) {
        ffmpegReadyWorkspaces.add(id)
        ffmpegReady = true
        return
    }
    val install = repository.executeCommand(
        id,
        FFMPEG_INSTALL_SCRIPT,
        timeoutMillis = MEDIA_TIMEOUT_MS,
    )
    if (install.exitCode != 0) {
        error("工作区内未安装 ffmpeg 且自动安装失败:\n${install.stderr.ifBlank { install.stdout }}")
    }
    ffmpegReadyWorkspaces.add(id)
    ffmpegReady = true
}
