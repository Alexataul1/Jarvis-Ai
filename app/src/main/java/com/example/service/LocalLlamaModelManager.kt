package com.example.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

private const val TAG = "LocalLlamaModelManager"
private const val PREFS_NAME = "local_ai_models_prefs"

enum class ModelType {
    LLM_LLAMA,
    STT_WHISPER
}

data class LocalAiModelInfo(
    val id: String,
    val name: String,
    val subtitle: String,
    val description: String,
    val type: ModelType,
    val repository: String,
    val format: String,
    val license: String,
    val sizeBytes: Long,
    val minValidBytes: Long,
    val sizeDisplay: String,
    val minRamGb: Float,
    val recommendedRamGb: Float,
    val fileName: String,
    val downloadUrl: String,
    val isRecommendedForDevice: Boolean = false
)

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedKbps: Int
    ) : ModelInstallState
    data class Installed(
        val localPath: String,
        val sizeBytes: Long,
        val installedAt: Long,
        val loadVerified: Boolean = false
    ) : ModelInstallState
    data class Active(
        val localPath: String,
        val modelName: String,
        val loadVerified: Boolean = false
    ) : ModelInstallState
    data class Error(val message: String) : ModelInstallState
}

data class DeviceHardwareSpecs(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val totalStorageGb: Float,
    val availableStorageGb: Float,
    val processorCores: Int,
    val isRamSufficientFor1B: Boolean,
    val isRamSufficientFor3B: Boolean,
    val recommendedLlamaModelId: String
)

/**
 * Real Model Download, Integrity Verification & Storage Manager for:
 * 1. Meta Llama 3.2 1B Instruct (GGUF Q4_K_M) — bartowski/Llama-3.2-1B-Instruct-GGUF (807,694,464 bytes)
 * 2. Meta Llama 3.2 3B Instruct (GGUF Q4_K_M) — bartowski/Llama-3.2-3B-Instruct-GGUF (2,019,377,696 bytes, optional)
 * 3. Whisper Base Multilingual (GGML) — ggerganov/whisper.cpp (147,951,465 bytes)
 *
 * Guarantees:
 * - Zero fake or simulated model creation
 * - Writes to .part temporary files and validates HTTP status, Content-Type, file size, and binary magic headers
 * - Resumes only when server returns HTTP 206 Partial Content with valid Content-Range
 * - Atomically promotes verified .part files to final storage
 */
open class LocalLlamaModelManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val modelLoadVerifier: (suspend (LocalAiModelInfo, File) -> Result<String>)? = null,
    private val catalogOverride: List<LocalAiModelInfo>? = null
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeDownloadJobs = mutableMapOf<String, Job>()

    val availableModels: List<LocalAiModelInfo> by lazy {
        if (catalogOverride != null) return@lazy catalogOverride
        val specs = getDeviceHardwareSpecs()
        listOf(
            LocalAiModelInfo(
                id = "llama_3_2_1b",
                name = "Meta Llama 3.2 1B Instruct",
                subtitle = "Default for 8 GB RAM • GGUF Q4_K_M (~770 MiB)",
                description = "Lightweight quantized Llama 3.2 1B model via llama.cpp. Recommended default for 8 GB RAM Android phones.",
                type = ModelType.LLM_LLAMA,
                repository = "bartowski/Llama-3.2-1B-Instruct-GGUF",
                format = "GGUF (Q4_K_M)",
                license = "Meta Llama 3.2 Community License",
                sizeBytes = 807_694_464L,
                minValidBytes = 700_000_000L,
                sizeDisplay = "770 MB",
                minRamGb = 3.0f,
                recommendedRamGb = 6.0f,
                fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                isRecommendedForDevice = specs.recommendedLlamaModelId == "llama_3_2_1b"
            ),
            LocalAiModelInfo(
                id = "llama_3_2_3b",
                name = "Meta Llama 3.2 3B Instruct (Optional)",
                subtitle = "Optional Heavy Brain • GGUF Q4_K_M (~1.88 GiB)",
                description = "Larger 3B parameter model. Optional on 8 GB phones—requires ~2.8 GB+ free RAM to load safely.",
                type = ModelType.LLM_LLAMA,
                repository = "bartowski/Llama-3.2-3B-Instruct-GGUF",
                format = "GGUF (Q4_K_M)",
                license = "Meta Llama 3.2 Community License",
                sizeBytes = 2_019_377_696L,
                minValidBytes = 1_800_000_000L,
                sizeDisplay = "1.88 GB",
                minRamGb = 7.5f,
                recommendedRamGb = 10.0f,
                fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                isRecommendedForDevice = specs.recommendedLlamaModelId == "llama_3_2_3b"
            ),
            LocalAiModelInfo(
                id = "whisper_base_stt",
                name = "Whisper Base Multilingual (whisper.cpp)",
                subtitle = "Offline Speech-to-Text • GGML (~141 MiB)",
                description = "MIT-licensed whisper.cpp multilingual speech recognition model for offline microphone transcription (Hindi & English).",
                type = ModelType.STT_WHISPER,
                repository = "ggerganov/whisper.cpp",
                format = "GGML (ggml-base.bin)",
                license = "MIT License",
                sizeBytes = 147_951_465L,
                minValidBytes = 130_000_000L,
                sizeDisplay = "141 MB",
                minRamGb = 2.0f,
                recommendedRamGb = 4.0f,
                fileName = "ggml-base.bin",
                downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
                isRecommendedForDevice = true
            )
        )
    }

    private val _modelStates = MutableStateFlow<Map<String, ModelInstallState>>(emptyMap())
    val modelStates: StateFlow<Map<String, ModelInstallState>> = _modelStates.asStateFlow()

    private val _activeLlamaModelId = MutableStateFlow(
        prefs.getString("active_llama_id", "llama_3_2_1b") ?: "llama_3_2_1b"
    )
    val activeLlamaModelId: StateFlow<String> = _activeLlamaModelId.asStateFlow()

    init {
        refreshModelStates()
    }

    fun getDeviceHardwareSpecs(): DeviceHardwareSpecs {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
        val availRamMb = memoryInfo.availMem / (1024 * 1024)

        val stat = StatFs(context.filesDir.absolutePath)
        val totalStorageGb = (stat.blockCountLong * stat.blockSizeLong).toFloat() / (1024f * 1024f * 1024f)
        val availableStorageGb = (stat.availableBlocksLong * stat.blockSizeLong).toFloat() / (1024f * 1024f * 1024f)

        val processorCores = Runtime.getRuntime().availableProcessors()

        // Default to 1B on 8 GB RAM devices; 3B is optional and only enabled if RAM permits
        val isRamSufficientFor1B = totalRamMb >= 3000
        val isRamSufficientFor3B = totalRamMb >= 7500 && (availRamMb >= 2600 || totalRamMb >= 10000)
        val recommendedLlamaId = if (totalRamMb >= 10500 && availRamMb >= 3800) {
            "llama_3_2_3b"
        } else {
            "llama_3_2_1b"
        }

        return DeviceHardwareSpecs(
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            totalStorageGb = totalStorageGb,
            availableStorageGb = availableStorageGb,
            processorCores = processorCores,
            isRamSufficientFor1B = isRamSufficientFor1B,
            isRamSufficientFor3B = isRamSufficientFor3B,
            recommendedLlamaModelId = recommendedLlamaId
        )
    }

    fun getModelsDir(): File {
        val dir = File(context.filesDir, "local_ai_models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelFile(fileName: String): File = File(getModelsDir(), fileName)

    fun getPartFile(fileName: String): File = File(getModelsDir(), "$fileName.part")

    fun refreshModelStates() {
        val states = mutableMapOf<String, ModelInstallState>()
        for (model in availableModels) {
            if (activeDownloadJobs[model.id]?.isActive == true) {
                _modelStates.value[model.id]?.let { states[model.id] = it }
                continue
            }

            val file = getModelFile(model.fileName)
            val isVerified = file.exists() && verifyModelIntegrity(file, model).isSuccess

            if (isVerified) {
                val loadVerified = prefs.getBoolean("load_verified_${model.id}", false)
                if (model.type == ModelType.LLM_LLAMA && model.id == _activeLlamaModelId.value) {
                    states[model.id] = ModelInstallState.Active(
                        localPath = file.absolutePath,
                        modelName = model.name,
                        loadVerified = loadVerified
                    )
                } else {
                    states[model.id] = ModelInstallState.Installed(
                        localPath = file.absolutePath,
                        sizeBytes = file.length(),
                        installedAt = file.lastModified(),
                        loadVerified = loadVerified
                    )
                }
            } else {
                // Clean invalid/incomplete remnants so unverified or fake files are never reported as installed
                if (file.exists()) {
                    Log.w(TAG, "Removing unverified or corrupt model file: ${file.name} (${file.length()} bytes)")
                    file.delete()
                }
                prefs.edit()
                    .remove("installed_${model.id}")
                    .remove("load_verified_${model.id}")
                    .apply()
                states[model.id] = ModelInstallState.NotInstalled
            }
        }
        _modelStates.value = states
    }

    fun isModelInstalled(modelId: String): Boolean {
        val state = _modelStates.value[modelId]
        return state is ModelInstallState.Installed || state is ModelInstallState.Active
    }

    fun getInstalledModelFile(modelId: String): File? {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return null
        val file = getModelFile(model.fileName)
        return if (file.exists() && verifyModelIntegrity(file, model).isSuccess) file else null
    }

    fun getInstalledWhisperModelFile(): File? = getInstalledModelFile("whisper_base_stt")

    fun setActiveLlamaModel(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId && it.type == ModelType.LLM_LLAMA } ?: return
        _activeLlamaModelId.value = model.id
        prefs.edit().putString("active_llama_id", model.id).apply()
        refreshModelStates()
    }

    fun markModelLoadVerified(modelId: String, verified: Boolean) {
        prefs.edit().putBoolean("load_verified_$modelId", verified).apply()
        refreshModelStates()
    }

    /**
     * Validates binary format header and size bounds strictly.
     * Rejects HTML/text/fake tiny files immediately.
     */
    fun verifyModelIntegrity(
        file: File,
        model: LocalAiModelInfo,
        expectedHttpSize: Long = -1L
    ): Result<Unit> {
        if (!file.exists() || !file.isFile) {
            return Result.failure(IllegalStateException("Model file does not exist."))
        }

        val actualLen = file.length()
        if (actualLen < model.minValidBytes) {
            return Result.failure(
                IllegalStateException(
                    "Model file is incomplete or invalid ($actualLen bytes; expected >= ${model.minValidBytes} bytes)."
                )
            )
        }
        if (expectedHttpSize > 0 && actualLen != expectedHttpSize) {
            return Result.failure(
                IllegalStateException(
                    "Downloaded size mismatch ($actualLen bytes vs server Content-Length $expectedHttpSize bytes)."
                )
            )
        }

        val header = ByteArray(16)
        val readCount = try {
            FileInputStream(file).use { it.read(header) }
        } catch (e: IOException) {
            return Result.failure(IllegalStateException("Cannot read model header: ${e.message}", e))
        }

        if (readCount < 8) {
            return Result.failure(IllegalStateException("Model file header is truncated."))
        }

        // Reject accidental HTML / JSON error pages saved to disk
        val leadingAscii = String(header, 0, readCount, Charsets.UTF_8).trimStart()
        if (leadingAscii.startsWith("<") || leadingAscii.startsWith("{")) {
            return Result.failure(IllegalStateException("Downloaded file is an HTML/JSON response instead of a binary model."))
        }

        return when (model.type) {
            ModelType.LLM_LLAMA -> {
                val isGgufMagic = header[0] == 0x47.toByte() &&
                    header[1] == 0x47.toByte() &&
                    header[2] == 0x55.toByte() &&
                    header[3] == 0x46.toByte()
                if (!isGgufMagic) {
                    return Result.failure(IllegalStateException("Invalid GGUF magic header (expected 'GGUF')."))
                }
                val version = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (version !in 1..3) {
                    return Result.failure(IllegalStateException("Unsupported GGUF version: $version."))
                }
                Result.success(Unit)
            }
            ModelType.STT_WHISPER -> {
                // whisper.cpp GGML magic is 'lmgg' (0x6c, 0x6d, 0x67, 0x67) in little-endian
                val isGgmlMagic = header[0] == 0x6C.toByte() &&
                    header[1] == 0x6D.toByte() &&
                    header[2] == 0x67.toByte() &&
                    header[3] == 0x67.toByte()
                if (!isGgmlMagic) {
                    return Result.failure(IllegalStateException("Invalid Whisper GGML magic header."))
                }
                Result.success(Unit)
            }
        }
    }

    /**
     * Real HTTP download with `.part` temp file, Range resume (only on HTTP 206),
     * storage verification, HTML/error rejection, retry, and atomic promotion.
     */
    fun startModelDownload(modelId: String) {
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        activeDownloadJobs[modelId]?.cancel()

        val job = scope.launch {
            runDownloadSuspend(model)
        }
        activeDownloadJobs[modelId] = job
    }

    suspend fun downloadModelBlocking(modelId: String): Result<File> = withContext(Dispatchers.IO) {
        val model = availableModels.firstOrNull { it.id == modelId }
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown model: $modelId"))
        runDownloadSuspend(model)
    }

    private suspend fun runDownloadSuspend(model: LocalAiModelInfo): Result<File> {
        val modelId = model.id
        val targetFile = getModelFile(model.fileName)
        val partFile = getPartFile(model.fileName)

        // Clean any stale leftover ".download" files from earlier versions
        File(getModelsDir(), "${model.fileName}.download").takeIf { it.exists() }?.delete()

        return try {
            val specs = getDeviceHardwareSpecs()
            val existingPartBytes = if (partFile.exists() && partFile.length() < model.sizeBytes) {
                partFile.length()
            } else {
                if (partFile.exists()) partFile.delete()
                0L
            }

            val remainingNeeded = (model.sizeBytes - existingPartBytes).coerceAtLeast(0L) + (256L * 1024L * 1024L)
            val availableStorageBytes = availableInternalStorageBytes()
            if (availableStorageBytes < remainingNeeded) {
                val freeMb = availableStorageBytes / (1024 * 1024)
                val reqMb = remainingNeeded / (1024 * 1024)
                val msg = "Insufficient storage: $freeMb MB free, need at least $reqMb MB for ${model.name}."
                updateModelState(modelId, ModelInstallState.Error(msg))
                return Result.failure(IOException(msg))
            }

            updateModelState(
                modelId,
                ModelInstallState.Downloading(
                    progress = (existingPartBytes.toFloat() / model.sizeBytes.toFloat()).coerceIn(0f, 0.99f),
                    downloadedBytes = existingPartBytes,
                    totalBytes = model.sizeBytes,
                    speedKbps = 0
                )
            )

            var lastError: Throwable? = null
            var expectedTotalBytes = model.sizeBytes
            var completed = false

            val maxAttempts = 3
            for (attempt in 1..maxAttempts) {
                try {
                    expectedTotalBytes = executeSingleDownloadAttempt(model, partFile)
                    completed = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NonRetryableDownloadException) {
                    lastError = e
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Download attempt $attempt/$maxAttempts failed for $modelId: ${e.message}")
                    if (attempt < maxAttempts) {
                        delay(1000L * attempt)
                    }
                }
            }

            if (!completed) {
                val msg = lastError?.message ?: "Model download failed."
                updateModelState(modelId, ModelInstallState.Error(msg))
                return Result.failure(lastError ?: IOException(msg))
            }

            // Validate size + binary magic header before moving into final location
            val verifyRes = verifyModelIntegrity(partFile, model, expectedTotalBytes)
            if (verifyRes.isFailure) {
                val err = verifyRes.exceptionOrNull()!!
                partFile.delete()
                val msg = "Downloaded file failed integrity check: ${err.message}"
                updateModelState(modelId, ModelInstallState.Error(msg))
                return Result.failure(IllegalStateException(msg, err))
            }

            // Atomically move verified .part file into final destination
            moveFileAtomically(partFile, targetFile)

            // Persist installation ONLY after full verification passes
            prefs.edit()
                .putBoolean("installed_$modelId", true)
                .putLong("verified_size_$modelId", targetFile.length())
                .apply()

            // Optional engine load test hook
            var loadVerified = false
            if (modelLoadVerifier != null) {
                val loadRes = modelLoadVerifier.invoke(model, targetFile)
                if (loadRes.isFailure) {
                    val msg = "Downloaded GGUF verified, but engine failed to load it: ${loadRes.exceptionOrNull()?.message}"
                    prefs.edit().putBoolean("load_verified_$modelId", false).apply()
                    updateModelState(modelId, ModelInstallState.Error(msg))
                    return Result.failure(loadRes.exceptionOrNull() ?: IllegalStateException(msg))
                }
                loadVerified = true
            }
            prefs.edit().putBoolean("load_verified_$modelId", loadVerified).apply()

            if (model.type == ModelType.LLM_LLAMA && (!isModelInstalled(_activeLlamaModelId.value) || modelId == _activeLlamaModelId.value)) {
                _activeLlamaModelId.value = modelId
                prefs.edit().putString("active_llama_id", modelId).apply()
            }

            refreshModelStates()
            Result.success(targetFile)
        } catch (e: CancellationException) {
            partFile.delete()
            refreshModelStates()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected download failure for $modelId", e)
            updateModelState(modelId, ModelInstallState.Error(e.message ?: "Download failed"))
            Result.failure(e)
        } finally {
            activeDownloadJobs.remove(modelId)
        }
    }

    private suspend fun executeSingleDownloadAttempt(
        model: LocalAiModelInfo,
        partFile: File
    ): Long = withContext(Dispatchers.IO) {
        val resumeFromBytes = if (partFile.exists() && partFile.length() in 1L until model.sizeBytes) {
            partFile.length()
        } else {
            if (partFile.exists() && partFile.length() >= model.sizeBytes) {
                // Might already be complete on disk; verify header before re-downloading
                if (verifyModelIntegrity(partFile, model, model.sizeBytes).isSuccess) {
                    return@withContext partFile.length()
                }
                partFile.delete()
            }
            0L
        }

        val reqBuilder = Request.Builder()
            .url(model.downloadUrl)
            .header("User-Agent", "Jarvis-Android-AI/1.0")
        if (resumeFromBytes > 0L) {
            reqBuilder.header("Range", "bytes=$resumeFromBytes-")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val code = response.code
            if (code == 401 || code == 403 || code == 404 || code == 410) {
                throw NonRetryableDownloadException("Server returned HTTP $code for ${model.fileName}. Check model URL or access permissions.")
            }
            if (code != 200 && code != 206) {
                throw IOException("HTTP error $code while downloading ${model.fileName}.")
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html") ||
                contentType.contains("application/json") ||
                contentType.contains("application/xml") ||
                contentType.startsWith("text/")
            ) {
                throw NonRetryableDownloadException(
                    "Server returned unexpected content-type '$contentType' instead of binary model."
                )
            }

            val body = response.body ?: throw IOException("Empty response body from server.")
            val isResuming = (code == 206 && resumeFromBytes > 0L)
            val contentLength = body.contentLength()
            val expectedTotalBytes = when {
                isResuming -> parseTotalFromContentRange(response.header("Content-Range"))
                    ?: if (contentLength > 0) resumeFromBytes + contentLength else model.sizeBytes
                contentLength > 0 -> contentLength
                else -> model.sizeBytes
            }

            // If server ignored Range and returned 200, restart cleanly from byte 0
            val append = isResuming
            if (!append && partFile.exists()) {
                partFile.delete()
            }

            var bytesCopied = if (append) resumeFromBytes else 0L
            var lastUpdateTime = System.currentTimeMillis()
            var lastBytes = bytesCopied
            val buffer = ByteArray(64 * 1024)

            body.byteStream().use { input ->
                FileOutputStream(partFile, append).use { output ->
                    var firstReadChecked = append
                    while (isActive) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break

                        if (!firstReadChecked && bytesRead > 0) {
                            firstReadChecked = true
                            val headStr = String(buffer, 0, minOf(bytesRead, 32), Charsets.UTF_8).trimStart()
                            if (headStr.startsWith("<!DOCTYPE", ignoreCase = true) ||
                                headStr.startsWith("<html", ignoreCase = true) ||
                                headStr.startsWith("{")
                            ) {
                                throw NonRetryableDownloadException("Received HTML/JSON error page instead of model binary.")
                            }
                        }

                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead

                        val now = System.currentTimeMillis()
                        val timeDiff = now - lastUpdateTime
                        if (timeDiff >= 350 || bytesCopied >= expectedTotalBytes) {
                            val speedKbps = if (timeDiff > 0) {
                                (((bytesCopied - lastBytes) * 1000L) / (timeDiff * 1024L)).toInt().coerceAtLeast(0)
                            } else 0
                            val progress = (bytesCopied.toFloat() / expectedTotalBytes.toFloat()).coerceIn(0f, 1f)
                            updateModelState(
                                model.id,
                                ModelInstallState.Downloading(
                                    progress = progress,
                                    downloadedBytes = bytesCopied,
                                    totalBytes = expectedTotalBytes,
                                    speedKbps = speedKbps
                                )
                            )
                            lastUpdateTime = now
                            lastBytes = bytesCopied
                        }
                    }
                    output.flush()
                }
            }

            if (!isActive) {
                throw CancellationException("Download cancelled")
            }

            expectedTotalBytes
        }
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slashIdx = header.indexOf('/')
        if (slashIdx == -1 || slashIdx == header.lastIndex) return null
        val totalPart = header.substring(slashIdx + 1).trim()
        return if (totalPart == "*") null else totalPart.toLongOrNull()
    }

    private fun moveFileAtomically(source: File, target: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } else {
            if (target.exists()) target.delete()
            if (!source.renameTo(target)) {
                throw IOException("Failed to move verified model into ${target.absolutePath}")
            }
        }
    }

    protected open fun availableInternalStorageBytes(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    fun cancelDownload(modelId: String) {
        activeDownloadJobs[modelId]?.cancel()
        activeDownloadJobs.remove(modelId)
        availableModels.firstOrNull { it.id == modelId }?.let { model ->
            getPartFile(model.fileName).takeIf { it.exists() }?.delete()
        }
        refreshModelStates()
    }

    fun deleteModel(modelId: String) {
        activeDownloadJobs[modelId]?.cancel()
        activeDownloadJobs.remove(modelId)
        val model = availableModels.firstOrNull { it.id == modelId } ?: return
        getModelFile(model.fileName).takeIf { it.exists() }?.delete()
        getPartFile(model.fileName).takeIf { it.exists() }?.delete()
        prefs.edit()
            .remove("installed_$modelId")
            .remove("verified_size_$modelId")
            .remove("load_verified_$modelId")
            .apply()
        refreshModelStates()
    }

    private fun updateModelState(modelId: String, state: ModelInstallState) {
        val current = _modelStates.value.toMutableMap()
        current[modelId] = state
        _modelStates.value = current
    }

    private class NonRetryableDownloadException(message: String) : IOException(message)
}
