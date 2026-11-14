package com.qtwl.gateway.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 本地推理引擎 —— llama.cpp (llama-server) 版
 *
 * 为什么用 llama.cpp 而不是 MediaPipe：
 * MediaPipe 的 libllm_inference_engine_jni.so 在部分设备（ColorOS /
 * Android 15 / 一加）上初始化时会在原生层 abort，且不输出错误信息。
 * llama.cpp 以【独立进程 + HTTP】方式运行，推理崩溃只影响子进程。
 *
 * 工作原理：
 *  1. 运行时（llama-server 及依赖 .so）随 APK 安装到 nativeLibraryDir
 *  2. 加载模型：子进程启动 llama-server，监听 127.0.0.1 本地端口
 *  3. 生成文本：HTTP 调用 /completion，支持流式
 *  4. 释放：结束子进程
 *
 * v3.66 修复清单：
 *  1. 对话模板按模型族分派：ChatML(Qwen) / Gemma / Llama3
 *  2. taskset 掩码按 cpufreq 动态识别大核，失败自动降级不绑核
 *  3. nThreads 与实际绑定核数一致
 *  4. n_predict 按上下文动态计算，不再硬编码 512
 *  5. generate() 支持协程取消，真正掐断 HTTP 连接
 *  6. 子进程日志保留最后 20 行，便于定位 dlopen 失败
 *  7. 释放时 destroyForcibly + 超时兜底
 *  8. KV cache 量化启动失败时自动降级重试
 *  9. 端口自动探测，不再写死 18080
 * 10. isRuntimeReady 返回缺失清单
 * 11. estimateTokens 不再无条件 +1
 */
object LocalLlmEngine {

    data class EngineState(
        val modelId: String = "",
        val loading: Boolean = false,
        val ready: Boolean = false,
        val error: String? = null,
        val loadMs: Long = 0,
        val maxTokens: Int = 0,
        /** 加载阶段文案，界面显示用 */
        val stage: String = "",
        // ★ 运行时参数（界面展示 + 排查性能问题用）
        val threads: Int = 0,
        val batch: Int = 0,
        val kvQuant: Boolean = false,
        val backend: String = ""
    )

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private const val BASE_PORT = 18080
    private const val PORT_SCAN = 20

    private val REQUIRED_LIBS = listOf(
        "libmtmd.so",
        "libllama.so",
        "libggml.so",
        "libggml-base.so",
        "libllama-common.so",
        "libllama-server-impl.so"
    )

    private var process: Process? = null
    private var loadedModelId: String = ""

    @Volatile
    private var activePort: Int = BASE_PORT

    @Volatile
    private var activeCtx: Int = 0

    @Volatile
    private var lastLog: String = ""

    private val logLines = ArrayDeque<String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val isReady: Boolean get() = process?.isAlive == true
    fun loadedId(): String = loadedModelId
    fun currentContextTokens(): Int = activeCtx

    /** 本机推理服务的 OpenAI 兼容地址，供 Agent 引擎直接使用 */
    fun serverBaseUrl(): String = "http://127.0.0.1:" + activePort

    /** 本次生成给输出预留的 token 数，供 trimHistory 对齐 */
    fun reserveForOutputTokens(maxTokens: Int): Int =
        (maxTokens / 4).coerceIn(256, 1024)

    /**
     * 按 /proc/cpuinfo 的 features 推断最匹配的 ggml CPU 变体。
     * ggml 内部会自己挑，这里只用于界面展示，方便你报给我定位性能问题。
     */
    fun detectCpuVariant(): String {
        return try {
            val feat = Regex("(?i)features\\s*:\\s*(.*)")
                .findAll(File("/proc/cpuinfo").readText())
                .joinToString(" ") { it.groupValues[1].lowercase() }
            when {
                feat.contains("sve2") && feat.contains("i8mm") -> "armv9.2"
                feat.contains("sve2") -> "armv9.0"
                feat.contains("i8mm") && feat.contains("bf16") -> "armv8.6"
                feat.contains("i8mm") || feat.contains("dotprod") -> "armv8.2"
                else -> "armv8.0"
            }
        } catch (_: Throwable) {
            "未知"
        }
    }

    fun runtimeDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    private fun serverBinary(context: Context) = File(runtimeDir(context), "libllamaserver.so")

    /** 返回缺失的原生库清单；能精确告诉用户缺哪个 .so */
    fun missingLibs(context: Context): List<String> {
        val dir = runtimeDir(context)
        val missing = mutableListOf<String>()
        if (!serverBinary(context).exists()) missing.add("libllamaserver.so")
        REQUIRED_LIBS.forEach { name ->
            if (!File(dir, name).exists()) missing.add(name)
        }
        // 新版 ggml 把 CPU 后端按微架构拆成多个变体（libggml-cpu-android_armv8.x_N.so），
        // 运行时按 CPU 特性挑一个加载，所以只要存在任意一个就算齐全。
        val hasCpuBackend = dir.listFiles()?.any { it.name.startsWith("libggml-cpu") } == true
        if (!hasCpuBackend) missing.add("libggml-cpu-android_*.so")
        return missing
    }

    fun isRuntimeReady(context: Context): Boolean = missingLibs(context).isEmpty()

// ==================== 加载 ====================

    /** 一次启动所需的全部参数 */
    private data class ServerCmdArgs(
        val binPath: String,
        val modelPath: String,
        val port: Int,
        val ctx: Int,
        val threads: Int,
        val batch: Int,
        val lockMemory: Boolean
    )

    suspend fun load(
        context: Context,
        spec: LocalModelManager.LocalModelSpec,
        maxTokens: Int = spec.recommendedTokens
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (loadedModelId == spec.id && process?.isAlive == true) {
            _state.value = _state.value.copy(loading = false, ready = true)
            return@withContext Result.success(Unit)
        }

        _state.value = EngineState(modelId = spec.id, loading = true, ready = false, stage = "检查运行时")
        val start = System.currentTimeMillis()

        try {
            // 预检 1：模型文件完整性
            val modelPath = LocalModelManager.modelPath(context, spec)
            val modelFile = File(modelPath)
            if (!modelFile.exists()) throw IllegalStateException("模型文件不存在，请先下载")
            if (spec.sizeBytes > 0 && modelFile.length() < spec.sizeBytes * 95 / 100) {
                throw IllegalStateException(
                    "模型文件不完整（${modelFile.length() / 1024 / 1024}MB / ${spec.sizeBytes / 1024 / 1024}MB），请重新下载"
                )
            }

            // 预检 2：运行时
            val missing = missingLibs(context)
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "推理引擎缺失，APK 里少了 ${missing.size} 个原生库：" +
                            missing.joinToString("、") +
                            "。请重新构建包含完整 jniLibs 的安装包。"
                )
            }

            // 预检 3：内存
            val needMb = (spec.sizeBytes / 1024 / 1024 * 1.5).toLong()
            val availMb = readAvailableMemoryMb()
            if (availMb > 0 && availMb < needMb) {
                throw IllegalStateException(
                    "内存不足：需要约 ${needMb}MB，当前可用 ${availMb}MB。请关闭其他应用或换更小的模型。"
                )
            }

            releaseInternal()
            _state.value = _state.value.copy(stage = "启动推理服务")

            val dir = runtimeDir(context)
            val bin = serverBinary(context)
            if (!bin.canExecute()) bin.setExecutable(true)

            val ctx = ctxSizeFor(spec, maxTokens)

            // 大核识别 + 线程数对齐
            val bigCores = detectBigCores()
            val mask = if (bigCores.size >= 2) maskFor(bigCores) else null
            // ★ 线程数：大核数 + 1，用来填掉纯大核调度留下的空档；
            //   大核很少（<4）时退回到「总核数 - 1」，避免只用两三个小核干等。
            val cores = Runtime.getRuntime().availableProcessors()
            // 只用大核：小核参与批处理只会拖慢同步点。
            // 大核识别不到（<2）时才退回总核数的一半。
            val nThreads = when {
                cores <= 4 -> cores
                bigCores.size >= 2 -> bigCores.size
                else -> cores / 2
            }.coerceIn(2, 8)

            val nBatch = when {
                availMb >= 3500 -> 512
                availMb >= 2000 -> 256
                else -> 128
            }

            activePort = pickFreePort()

            val cmdArgs = ServerCmdArgs(
                binPath = bin.absolutePath,
                modelPath = modelPath,
                port = activePort,
                ctx = ctx,
                threads = nThreads,
                batch = nBatch,
                lockMemory = availMb >= 3000
            )

            // KV cache 量化不被支持时自动降级重试一次
            var started = false
            var lastError = ""
            var usedLevel = 0
            for (level in 0..2) {
                _state.value = _state.value.copy(
                    stage = when (level) {
                        0 -> "启动推理服务"
                        1 -> "启动推理服务（兼容模式）"
                        else -> "启动推理服务（最小参数）"
                    }
                )
                try {
                    val p = startProcess(buildCmd(cmdArgs, level), dir, mask)
                    process = p
                    loadedModelId = spec.id
                    drainOutput(p)

                    if (waitForServer(p, startupTimeoutMs(spec))) {
                        started = true
                        usedLevel = level
                        break
                    }
                    lastError = lastLog.ifEmpty { "服务启动超时" }
                    releaseInternal()
                    if (!looksLikeUnsupportedParam(lastError)) break
                } catch (e: Throwable) {
                    lastError = e.message ?: e.javaClass.simpleName
                    releaseInternal()
                    if (!looksLikeUnsupportedParam(lastError)) break
                }
            }

            if (!started) throw IllegalStateException("推理服务启动失败：$lastError")

            activeCtx = ctx
            _state.value = EngineState(
                modelId = spec.id, loading = false, ready = true,
                loadMs = System.currentTimeMillis() - start, maxTokens = ctx, stage = "",
                threads = cmdArgs.threads, batch = cmdArgs.batch,
                kvQuant = usedLevel == 0, backend = detectCpuVariant()
            )
            Result.success(Unit)
        } catch (e: Throwable) {
            releaseInternal()
            _state.value = EngineState(
                modelId = spec.id, loading = false, ready = false,
                error = e.message ?: e.javaClass.simpleName
            )
            Result.failure(e)
        }
    }

    /**
     * 组装 llama-server 命令行。
     * 注意：不加 --jinja，否则会在手工拼好的对话模板外再包一层，
     * 形成双层模板导致模型复读、自问自答。
     */
    private fun buildCmd(a: ServerCmdArgs, level: Int): List<String> {
        // level 0：完整参数（KV 量化 + 内存锁定）
        // level 1：去掉 KV 量化与 mlock/no-mmap —— 不同版本的 llama.cpp 对这些支持不一致
        // level 2：最小可用集，只保留模型 / 端口 / 上下文 / 线程
        //          （新版把 libllama-common 里的参数表改过，老参数会直接 unrecognized 退出）
        val c = mutableListOf(
            a.binPath,
            "-m", a.modelPath,
            "--host", "127.0.0.1",
            "--port", a.port.toString(),
            "-c", a.ctx.toString(),
            "-t", a.threads.toString()
        )
        if (level <= 1) {
            c.addAll(listOf(
                "--threads-batch", a.threads.toString(),
                "-b", a.batch.toString(),
                "-ub", a.batch.toString(),
                "-ngl", "0",
                "--no-webui",
                // ★ 开启 jinja：/v1/chat/completions 需要它来套用模型自带的对话模板，
                //   否则 message 数组会被当成纯文本，工具调用也无从解析。
                "--jinja"
            ))
        }
        if (level == 0) {
            // ★ flash attention：
            //   1) 长上下文下 attention 计算更快
            //   2) 更关键 —— V cache 量化【必须】开 FA 才被接受，
            //      之前只传 --cache-type-v q8_0 会被服务端判为非法参数，
            //      于是每次都掉进「兼容模式」，KV 量化从来没真正生效过。
            c.addAll(listOf("-fa", "on"))
            // 前缀缓存复用：多轮对话时跳过重复的 system prompt 计算
            c.add("--cache-reuse"); c.add("256")
            // 打印每轮 tokens/s，方便直接从日志看速度
            c.add("--perf")
            c.addAll(listOf("--cache-type-k", "q8_0", "--cache-type-v", "q8_0"))
            if (a.lockMemory) {
                c.add("--mlock")
                c.add("--no-mmap")
            }
        }
        return c
    }

    /**
     * 启动子进程。
     * taskset 可能不存在或被 SELinux 拒绝，此时降级为直接启动。
     */
    private fun startProcess(cmd: List<String>, dir: File, mask: String?): Process {
        val env = mapOf(
            "LD_LIBRARY_PATH" to dir.absolutePath,
            "HOME" to dir.absolutePath,
            "TMPDIR" to dir.absolutePath
        )

        if (mask != null) {
            try {
                val full = mutableListOf("taskset", mask)
                full.addAll(cmd)
                val pb = ProcessBuilder(full).directory(dir).redirectErrorStream(true)
                pb.environment().putAll(env)
                return pb.start()
            } catch (_: Throwable) {
                // taskset 不可用，落到下面直接启动
            }
        }

        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(env)
        return pb.start()
    }

    /** 启动超时按模型体积放大：3B 模型冷读可能超过 3 分钟 */
    private fun startupTimeoutMs(spec: LocalModelManager.LocalModelSpec): Long {
        val sizeMb = spec.sizeBytes / 1024 / 1024
        return (60_000L + sizeMb * 200L).coerceIn(60_000L, 300_000L)
    }

    /** 启动失败是否疑似参数不被支持（只有这种才值得降级重试） */
    private fun looksLikeUnsupportedParam(log: String): Boolean {
        val l = log.lowercase()
        return listOf(
            "unrecognized", "unknown argument", "invalid argument",
            "invalid value", "cache-type", "invalid cache", "usage:",
            "error while handling argument", "failed to parse", "unexpected argument"
        ).any { l.contains(it) }
    }

    /**
     * 异步吞掉输出，避免管道写满导致子进程阻塞。
     * 保留最后 20 行：dlopen 失败的真实原因常是
     * 「CANNOT LINK EXECUTABLE ... library not found」，不含 error 字样。
     */
    private fun drainOutput(p: Process) {
        val t = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(logLines) {
                        logLines.addLast(line)
                        while (logLines.size > 20) logLines.removeFirst()
                        lastLog = logLines.joinToString("\n")
                    }
                }
            } catch (_: Throwable) {
            }
        }
        t.isDaemon = true
        t.start()
    }

    /** 上下文长度：小模型 4096 够用，大模型给 8192；再按内存保守收缩 */
    private fun ctxSizeFor(spec: LocalModelManager.LocalModelSpec, userWanted: Int): Int {
        val base = when {
            spec.paramsB.contains("0.5") -> 2048
            spec.paramsB.contains("1B") || spec.paramsB.contains("1.5") -> 4096
            else -> 8192
        }
        return userWanted.coerceIn(1024, base)
    }

// ==================== 大核识别 ====================

    /**
     * 读取各 CPU 最大频率，把频率接近最高值的核视为大核。
     * 识别失败返回空列表，调用方据此降级为不绑核。
     * 旧版写死 "taskset f0"（只对 8 核成立），6 核机型会指向不存在的核。
     */
    private fun detectBigCores(): List<Int> {
        return try {
            val cpuDir = File("/sys/devices/system/cpu")
            val cpus = cpuDir.listFiles { f -> Regex("cpu\\d+").matches(f.name) }
                ?.mapNotNull { it.name.removePrefix("cpu").toIntOrNull() }
                ?.sorted()
                ?: return emptyList()
            if (cpus.isEmpty()) return emptyList()

            val freqs = cpus.mapNotNull { cpu ->
                val f = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                if (!f.exists()) return@mapNotNull null
                val khz = f.readText().trim().toLongOrNull() ?: return@mapNotNull null
                cpu to khz
            }
            // 读不全就不猜，避免绑到错误核心
            if (freqs.size < cpus.size) return emptyList()

            val maxFreq = freqs.maxOf { it.second }
            if (maxFreq <= 0) return emptyList()
            freqs.filter { it.second >= maxFreq * 85 / 100 }.map { it.first }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 把 CPU 序号列表转成 taskset 十六进制掩码，如 [4,5,6,7] → "f0" */
    private fun maskFor(cores: List<Int>): String {
        var bits = 0L
        cores.forEach { bits = bits or (1L shl it) }
        return java.lang.Long.toHexString(bits)
    }

    // ==================== 端口 ====================

    /** 端口写死会在残留进程未退出时启动失败，改为探测空闲端口 */
    private fun pickFreePort(): Int {
        for (p in BASE_PORT until BASE_PORT + PORT_SCAN) {
            if (isPortFree(p)) return p
        }
        return BASE_PORT
    }

    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use { s ->
            s.reuseAddress = false
            s.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    } catch (_: Throwable) {
        false
    }

    /** 轮询 /health 直到服务可用 */
    private fun waitForServer(p: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) {
                if (lastLog.isEmpty()) lastLog = "进程已退出（退出码 ${p.exitValue()}）"
                return false
            }
            try {
                val conn = URL("http://127.0.0.1:$activePort/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return true
            } catch (_: Throwable) {
            }
            Thread.sleep(400)
        }
        return false
    }

    // ==================== 生成 ====================

    /**
     * 生成文本。
     *
     * @param prompt 已由 buildPrompt 拼好的完整对话模板文本
     * @param maxNewTokens 本次最多生成多少 token；0 表示按上下文自动计算
     * @param onDelta 流式增量回调
     */
    suspend fun generate(
        prompt: String,
        maxNewTokens: Int = 0,
        onDelta: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        if (process?.isAlive != true) throw IllegalStateException("本地模型未加载")

        // n_predict 不再固定 512，按剩余上下文动态给出
        val remain = if (activeCtx > 0) activeCtx - estimateTokens(prompt) - 64 else 1024
        val nPredict = if (maxNewTokens > 0) maxNewTokens else remain.coerceIn(64, 4096)

        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("n_predict", nPredict)
            put("temperature", 0.7)
            put("top_p", 0.9)
            put("top_k", 40)
            put("repeat_penalty", 1.1)
            put("stream", true)
            put("stop", JSONArray().apply {
                stopTokens().forEach { put(it) }
            })
        }

        val req = Request.Builder()
            .url("http://127.0.0.1:$activePort/completion")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(req)

        suspendCancellableCoroutine { cont ->
            // 协程取消时真正掐断 HTTP 连接，避免后台空转占满 CPU
            cont.invokeOnCancellation { runCatching { call.cancel() } }

            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string()?.take(300) ?: ""
                        throw IllegalStateException("推理失败 HTTP ${resp.code} $err")
                    }
                    val sb = StringBuilder()
                    var genTps = 0.0
                    var promptTps = 0.0
                    var genTokens = 0
                    var promptTokens = 0
                    val source: InputStream = resp.body!!.byteStream()
                    source.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!line.startsWith("data: ")) continue
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") break
                            try {
                                val obj = JSONObject(data)
                                val piece = obj.optString("content", "")
                                if (piece.isNotEmpty()) {
                                    sb.append(piece)
                                    onDelta?.invoke(piece)
                                }
                                // 服务端在最后一帧带 timings，直接拿真实速度，不用自己掐表
                                obj.optJSONObject("timings")?.let { t ->
                                    genTps = t.optDouble("predicted_per_second", genTps)
                                    promptTps = t.optDouble("prompt_per_second", promptTps)
                                    genTokens = t.optInt("predicted_n", genTokens)
                                    promptTokens = t.optInt("prompt_n", promptTokens)
                                }
                                if (obj.optBoolean("stop", false)) break
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (genTps > 0.0) {
                        lastPerf = Perf(genTps, promptTps, genTokens, promptTokens)
                    }
                    if (cont.isActive) cont.resume(sb.toString())
                }
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    /** 最近一次生成的真实速度（来自 llama-server 返回的 timings） */
    data class Perf(
        val genTps: Double = 0.0,
        val promptTps: Double = 0.0,
        val genTokens: Int = 0,
        val promptTokens: Int = 0
    )

    @Volatile
    var lastPerf: Perf = Perf()
        private set

    // ==================== 释放 ====================

    fun release() {
        releaseInternal()
        _state.value = EngineState()
    }

    /** 先温和终止，3 秒不退再强杀，避免 llama-server 残留吃内存 */
    private fun releaseInternal() {
        val p = process
        process = null
        loadedModelId = ""
        activeCtx = 0
        lastLog = ""
        synchronized(logLines) { logLines.clear() }
        if (p == null) return

        try {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(3, TimeUnit.SECONDS)
            }
        } catch (_: Throwable) {
            try {
                p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        try {
            p.inputStream.close()
        } catch (_: Throwable) {
        }
    }

    /** 读取系统可用内存（MB），失败返回 -1 */
    private fun readAvailableMemoryMb(): Long {
        return try {
            val text = File("/proc/meminfo").readText()
            val line = text.lineSequence().firstOrNull { it.startsWith("MemAvailable:") } ?: return -1
            val kb = line.replace("[^0-9]".toRegex(), "").toLongOrNull() ?: return -1
            kb / 1024
        } catch (_: Exception) {
            -1
        }
    }

// ==================== Prompt 构建 ====================

    /**
     * 把对话拼成【该模型族真正认识】的对话模板。
     *
     * v3.66 关键修复：旧版对所有模型一律输出 ChatML(<|im_start|>)，
     * 注释还写着「Qwen / Gemma / Llama 都支持」——这对 Gemma 是错的。
     * Gemma 只认 <start_of_turn>/<end_of_turn>，收到 <|im_start|> 会当成
     * 普通文本，表现为胡言乱语或复读。而 Gemma3-1B 恰好是清单推荐款。
     */
    fun buildPrompt(
        messages: List<Pair<String, String>>,
        template: LocalModelManager.PromptTemplate? = null
    ): String {
        val t = template ?: LocalModelManager.templateOf(loadedModelId)
        return when (t) {
            LocalModelManager.PromptTemplate.GEMMA -> buildGemma(messages)
            LocalModelManager.PromptTemplate.LLAMA3 -> buildLlama3(messages)
            else -> buildChatMl(messages)
        }
    }

    /** Qwen2.5 / ChatML */
    private fun buildChatMl(messages: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        val sys = messages.filter { it.first == "system" }
            .joinToString("\n\n") { it.second.trim() }
            .trim()

        if (sys.isNotEmpty()) {
            sb.append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
        }

        messages.filter { it.first != "system" }.forEach { (role, content) ->
            val text = content.trim()
            if (text.isEmpty()) return@forEach
            when (role) {
                "user" -> sb.append("<|im_start|>user\n").append(text).append("<|im_end|>\n")
                "assistant" -> sb.append("<|im_start|>assistant\n").append(text).append("<|im_end|>\n")
                // 工具结果以 user 的「工具返回」子块呈现，避免小模型把指令当成新问题
                "tool" -> sb.append("<|im_start|>user\n")
                    .append("[工具返回]\n")
                    .append(text)
                    .append("\n<|im_end|>\n")
            }
        }

        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /**
     * Gemma 2 / Gemma 3。
     * Gemma 没有独立 system 角色，按官方做法并入第一段 user 内容。
     * 结尾必须是 <start_of_turn>model\n，模型从这里开始续写。
     */
    private fun buildGemma(messages: List<Pair<String, String>>): String {
        val sb = StringBuilder("<bos>")

        val sys = messages.filter { it.first == "system" }
            .joinToString("\n\n") { it.second.trim() }
            .trim()

        var sysPending = sys.isNotEmpty()
        var firstTurn = true

        messages.filter { it.first != "system" }.forEach { (role, content) ->
            val text = content.trim()
            if (text.isEmpty()) return@forEach

            when (role) {
                "user" -> {
                    sb.append("<start_of_turn>user\n")
                    if (sysPending && firstTurn) {
                        sb.append(sys).append("\n\n")
                        sysPending = false
                    }
                    sb.append(text).append("<end_of_turn>\n")
                    firstTurn = false
                }
                "assistant" -> sb.append("<start_of_turn>model\n")
                    .append(text).append("<end_of_turn>\n")
                "tool" -> {
                    sb.append("<start_of_turn>user\n")
                    sb.append("[工具返回]\n").append(text).append("<end_of_turn>\n")
                }
            }
        }

        // 没有 user 轮次时，system 也要有落点，否则会被整个丢掉
        if (sysPending) {
            sb.append("<start_of_turn>user\n").append(sys).append("<end_of_turn>\n")
        }

        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    /** Llama 3.1 / 3.2 */
    private fun buildLlama3(messages: List<Pair<String, String>>): String {
        val sb = StringBuilder("<|begin_of_text|>")

        // 不写局部函数：本项目曾在成员函数内的局部函数上踩过
        // 「解析器吞掉后续成员」的坑，这里全部展开成显式 append。
        val sys = messages.filter { it.first == "system" }
            .joinToString("\n\n") { it.second.trim() }
            .trim()
        if (sys.isNotEmpty()) {
            sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
            sb.append(sys).append("<|eot_id|>")
        }

        messages.filter { it.first != "system" }.forEach { (role, content) ->
            val text = content.trim()
            if (text.isEmpty()) return@forEach
            when (role) {
                "user" -> {
                    sb.append("<|start_header_id|>user<|end_header_id|>\n\n")
                    sb.append(text).append("<|eot_id|>")
                }
                "assistant" -> {
                    sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
                    sb.append(text).append("<|eot_id|>")
                }
                "tool" -> {
                    sb.append("<|start_header_id|>ipython<|end_header_id|>\n\n")
                    sb.append("[工具返回]\n").append(text).append("<|eot_id|>")
                }
            }
        }

        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    /** 按当前模型的模板给停止词，避免模型把下一轮标记也写出来 */
    private fun stopTokens(): List<String> =
        when (LocalModelManager.templateOf(loadedModelId)) {
            LocalModelManager.PromptTemplate.GEMMA -> listOf(
                "<end_of_turn>", "<start_of_turn>", "<eos>"
            )
            LocalModelManager.PromptTemplate.LLAMA3 -> listOf(
                "<|eot_id|>", "<|start_header_id|>", "<|end_of_text|>"
            )
            else -> listOf(
                "<|im_end|>", "<|im_start|>", "<|endoftext|>",
                // 只在整行出现时才停，避免正文里的「用户：」被误截断
                "\n用户：", "\n助手："
            )
        }

    /**
     * 粗略估算 token 数（中文约 1 字 = 1 token，英文约 4 字符 = 1 token）。
     * 旧版末尾无条件 +1，短消息被系统性高估，导致历史被误裁。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF) cjk++
            else other++
        }
        return (cjk + other / 4).coerceAtLeast(1)
    }

    /** 按 token 预算裁剪历史，保留最近的消息 */
    fun trimHistory(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        reserveForOutput: Int = reserveForOutputTokens(maxTokens)
    ): List<Pair<String, String>> {
        val budget = (maxTokens - reserveForOutput).coerceAtLeast(256)
        val systems = messages.filter { it.first == "system" }
        val rest = messages.filter { it.first != "system" }

        var used = systems.sumOf { estimateTokens(it.second) }
        val kept = ArrayDeque<Pair<String, String>>()

        for (m in rest.asReversed()) {
            val t = estimateTokens(m.second)
            if (used + t > budget) break
            used += t
            kept.addFirst(m)
        }
        return systems + kept.toList()
    }
}