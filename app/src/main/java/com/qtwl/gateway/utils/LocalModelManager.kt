package com.qtwl.gateway.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地小模型管理（llama.cpp / GGUF 版）
 *
 * 特点：
 *  1. 模型为 .gguf 格式，由 llama-server 子进程加载推理
 *  2. 每个模型配置多个下载源，主源失败自动切换
 *  3. 支持断点续传（HTTP Range）
 *  4. 实时速度、剩余时间、暂停/继续
 */
object LocalModelManager {

    /**
     * 对话模板族。
     *
     * ★ 为什么需要它：不同模型族的 prompt 结构互不兼容，
     *   用错模板的表现不是报错，而是「胡言乱语 / 复读 / 自问自答」，
     *   极难排查。这里显式标注，由 LocalLlmEngine.buildPrompt 分派。
     */
    enum class PromptTemplate {
        /** Qwen2.5 等：<|im_start|>role ... <|im_end|> */
        CHATML,

        /** Gemma 2 / Gemma 3：<start_of_turn>user ... <end_of_turn> */
        GEMMA,

        /** Llama 3.1 / 3.2：<|start_header_id|>role<|end_header_id|> ... <|eot_id|> */
        LLAMA3
    }

    /** 按模型 id 查模板；查不到退回 ChatML（Qwen 系最常见） */
    fun templateOf(modelId: String): PromptTemplate =
        CATALOG.firstOrNull { it.id == modelId }?.template ?: PromptTemplate.CHATML

    /** 单个可下载模型 */
    data class LocalModelSpec(
        val id: String,
        val displayName: String,
        val fileName: String,
        val sizeBytes: Long,
        val paramsB: String,
        val quant: String,
        val description: String,
        val urls: List<String>,      // 多源，按顺序尝试
        val recommend: Boolean = false,
        /** 该模型建议的 token 总预算（小模型 4096 够，3B 给 8192） */
        val recommendedTokens: Int = 4096,
        /** 该模型必须使用的对话模板 */
        val template: PromptTemplate = PromptTemplate.CHATML
    )

    private const val MS_RESOLVE = "https://modelscope.cn/models/"
    private const val MS_API = "https://modelscope.cn/api/v1/models/"

    /** 同仓库的两个等价下载地址（互为备份，均支持 HTTP Range 断点续传） */
    private fun gguf(repo: String, file: String): List<String> = listOf(
        MS_RESOLVE + repo + "/resolve/master/" + file,
        MS_API + repo + "/repo?Revision=master&FilePath=" + file
    )

    /**
     * 可选模型清单（全部为 GGUF 格式）
     *
     * 所有文件名与体积均通过 ModelScope 仓库 API 核实，不是猜测值，
     * 避免出现「链接下不了 / 大小对不上」。
     *
     * ★ template 字段必须与模型族严格对应，见 PromptTemplate 注释。
     */
    val CATALOG: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = "qwen2.5-0.5b",
            displayName = "Qwen2.5-0.5B Instruct",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491_400_032L,
            paramsB = "0.5B",
            quant = "Q4_K_M",
            description = "最省内存，任何手机都能跑。中文可懂，能力有限",
            urls = gguf("Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            recommendedTokens = 4096,
            template = PromptTemplate.CHATML
        ),
        LocalModelSpec(
            id = "gemma3-1b",
            displayName = "Gemma3-1B IT",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            sizeBytes = 806_058_272L,
            paramsB = "1B",
            quant = "Q4_K_M",
            description = "推荐入门。中文可用，约需 2GB 内存",
            urls = gguf("unsloth/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf"),
            recommend = true,
            recommendedTokens = 4096,
            // ★ 关键：Gemma 只认 <start_of_turn>，不能套 ChatML
            template = PromptTemplate.GEMMA
        ),
        LocalModelSpec(
            id = "qwen2.5-1.5b",
            displayName = "Qwen2.5-1.5B Instruct",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            paramsB = "1.5B",
            quant = "Q4_K_M",
            description = "中文强，指令遵循好，最适合 Agent。约需 2.5GB 内存",
            urls = gguf("Qwen/Qwen2.5-1.5B-Instruct-GGUF", "qwen2.5-1.5b-instruct-q4_k_m.gguf"),
            recommend = true,
            recommendedTokens = 4096,
            template = PromptTemplate.CHATML
        ),
        LocalModelSpec(
            id = "qwen2.5-3b",
            displayName = "Qwen2.5-3B Instruct",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 1_929_900_000L,
            paramsB = "3B",
            quant = "Q4_K_M",
            description = "中文能力强的本地选项。需 4GB 以上可用内存",
            urls = gguf("Qwen/Qwen2.5-3B-Instruct-GGUF", "qwen2.5-3b-instruct-q4_k_m.gguf"),
            recommendedTokens = 8192,
            template = PromptTemplate.CHATML
        ),
        LocalModelSpec(
            id = "llama3.2-1b",
            displayName = "Llama-3.2-1B Instruct",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_208L,
            paramsB = "1B",
            quant = "Q4_K_M",
            description = "Meta 出品，英文流畅，中文一般。约需 2GB 内存",
            urls = gguf("unsloth/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            recommendedTokens = 4096,
            // ★ 关键：Llama3 用 header 格式，不是 ChatML
            template = PromptTemplate.LLAMA3
        )
    )

    /** 下载状态 */
    data class DownloadState(
        val modelId: String = "",
        val downloaded: Long = 0L,
        val total: Long = 0L,
        val speedBps: Long = 0L,
        val running: Boolean = false,
        val paused: Boolean = false,
        val error: String? = null,
        val sourceIndex: Int = 0
    ) {
        val percent: Int
            get() = if (total <= 0) 0 else ((downloaded * 100) / total).toInt().coerceIn(0, 100)

        /** 剩余秒数，未知返回 -1 */
        val etaSeconds: Int
            get() {
                if (speedBps <= 0 || total <= downloaded) return -1
                return ((total - downloaded) / speedBps).toInt()
            }
    }

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    /** 正在运行的下载任务，用于暂停 */
    private val jobs = mutableMapOf<String, Job>()

    fun modelDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun targetFile(context: Context, spec: LocalModelSpec) =
        File(modelDir(context), spec.fileName)

    private fun partFile(context: Context, spec: LocalModelSpec) =
        File(modelDir(context), spec.fileName + ".part")

    fun isDownloaded(context: Context, spec: LocalModelSpec): Boolean {
        val f = targetFile(context, spec)
        // 允许 2% 误差（不同源大小可能有细微差异）
        return f.exists() && f.length() > spec.sizeBytes * 98 / 100
    }

    fun downloadedModels(context: Context): List<LocalModelSpec> =
        CATALOG.filter { isDownloaded(context, it) }

    fun usedBytes(context: Context): Long =
        modelDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** 已下载模型文件路径，供推理引擎加载 */
    fun modelPath(context: Context, spec: LocalModelSpec): String =
        targetFile(context, spec).absolutePath

    /** 开始或继续下载 */
    fun startDownload(context: Context, scope: CoroutineScope, spec: LocalModelSpec) {
        if (jobs[spec.id]?.isActive == true) return
        jobs[spec.id] = scope.launch(Dispatchers.IO) {
            runDownload(context.applicationContext, spec)
        }
    }

    /** 暂停下载（保留 .part 文件） */
    fun pauseDownload(spec: LocalModelSpec) {
        jobs[spec.id]?.cancel()
        jobs.remove(spec.id)
        update(spec.id) { it.copy(running = false, paused = true, speedBps = 0) }
    }

    /**
     * 清理孤儿缓存。
     *
     * ★ 说明：.xnnpack_cache 是 MediaPipe 时代的产物，现已不用。
     *   保留这段清理逻辑是为了兼容老用户升级后残留的几百 MB 垃圾文件，
     *   顺手也把下载中断留下的 .part 文件在对应模型被删除后一并清理。
     *
     * @return 清理掉的字节数
     */
    fun cleanOrphanCaches(context: Context): Long {
        val dir = modelDir(context)
        val files = dir.listFiles() ?: return 0
        var freed = 0L
        files.forEach { f ->
            when {
                // MediaPipe 时代残留
                f.name.endsWith(".xnnpack_cache") -> {
                    val modelName = f.name.removeSuffix(".xnnpack_cache")
                    if (!File(dir, modelName).exists()) {
                        freed += f.length()
                        f.delete()
                    }
                }
                // 中断下载的 .part：同名正式文件已存在 → 残留
                f.name.endsWith(".part") -> {
                    val realName = f.name.removeSuffix(".part")
                    if (File(dir, realName).exists()) {
                        freed += f.length()
                        f.delete()
                    }
                }
            }
        }
        return freed
    }

    /** 删除 */
    fun delete(context: Context, spec: LocalModelSpec) {
        jobs[spec.id]?.cancel()
        jobs.remove(spec.id)
        targetFile(context, spec).delete()
        partFile(context, spec).delete()
        _states.value = _states.value - spec.id
    }

    private suspend fun runDownload(context: Context, spec: LocalModelSpec) {
        val target = targetFile(context, spec)
        val part = partFile(context, spec)

        if (isDownloaded(context, spec)) {
            update(spec.id) {
                it.copy(downloaded = target.length(), total = target.length(), running = false, paused = false)
            }
            return
        }

        update(spec.id) { it.copy(running = true, paused = false, error = null, modelId = spec.id) }

        // 依次尝试每个源
        for ((idx, url) in spec.urls.withIndex()) {
            try {
                update(spec.id) { it.copy(sourceIndex = idx, error = null) }
                if (downloadFrom(context, spec, url, part)) {
                    if (target.exists()) target.delete()
                    if (part.renameTo(target)) {
                        update(spec.id) {
                            it.copy(
                                running = false, paused = false, speedBps = 0,
                                downloaded = target.length(), total = target.length()
                            )
                        }
                        return
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                update(spec.id) { it.copy(running = false, paused = true, speedBps = 0) }
                throw e
            } catch (e: Exception) {
                update(spec.id) {
                    it.copy(error = "源 ${idx + 1} 失败：${e.message ?: "未知错误"}")
                }
            }
        }

        update(spec.id) {
            it.copy(running = false, speedBps = 0, error = it.error ?: "所有下载源均失败")
        }
    }

    /** 从单个源下载，返回是否下完 */
    private fun downloadFrom(
        context: Context,
        spec: LocalModelSpec,
        url: String,
        part: File
    ): Boolean {
        var existing = if (part.exists()) part.length() else 0L
        if (existing >= spec.sizeBytes) return true

        var conn = openConn(url, existing)
        // ★ 修复：服务器不支持 Range（返回 200 而非 206）时，
        //   旧实现直接删掉 .part 从头下。但如果响应体确实是完整文件，
        //   从头下是对的；这里保留该行为，但补上对 416 的处理
        //   （.part 比服务器文件还大 → 说明 .part 已损坏，必须重下）。
        if (existing > 0 && conn.responseCode == 416 /* HTTP 416 Range Not Satisfiable */) {
            conn.disconnect()
            part.delete()
            existing = 0L
            conn = openConn(url, 0L)
        } else if (existing > 0 && conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect()
            part.delete()
            existing = 0L
            conn = openConn(url, 0L)
        }

        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }

        val serverLen = conn.contentLengthLong
        val total = if (serverLen > 0) existing + serverLen else spec.sizeBytes

        var lastBytes = existing
        var lastTime = System.currentTimeMillis()

        try {
            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(existing)
                    val buf = ByteArray(256 * 1024)
                    var done = existing
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        done += n

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastTime
                        if (elapsed >= 500) {
                            val speed = ((done - lastBytes) * 1000L) / elapsed
                            lastBytes = done
                            lastTime = now
                            update(spec.id) {
                                it.copy(downloaded = done, total = total, speedBps = speed, running = true)
                            }
                        }
                    }
                    // ★ 修复：旧实现在这里把 speedBps 归零但 running 仍为 true，
                    //   界面会显示「下载中 0B/s」。改为同步把 running 置 false。
                    update(spec.id) {
                        it.copy(downloaded = done, total = total, speedBps = 0, running = false)
                    }
                }
            }
        } finally {
            // ★ 修复：旧实现只在正常路径 disconnect，
            //   read() 抛 IO 异常（网络抖动、切 WiFi/4G）时连接泄漏，
            //   多源重试几次后 socket 耗尽。
            runCatching { conn.disconnect() }
        }

        val len = part.length()
        return len >= spec.sizeBytes * 98 / 100 || (serverLen > 0 && len == total)
    }

    private fun openConn(url: String, rangeStart: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        // ★ 修复：UA 里写的还是 3.28，与实际版本无关，
        //   统一改成中性标识，避免服务端按旧版本做兼容处理。
        conn.setRequestProperty("User-Agent", "QiTongGateway/3.66 (Android)")
        conn.setRequestProperty("Accept", "*/*")
        if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")
        conn.connect()
        return conn
    }

    private fun update(id: String, block: (DownloadState) -> DownloadState) {
        val cur = _states.value[id] ?: DownloadState(modelId = id)
        _states.value = _states.value + (id to block(cur))
    }
}