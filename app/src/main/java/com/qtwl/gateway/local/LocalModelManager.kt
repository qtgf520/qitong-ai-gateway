package com.qtwl.gateway.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * 本地模型管理器 —— 手机端跑 GGUF 大模型（llama.cpp llama-server）
 *
 * 能力：
 * 1. 引擎管理：下载/部署 llama-server 二进制（GitHub release Android arm64）、启动/停止、运行状态
 * 2. 模型商城：HuggingFace API 自动拉取 GGUF 模型列表、流式下载（含进度）
 * 3. 接入网关：本地 server 提供 OpenAI 兼容 API（/v1/chat/completions），天然支持思考(tools)与工具调用
 * 4. 保活：通过 Shizuku 后台启动 + 心跳自愈（关屏不掉线）
 *
 * 依赖 Shizuku 权限（用于执行后台 shell 命令，不依赖 Termux）
 */
object LocalModelManager {

    private const val TAG = "LocalModel"

    // ==================== 常量 ====================
    const val SERVER_PORT = 18080
    const val SERVER_HOST = "127.0.0.1"
    const val SERVER_URL = "http://$SERVER_HOST:$SERVER_PORT"
    const val API_BASE = "$SERVER_URL/v1"

    /** llama-server 部署目录（Shizuku 可执行分区） */
    private const val ENGINE_DIR = "/data/local/tmp/llama"
    private const val SERVER_BIN = "$ENGINE_DIR/llama-server"

    /** 模型存放目录：沙盒私有（引擎通过 Shizuku 读取） */
    private const val MODELS_DIR_NAME = "llama_models"

    /** llama.cpp GitHub release 下载源（Android arm64 预编译） */
    private const val LLAMA_NIGHTLY_TAG_URL =
        "https://github.com/ggml-org/llama.cpp/releases/latest/download/nightly-tag.txt"
    private const val LLAMA_RELEASE_TAG_URL =
        "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"

    /** HuggingFace 商城 API（列表双源：优先镜像 API，失败回退官方 API） + 下载走国内镜像 hf-mirror.com */
    private const val HF_SEARCH_API_OFFICIAL = "https://huggingface.co/api/models"
    private const val HF_SEARCH_API_MIRROR = "https://hf-mirror.com/api/models"
    private const val HF_RESOLVE = "https://hf-mirror.com"

    /** 自愈重启时使用的模型（无 context 场景） */
    @Volatile
    var autoRestartModel: String? = null

    // ==================== 状态流 ====================
    /** 引擎状态：未安装 / 下载中 / 已就绪 / 启动中 / 运行中 / 已停止 / 错误 */
    private val _engineStatus = MutableStateFlow("未安装")
    val engineStatus: StateFlow<String> = _engineStatus.asStateFlow()

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _serverPid = MutableStateFlow(0)
    val serverPid: StateFlow<Int> = _serverPid.asStateFlow()

    /** 已安装的本地模型 */
    data class LocalModel(
        val name: String,          // 文件名 xxx.gguf
        val size: Long = 0L,       // 文件大小
        val downloaded: Long = 0L, // 已下载大小
        val url: String = "",      // 下载来源
        val isLoaded: Boolean = false, // 是否正在被 server 加载
    )

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    /** 商城模型条目 */
    data class StoreModel(
        val modelId: String,        // HF 仓库名 e.g. Qwen/Qwen2.5-0.5B-Instruct-GGUF
        val name: String,
        val fileName: String,       // 具体 gguf 文件名
        val size: Long,
        val downloadUrl: String,
        val description: String = "",
        val quant: String = "",     // 量化类型 Q4_K_M 等
    )

    private val _storeModels = MutableStateFlow<List<StoreModel>>(emptyList())
    val storeModels: StateFlow<List<StoreModel>> = _storeModels.asStateFlow()

    private val _storeLoading = MutableStateFlow(false)
    val storeLoading: StateFlow<Boolean> = _storeLoading.asStateFlow()

    /** 下载任务进度 */
    data class DownloadTask(
        val fileName: String,
        val totalBytes: Long = 0L,
        val downloadedBytes: Long = 0L,
        val active: Boolean = false,
        val done: Boolean = false,
        val error: String = "",
    )

    private val _download = MutableStateFlow<DownloadTask?>(null)
    val download: StateFlow<DownloadTask?> = _download.asStateFlow()

    private val _lastLog = MutableStateFlow("")
    val lastLog: StateFlow<String> = _lastLog.asStateFlow()

    // ==================== HTTP 客户端 ====================
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ==================== Shizuku 执行 ====================
    /**
     * 通过 Shizuku 执行 shell 命令（可后台运行，返回输出）
     * 若 Shizuku 不可用则返回 null
     * 注：newProcess 在部分版本为隐藏 API，用反射调用以兼容
     */
    fun shCommand(cmd: String): String? {
        return try {
            if (!rikka.shizuku.Shizuku.pingBinder()) return null
            val shizukuClass = rikka.shizuku.Shizuku::class.java
            val newProcess = try {
                shizukuClass.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            } catch (_: NoSuchMethodException) {
                shizukuClass.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java).apply { isAccessible = true }
            }
            val proc = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) ?: return null
            val out = try {
                proc.javaClass.getMethod("getInputStream").invoke(proc) as java.io.InputStream
            } catch (_: Throwable) { return null }
            val text = out.bufferedReader().readText()
            try { proc.javaClass.getMethod("waitFor").invoke(proc) } catch (_: Throwable) {}
            text
        } catch (e: Throwable) {
            Log.w(TAG, "shCommand failed: ${e.message}")
            null
        }
    }

    /** Shizuku 是否可用 */
    fun shizukuAvailable(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (_: Throwable) { false }

    /** 请求 Shizuku 授权 */
    fun requestShizuku(): Boolean {
        return try {
            if (rikka.shizuku.Shizuku.pingBinder()) {
                try {
                    rikka.shizuku.Shizuku.requestPermission(0)
                } catch (_: Throwable) {}
                true
            } else false
        } catch (_: Throwable) { false }
    }

    // ==================== 模型目录 ====================
    /** 获取沙盒模型目录 */
    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, MODELS_DIR_NAME).apply { mkdirs() }

    /** 模型目录绝对路径（自愈用，无 context） */
    private fun modelsDirPath(): String {
        return try {
            "/storage/emulated/0/Android/data/${com.qtwl.gateway.GatewayApplication.getInstance().packageName}/files/$MODELS_DIR_NAME"
        } catch (_: Throwable) {
            "/storage/emulated/0/Android/data/com.qtwl.gateway/files/$MODELS_DIR_NAME"
        }
    }

    /** 模型文件是否存在（Shizuku shell 探测） */
    private fun modelFileExists(fileName: String): Boolean {
        return findModelAbsolutePath(fileName) != null
    }

    /** 扫描已安装模型 */
    fun scanModels(context: Context) {
        val dir = modelsDir(context)
        val list = dir.listFiles { f -> f.extension.lowercase() in setOf("gguf", "bin") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val size = f.length()
                val loaded = isModelLoaded(f.name)
                LocalModel(name = f.name, size = size, downloaded = if (loaded) size else 0L, isLoaded = loaded)
            } ?: emptyList()
        _models.value = list
    }

    /** 判断模型是否被当前 server 加载 */
    private fun isModelLoaded(fileName: String): Boolean {
        val running = _serverRunning.value
        if (!running) return false
        val log = _lastLog.value
        return log.contains(fileName, ignoreCase = true)
    }

    // ==================== 引擎部署 ====================
    /** 检查引擎是否已就绪 */
    fun isEngineInstalled(): Boolean {
        val out = shCommand("ls -l $SERVER_BIN 2>&1") ?: return false
        return out.contains("-") && !out.contains("No such")
    }

    /** 下载并部署 llama-server 引擎（GitHub nightly android-arm64 tar.gz） */
    suspend fun installEngine(): Boolean = withContext(Dispatchers.IO) {
        _engineStatus.value = "下载引擎..."
        return@withContext try {
            // 1. 获取最新 nightly tag（如 b11960）
            var tag = ""
            try {
                val req = Request.Builder().url(LLAMA_NIGHTLY_TAG_URL).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        tag = resp.body?.string()?.trim() ?: ""
                    }
                }
            } catch (_: Exception) {}
            if (tag.isBlank()) {
                // 兜底：从 GitHub API 拿 latest release 的 nightly-tag asset
                try {
                    val req = Request.Builder().url(LLAMA_RELEASE_TAG_URL).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val j = JSONObject(resp.body?.string() ?: "{}")
                            j.optString("tag_name", "").let { if (it.isNotBlank()) tag = it }
                        }
                    }
                } catch (_: Exception) {}
            }
            if (tag.isBlank()) { _engineStatus.value = "获取版本号失败"; return@withContext false }

            // 2. 下载 tar.gz（Android arm64）
            val url = "https://github.com/ggml-org/llama.cpp/releases/download/$tag/llama-$tag-bin-android-arm64.tar.gz"
            Log.i(TAG, "下载引擎: $url")
            val tarFile = File.createTempFile("llama", ".tar.gz")
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _engineStatus.value = "引擎下载失败 HTTP ${resp.code}"
                    return@withContext false
                }
                resp.body?.byteStream()?.use { input ->
                    tarFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
            if (tarFile.length() < 1_000_000) {
                _engineStatus.value = "引擎包异常(太小 ${tarFile.length()})"
                tarFile.delete()
                return@withContext false
            }

            // 3. 解压 tar.gz 提取 llama-server → /data/local/tmp/llama/llama-server
            val out = shCommand(
                "mkdir -p $ENGINE_DIR && " +
                "tar -xzf ${tarFile.absolutePath} -C $ENGINE_DIR --wildcards '*/llama-server' 2>/dev/null; " +
                "find $ENGINE_DIR -name 'llama-server' -type f | head -1 | xargs -I{} cp {} $SERVER_BIN 2>/dev/null; " +
                "chmod 755 $SERVER_BIN; ls -l $SERVER_BIN"
            )
            tarFile.delete()
            val ok = out != null && out.contains("llama-server") && !out.contains("No such")
            _engineStatus.value = if (ok) "已就绪" else "引擎部署失败"
            if (ok) {
                shCommand("chmod 755 ${ENGINE_DIR} 2>/dev/null; echo ok")
            }
            ok
        } catch (e: Exception) {
            _engineStatus.value = "错误: ${e.message?.take(80)}"
            false
        }
    }

    // ==================== 启停 ====================
    /** 启动本地 server（加载指定模型文件） */
    suspend fun startServer(context: Context, modelFileName: String?): Boolean {
        if (!isEngineInstalled()) { _engineStatus.value = "引擎未安装"; return false }
        if (_serverRunning.value) return true
        _engineStatus.value = "启动中..."
        return withContext(Dispatchers.IO) {
            try {
                val modelPath = modelFileName?.let { modelsDir(context).absolutePath + "/" + it }
                if (modelFileName != null && modelPath != null) {
                    // 校验模型存在
                    val f = File(modelPath)
                    if (!f.exists()) { _engineStatus.value = "模型不存在: $modelFileName"; return@withContext false }
                }
                // 先杀旧进程
                shCommand("pkill -f llama-server 2>/dev/null; true")
                delay(500)

                val modelArg = if (modelPath != null) "--model \"$modelPath\"" else ""
                // 后台启动：nohup + 日志文件
                val cmd = "nohup $SERVER_BIN $modelArg " +
                    "--host $SERVER_HOST --port $SERVER_PORT " +
                    "--ctx-size 4096 --threads 4 --parallel 1 " +
                    "--jinja " +  // 模板渲染，支持 tools
                    "> $ENGINE_DIR/server.log 2>&1 & echo PID=\$!"
                val out = shCommand(cmd)
                val pid = out?.let { Regex("PID=(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 0

                // 等待端口就绪
                var ready = false
                repeat(20) {
                    delay(500)
                    val probe = shCommand("curl -s -m 2 ${SERVER_URL}/health 2>/dev/null || echo NO")
                    if (probe != null && probe.contains("ok", ignoreCase = true)) { ready = true; return@repeat }
                }
                _serverRunning.value = ready
                _serverPid.value = pid
                _engineStatus.value = if (ready) "运行中" else "端口未就绪(可能模型加载慢)"
                _lastLog.value = shCommand("tail -20 $ENGINE_DIR/server.log 2>/dev/null") ?: ""
                if (ready) {
                    autoRestartModel = modelFileName ?: autoRestartModel
                }
                if (modelFileName != null) scanModels(context)
                ready
            } catch (e: Exception) {
                _engineStatus.value = "错误: ${e.message?.take(80)}"
                false
            }
        }
    }

    /** 停止本地 server */
    suspend fun stopServer(): Boolean = withContext(Dispatchers.IO) {
        shCommand("pkill -f llama-server 2>/dev/null; true")
        delay(300)
        _serverRunning.value = false
        _serverPid.value = 0
        _engineStatus.value = "已停止"
        autoRestartModel = null
        true
    }

    /** 刷新运行状态（心跳自愈用） */
    suspend fun refreshStatus(): Boolean {
        val probe = shCommand("curl -s -m 2 ${SERVER_URL}/health 2>/dev/null || echo NO")
        val running = probe != null && probe.contains("ok", ignoreCase = true)
        _serverRunning.value = running
        if (running) _engineStatus.value = "运行中"
        return running
    }

    /**
     * 自愈重启：当 server 曾经运行但掉线时，自动用上次的模型重新拉起
     */
    suspend fun autoRestart() {
        if (_serverRunning.value) return
        if (!isEngineInstalled()) return
        // 无 model 时不自动拉（避免加载错误）
        val model = autoRestartModel ?: return
        Log.i(TAG, "autoRestart: 自愈拉起 $model")
        // 用 Shizuku 全局模型路径探测；文件必须存在才拉
        val modelPath = findModelAbsolutePath(model) ?: return
        val cmd = "nohup $SERVER_BIN --model \"$modelPath\" " +
            "--host $SERVER_HOST --port $SERVER_PORT " +
            "--ctx-size 4096 --threads 4 --parallel 1 --jinja " +
            "> $ENGINE_DIR/server.log 2>&1 & echo PID=\$!"
        val out = shCommand(cmd)
        val pid = out?.let { Regex("PID=(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 0
        var ready = false
        repeat(30) {
            kotlinx.coroutines.delay(1000)
            val probe = shCommand("curl -s -m 2 ${SERVER_URL}/health 2>/dev/null || echo NO")
            if (probe != null && probe.contains("ok", ignoreCase = true)) { ready = true; return@repeat }
        }
        _serverRunning.value = ready
        _serverPid.value = pid
        _engineStatus.value = if (ready) "运行中" else "自愈失败"
    }

    /** 通过 Shizuku 查找模型绝对路径（支持沙盒私有目录） */
    private fun findModelAbsolutePath(fileName: String): String? {
        // 常见位置：/storage/emulated/0/Android/data/<pkg>/files/llama_models/
        val candidates = listOf(
            "/storage/emulated/0/Android/data/${getAppId()}/files/$MODELS_DIR_NAME/$fileName",
            "/data/user/0/${getAppId()}/files/$MODELS_DIR_NAME/$fileName",
            "/data/local/tmp/llama/models/$fileName"
        )
        for (c in candidates) {
            val out = shCommand("ls \"$c\" 2>/dev/null || echo NO")
            if (out != null && !out.contains("NO") && out.isNotBlank()) return c
        }
        return null
    }

    /** 沙盒包名 */
    private fun getAppId(): String {
        return try {
            com.qtwl.gateway.GatewayApplication.getInstance().packageName
        } catch (_: Throwable) { "com.qtwl.gateway" }
    }

    /** 记录当前启动的模型（供自愈用） */
    fun recordRunningModel(fileName: String?) {
        autoRestartModel = fileName
    }

    // ==================== 商城（HuggingFace） ====================
    /** 拉取 GGUF 模型商城列表（HuggingFace 双源：镜像优先，官方兜底） */
    suspend fun loadStore(search: String = "gguf", limit: Int = 30): Boolean = withContext(Dispatchers.IO) {
        _storeLoading.value = true
        try {
            val query = "search=${android.net.Uri.encode(search)}&limit=$limit&full=true"
            // 1. 先试镜像 API
            var body: String? = null
            for (base in listOf(HF_SEARCH_API_MIRROR, HF_SEARCH_API_OFFICIAL)) {
                try {
                    val url = "$base?$query"
                    val req = Request.Builder().url(url).build()
                    val respBody = client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                    if (respBody != null && respBody.trim().startsWith("[")) {
                        body = respBody; Log.i(TAG, "商城 API 用 $base"); break
                    }
                } catch (_: Exception) {}
            }
            if (body == null) { _storeLoading.value = false; return@withContext false }
            val arr = JSONArray(body)
            val list = mutableListOf<StoreModel>()
            for (i in 0 until arr.length()) {
                val repo = arr.getJSONObject(i)
                val modelId = repo.optString("id", "")
                val siblings = repo.optJSONArray("siblings") ?: continue
                // 找出 gguf 文件
                for (j in 0 until siblings.length()) {
                    val f = siblings.getJSONObject(j)
                    val fn = f.optString("rfilename", "")
                    if (!fn.endsWith(".gguf", ignoreCase = true)) continue
                    // 过滤过大的文件（>6GB 手机跑不动）
                    val size = f.optLong("size", 0L)
                    if (size > 6L * 1024 * 1024 * 1024) continue
                    val quant = Regex("(Q\\d+_?[A-Z0-9]*)").find(fn)?.groupValues?.getOrNull(1) ?: ""
                    val dl = "$HF_RESOLVE/$modelId/resolve/main/$fn"
                    list.add(
                        StoreModel(
                            modelId = modelId,
                            name = modelId.substringAfterLast("/"),
                            fileName = fn,
                            size = size,
                            downloadUrl = dl,
                            description = repo.optString("description", "").take(120),
                            quant = quant,
                        )
                    )
                    break // 每个仓库只取第一个 gguf
                }
                if (list.size >= limit) break
            }
            _storeModels.value = list
            true
        } catch (e: Exception) {
            Log.w(TAG, "loadStore failed: ${e.message}")
            false
        } finally {
            _storeLoading.value = false
        }
    }

    // ==================== 模型下载 ====================
    /** 下载模型（流式，带进度）到沙盒目录 */
    suspend fun downloadModel(context: Context, store: StoreModel) {
        if (_download.value?.active == true) return
        val target = File(modelsDir(context), store.fileName)
        _download.value = DownloadTask(fileName = store.fileName, totalBytes = store.size, active = true)
        try {
            val req = Request.Builder().url(store.downloadUrl)
                .header("User-Agent", "qiTongGateway")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _download.value = DownloadTask(fileName = store.fileName, active = false, done = false, error = "HTTP ${resp.code}")
                    return
                }
                val total = resp.body?.contentLength() ?: store.size
                var downloaded = 0L
                resp.body?.byteStream()?.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            _download.value = DownloadTask(
                                fileName = store.fileName, totalBytes = total, downloadedBytes = downloaded, active = true
                            )
                        }
                    }
                }
            }
            _download.value = DownloadTask(fileName = store.fileName, totalBytes = target.length(), downloadedBytes = target.length(), active = false, done = true)
            scanModels(context)
            // 下载完成 → 开放文件权限给 Shizuku shell 读取（关键！llama-server 是 shell 身份跑）
            try {
                target.setReadable(true, false)
                target.parentFile?.setReadable(true, false)
                target.parentFile?.setExecutable(true, false)
            } catch (_: Throwable) {}
        } catch (e: Exception) {
            _download.value = DownloadTask(fileName = store.fileName, active = false, done = false, error = e.message?.take(60) ?: "失败")
            target.delete()
        }
    }

    // ==================== 接入网关 ====================
    /** 生成内置服务商（自动注册到网关数据库，供 ProviderDao insert） */
    fun buildProvider(name: String = "本地模型"): com.qtwl.gateway.data.model.Provider {
        return com.qtwl.gateway.data.model.Provider(
            name = name,
            type = "OpenAI Compatible",
            baseUrl = SERVER_URL,
            port = "",
            apiKey = null,
            isEnabled = true,
            orderIndex = 999,
            chatPath = "/v1/chat/completions",
            supportsSystemRole = true,
        )
    }
}