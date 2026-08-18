package com.aiagents.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.DiffMetadata
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.ai.ui.toMetadata
import com.aiagents.data.ai.tools.local.WorkspacePathResolver
import com.aiagents.data.files.FilesManager
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.utils.generateUnifiedDiff
import com.aiagents.workspace.WorkspaceCommandResult
import com.aiagents.workspace.WorkspaceFileEntry
import com.aiagents.workspace.WorkspaceManager
import com.aiagents.workspace.WorkspaceStorageArea

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    filesManager: FilesManager? = null,
    context: Context? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return buildList {
        add(createReadFileTool(workspaceId, workspaceRepository, filesManager))
        add(createWriteFileTool(workspaceId, workspaceRepository))
        add(createEditFileTool(workspaceId, workspaceRepository))
        add(createGlobTool(workspaceId, workspaceRepository, cwd))
        add(createGrepTool(workspaceId, workspaceRepository, cwd))
        add(createShellTool(workspaceId, workspaceRepository, shellCwd))
        context?.let { ctx ->
            add(createImportToWorkspaceTool(ctx, workspaceId, workspaceRepository))
        }
    }
}

/**
 * import_to_workspace 工具: 把任意外部文件复制到工作区内。
 * `path` 与其它工具一致, 同时支持工作区路径(/workspace/...、/screenshots/...、/sd/...)
 * 与安卓目录路径(/storage/emulated/0/...、/sdcard/...、/data/... 等)。
 * 复制完成后返回工作区内的新路径, AI 即可用 workspace_read_file / cv_image / edit_image 继续处理。
 */
private fun createImportToWorkspaceTool(
    context: Context,
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
): Tool {
    val pathResolver = WorkspacePathResolver(context, workspaceRepository)
    return Tool(
        name = "import_to_workspace",
        description = """
            Copy a file from the Android directory (or anywhere the device can read) into the assistant's workspace.
            `path` accepts an Android path (/storage/emulated/0/..., /sdcard/..., /data/...) OR a workspace path (/workspace/..., /screenshots/..., /sd/...).
            The file keeps its name unless `name` is provided, and is placed in `destination` (default /workspace).
            Returns the new workspace path, which can then be used by workspace_read_file / cv_image / edit_image / image_analysis.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Source file: Android path (/storage/emulated/0/...) or workspace path (/workspace/..., /screenshots/..., /sd/...)")
                    })
                    put("destination", buildJsonObject {
                        put("type", "string")
                        put("description", "Destination directory inside the workspace (default /workspace), e.g. /workspace/images")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional new file name inside the workspace (default: keep the original name)")
                    })
                },
                required = listOf("path"),
            )
        },
        execute = {
            val params = it.jsonObject
            val source = params["path"]?.jsonPrimitive?.contentOrNull?.takeIf { p -> p.isNotBlank() }
                ?: error("path is required")
            val destination = params["destination"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { d -> d.isNotBlank() }
                ?.trim()
                ?.let { d -> if (d.startsWith("/")) d.trimEnd('/') else "/$d".trimEnd('/') }
                ?: "/workspace"
            val rename = params["name"]?.jsonPrimitive?.contentOrNull?.takeIf { n -> n.isNotBlank() }

            val devicePath = pathResolver.toDevicePath(source, needsShellRead = true)
                ?: error("找不到文件: $source")
            val sourceFile = File(devicePath)
            require(sourceFile.isFile) { "目标不是文件(目录无法直接导入): $source" }

            val fileName = rename ?: sourceFile.name
            val entry = workspaceRepository.importFile(
                id = workspaceId,
                area = WorkspaceStorageArea.FILES,
                destinationPath = destination,
                fileName = fileName,
                inputStream = sourceFile.inputStream(),
            )
            val workspacePath = "$destination/${entry.name}"
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", workspacePath)
                        put("source", source)
                        put("name", entry.name)
                        put("destination", destination)
                        put("sizeBytes", entry.sizeBytes)
                        put("description", "File imported to workspace")
                    }.toString()
                )
            )
        },
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    filesManager: FilesManager? = null,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a TEXT file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Only supports UTF-8 text files. For image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico),
        do NOT use this tool — use image_analysis (cloud vision model) or cv_image (local OpenCV) instead.
        Calling this tool on an image will NOT read its content.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("error", "workspace_read_file only reads text files and cannot read image content. Use image_analysis or cv_image to analyze this image.")
                    }.toString()
                )
            )
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createGlobTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
) = Tool(
    name = "workspace_glob",
    description = """
        Find files in the assistant's bound workspace files area using a glob pattern.
        Paths are relative to the workspace files root (/workspace). Examples:
        - `**/*.kt` finds all Kotlin files recursively
        - `src/**` finds everything under src
        - `*.md` finds markdown files in the root only
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern to match file paths against")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Base directory relative to the workspace files root. Defaults to root.")
                })
            },
            required = listOf("pattern"),
        )
    },
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern") ?: error("pattern is required")
        val path = params.string("path").orEmpty()
        val entries = workspaceRepository.glob(workspaceId, pattern, path, cwd)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("matches", buildJsonArray {
                        entries.forEach { entry -> add(entry.toJson()) }
                    })
                }.toString()
            )
        )
    },
)

private fun createGrepTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
) = Tool(
    name = "workspace_grep",
    description = """
        Search file contents in the assistant's bound workspace files area for a query.
        Paths are relative to the workspace files root (/workspace). Returns matching lines
        with file path and line number. Use `regex` for regular expression search and
        `include` to restrict to a glob (e.g. `*.kt`).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Text or regex to search for")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Base directory relative to the workspace files root. Defaults to root.")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Treat query as a regular expression. Defaults to false.")
                })
                put("ignoreCase", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Case-insensitive search. Defaults to true.")
                })
                put("include", buildJsonObject {
                    put("type", "string")
                    put("description", "Only search files matching this glob (e.g. `*.kt`). Defaults to all files.")
                })
            },
            required = listOf("query"),
        )
    },
    execute = {
        val params = it.jsonObject
        val query = params.string("query") ?: error("query is required")
        val path = params.string("path").orEmpty()
        val regex = params["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val ignoreCase = params["ignoreCase"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val include = params["include"]?.jsonPrimitive?.contentOrNull
        val matches = workspaceRepository.grep(workspaceId, query, path, regex, ignoreCase, include, cwd)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("query", query)
                    put("matches", buildJsonArray {
                        matches.forEach { match ->
                            add(buildJsonObject {
                                put("path", match.path)
                                put("line", match.line)
                                put("text", match.text)
                            })
                        }
                    })
                }.toString()
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready. ")
        append("If a command is missing (command not found), the tool automatically installs it via apt (fast mirror auto-selected), then retries.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeShellWithAutoInstall(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private const val AUTO_INSTALL_TIMEOUT_MS = 600_000L
private val COMMAND_NOT_FOUND = Regex("""([A-Za-z0-9._+-]+): command not found""", RegexOption.IGNORE_CASE)

private fun WorkspaceCommandResult.missingCommand(): String? {
    if (exitCode != 127) return null
    val text = stderr + "\n" + stdout
    val name = COMMAND_NOT_FOUND.find(text)?.groupValues?.get(1) ?: return null
    if ('/' in name || name.startsWith("-")) return null
    return name
}

/** 常见命令名 → 包名映射; 其余默认同名(绝大多数场景成立)。 */
private fun packageForCommand(name: String): String = when (name) {
    "ffmpeg", "ffprobe" -> "ffmpeg"
    "ping" -> "iputils-ping"
    "ifconfig", "netstat" -> "net-tools"
    "node" -> "nodejs"
    "pip", "pip3" -> "python3-pip"
    "docker" -> "docker.io"
    "java" -> "default-jre-headless"
    "python" -> "python-is-python3"
    "nano" -> "nano"
    else -> name
}

/**
 * 执行 shell 命令; 若命中 "command not found"(exit 127) 且能解析出命令名,
 * 自动用 apt(动态选国内/国外最快源)安装对应包后重试一次, 并把安装动作告知调用方。
 */
private suspend fun WorkspaceRepository.executeShellWithAutoInstall(
    workspaceId: String,
    command: String,
    cwd: String,
    timeoutMillis: Long,
): WorkspaceCommandResult {
    var result = executeCommand(workspaceId, command, cwd, timeoutMillis)
    val missing = result.missingCommand() ?: return result
    val pkg = packageForCommand(missing)
    val install = runCatching {
        executeCommand(workspaceId, buildAptInstallScript(pkg), timeoutMillis = AUTO_INSTALL_TIMEOUT_MS)
    }.getOrNull()
    return if (install?.exitCode == 0) {
        val retried = executeCommand(workspaceId, command, cwd, timeoutMillis)
        retried.copy(
            stdout = "[auto-install] installed '$pkg' (missing command '$missing'), re-ran command.\n" + retried.stdout,
        )
    } else {
        result.copy(
            stderr = result.stderr + "\n[auto-install] failed to install '$pkg' for missing command '$missing': " +
                (install?.stderr?.ifBlank { install?.stdout } ?: "installer unavailable"),
        )
    }
}

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
