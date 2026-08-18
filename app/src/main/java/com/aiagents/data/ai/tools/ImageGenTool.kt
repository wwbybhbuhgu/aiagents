package com.aiagents.data.ai.tools

import android.content.Context
import java.util.Base64
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.provider.ImageGenerationParams
import com.aiagents.ai.provider.ProviderManager
import com.aiagents.ai.ui.ImageGenSize
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.datastore.Settings
import com.aiagents.data.datastore.findModelById
import com.aiagents.data.datastore.findProvider
import com.aiagents.data.files.FilesManager
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.workspace.WorkspaceStorageArea

/** 工作区专用图片目录（容器内路径 /workspace/images） */
private const val WORKSPACE_IMAGES_DIR = "images"

/**
 * 构建 image_generate 工具：用配置的图片生成模型出图，
 * 文件保存到工作区专用图片目录（/workspace/images），并回显到对话。
 */
fun buildImageGenTool(
    context: Context,
    settings: Settings,
    providerManager: ProviderManager,
    workspaceRepository: WorkspaceRepository,
    workspaceId: String?,
    filesManager: FilesManager,
): Tool {
    val sizeValues = ImageGenSize.entries.map { it.value }

    return Tool(
        name = "image_generate",
        description = """
            Generates images using the configured image generation model.
            The generated image files are saved to the workspace image folder (/workspace/images)
            and displayed in the conversation.
            Provide a detailed `prompt` describing the desired image.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Detailed description of the image to generate")
                    })
                    put("size", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            sizeValues.forEach { add(it) }
                        })
                        put("description", "Image size. Defaults to auto.")
                    })
                    put("numOfImages", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of images to generate (1-4). Defaults to 1.")
                    })
                },
                required = listOf("prompt"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: error("prompt is required")
            val size = params["size"]?.jsonPrimitive?.contentOrNull?.takeIf { it in sizeValues }
                ?: ImageGenSize.AUTO.value
            val numOfImages = params["numOfImages"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?.coerceIn(1, 4) ?: 1

            val model = settings.findModelById(settings.imageGenerationModelId)
                ?: error("未配置图片生成模型，请先在 设置-模型 中选择")
            val provider = model.findProvider(settings.providers)
                ?: error("Provider not found for model: ${model.displayName}")
            val providerSetting = settings.providers.find { it.id == provider.id }
                ?: error("Provider setting not found")

            val imageParams = ImageGenerationParams(
                model = model,
                prompt = prompt,
                numOfImages = numOfImages,
                size = size,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            )

            val parts = mutableListOf<UIMessagePart>()
            val savedPaths = mutableListOf<String>()
            providerManager.getProviderByType(provider)
                .generateImage(providerSetting, imageParams)
                .collect { item ->
                    if (!item.partial) {
                        val bytes = runCatching {
                            Base64.getDecoder().decode(item.data.trim())
                        }.getOrElse {
                            error("图片响应解码失败: ${it.message}")
                        }
                        val fileName = "img_${System.currentTimeMillis()}.png"
                        val workspacePath = saveImageToWorkspace(
                            workspaceRepository = workspaceRepository,
                            workspaceId = workspaceId,
                            fileName = fileName,
                            bytes = bytes,
                            filesManager = filesManager,
                            context = context,
                        )
                        savedPaths += workspacePath
                        // 图片已保存到工作区 /workspace/images, 只返回路径提示,
                        // 由 AI 用工具(workspace_read_file / image_analysis)自行读取处理
                        parts += UIMessagePart.Text(
                            buildJsonObject {
                                put("path", workspacePath)
                                put("description", "Image generated and saved to workspace")
                            }.toString()
                        )
                    }
                }

            if (savedPaths.isEmpty()) {
                error("图片生成失败：没有收到有效的图片数据")
            }

            parts += UIMessagePart.Text(
                buildJsonObject {
                    put("paths", buildJsonArray {
                        savedPaths.forEach { add(it) }
                    })
                }.toString()
            )
            parts
        },
    )
}

/** 保存到工作区图片目录，返回容器内绝对路径；无工作区时回落到应用内部图片目录 */
private suspend fun saveImageToWorkspace(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String?,
    fileName: String,
    bytes: ByteArray,
    filesManager: FilesManager,
    context: Context,
): String {
    if (!workspaceId.isNullOrBlank()) {
        try {
            val entry = workspaceRepository.importFile(
                id = workspaceId,
                area = WorkspaceStorageArea.FILES,
                destinationPath = WORKSPACE_IMAGES_DIR,
                fileName = fileName,
                inputStream = bytes.inputStream(),
            )
            return "/workspace/images/${entry.name}"
        } catch (e: Exception) {
            // 工作区不可用时回落到应用内部目录
        }
    }
    val imagesDir = filesManager.getImagesDir()
    val file = java.io.File(imagesDir, fileName)
    filesManager.createImageFileFromBase64(Base64.getEncoder().encodeToString(bytes), file.absolutePath)
    return file.absolutePath
}
