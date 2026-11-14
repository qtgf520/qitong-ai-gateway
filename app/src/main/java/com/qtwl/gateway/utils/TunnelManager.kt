package com.qtwl.gateway.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 公网映射（内网穿透）。
 *
 * 用 cloudflared 的 quick tunnel：不需要注册账号、不需要域名。
 * 执行 cloudflared tunnel --url http://127.0.0.1:<网关端口> 之后，
 * 它会自己建一条出站隧道，并打印一个 https://xxx.trycloudflare.com 的地址。
 *
 * 二进制以 libcloudflared.so 的名字放进 jniLibs：
 * 安装时系统会把它解压到 nativeLibraryDir 并给 0755 权限，
 * 这样才可以直接当可执行文件拉起来（和 llama-server 同一个套路）。
 *
 * 注意：quick tunnel 地址是一次性的，进程重启就换，必须提前告知使用者。
 */
object TunnelManager {

    private const val BIN = "libcloudflared.so"

    data class State(
        val running: Boolean = false,
        val starting: Boolean = false,
        val url: String? = null,
        val error: String? = null,
        val log: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var process: Process? = null

    private val URL_REGEX = Regex("https://[a-zA-Z0-9-]+[.]trycloudflare[.]com")

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, BIN)

    fun available(context: Context): Boolean = binary(context).exists()

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * ★ 修复「lookup api.trycloudflare.com on [::1]:53 connection refused」★★★
     *
     * cloudflared 是 Go 写的静态程序，它靠读 /etc/resolv.conf 找 DNS。
     * 但 Android 根本没有这个文件 —— 系统的 DNS 在 netd 里，只有 App 通过
     * Bionic 的解析器才能用到。Go 找不到文件就退回到 127.0.0.1 / ::1:53，
     * 那里没有 DNS 服务，于是所有域名都解析失败，quick tunnel 第一步就挂。
     *
     * 解法：把系统当前的 DNS 服务器地址写进 /etc/resolv.conf。
     * 这一步需要 root（/etc 是系统分区），没有 root 只能放弃 cloudflared。
     */
    private fun ensureResolvConf(context: Context): String? {
        val f = java.io.File("/etc/resolv.conf")
        if (f.exists() && f.canRead()) {
            val content = runCatching { f.readText() }.getOrDefault("")
            if (content.contains("nameserver")) return null
        }
        // 拿系统 DNS
        val dnsList = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            cm.activeNetwork?.let { n ->
                cm.getLinkProperties(n)?.dnsServers?.mapNotNull { it.hostAddress }
            }
        }.getOrNull().orEmpty()

        val servers = if (dnsList.isEmpty()) listOf("223.5.5.5", "1.1.1.1") else dnsList.take(3)
        val body = servers.joinToString("") { "nameserver " + it + "\n" }

        // 用 root 写
        val ok = runCatching {
            val p = ProcessBuilder("su", "-c", "printf '" + body.replace("'", "") + "' > /etc/resolv.conf")
                .redirectErrorStream(true).start()
            p.waitFor() == 0
        }.getOrDefault(false)
        if (ok) return null
        return "无法写入 /etc/resolv.conf（需要 root）。cloudflared 是 Go 程序，" +
                "它只能通过这个文件找 DNS，而 Android 没有该文件，" +
                "所以隧道在当前设备上无法启动。"
    }

    fun start(context: Context, port: Int) {
        if (isRunning()) return
        // ★★★ 稳定性修复 ★★★
        //   下面要拉起 su 进程并 waitFor()，还可能要等 socket 绑定，
        //   这些都会阻塞。原来直接在调用处（设置页开关的回调 = 主线程）执行，
        //   设备上没装 su 时 ProcessBuilder 抛异常尚可，但如果 su 存在而卡在授权弹窗，
        //   主线程会一直等 → 界面冻住甚至 ANR。
        //   现在整段逻辑丢到后台线程，状态通过 StateFlow 回传，UI 不受影响。
        _state.value = State(starting = true)
        val worker = Thread({ startInternal(context.applicationContext, port) }, "tunnel-start")
        worker.isDaemon = true
        worker.start()
    }

    private fun startInternal(context: Context, port: Int) {
        if (isRunning()) return
        // 方案一：有 root 就补 /etc/resolv.conf（最干净）
        val dnsProblem = ensureResolvConf(context)
        // 方案二：没有 root 就起本地代理，让 cloudflared 把域名交给 App 解析
        val proxyPort = if (dnsProblem != null) LocalDnsProxy.start() else 0
        if (dnsProblem != null && proxyPort == 0) {
            _state.value = State(error = dnsProblem)
            return
        }
        val bin = binary(context)
        if (!bin.exists()) {
            _state.value = State(error = "安装包里没有 cloudflared 组件（libcloudflared.so 缺失）")
            return
        }
        val thread = Thread({ runTunnel(bin, port, proxyPort) }, "cloudflared-tunnel")
        thread.isDaemon = true
        thread.start()
    }

    private fun runTunnel(bin: File, port: Int, proxyPort: Int) {
        val logBuf = StringBuilder()
        try {
            val cmd = mutableListOf(
                bin.absolutePath,
                "tunnel",
                "--no-autoupdate",
                "--url", "http://127.0.0.1:" + port
            )
            val extra = mutableMapOf<String, String>()
            if (proxyPort > 0) {
                // 走本地代理：域名解析交给 App，cloudflared 自己完全不做 DNS
                extra["HTTPS_PROXY"] = "http://127.0.0.1:" + proxyPort
                extra["HTTP_PROXY"] = "http://127.0.0.1:" + proxyPort
                // 强制 http2（TCP），避免 QUIC/UDP 绕开代理
                cmd.add("--protocol")
                cmd.add("http2")
            }
            val pb = ProcessBuilder(cmd)
            pb.directory(bin.parentFile)
            pb.redirectErrorStream(true)
            pb.environment().putAll(extra)
            val p = pb.start()
            process = p

            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    if (logBuf.length < 4000) logBuf.append(line).append(10.toChar())
                    val found = URL_REGEX.find(line)?.value
                    if (found != null && _state.value.url != found) {
                        _state.value = State(
                            running = true,
                            starting = false,
                            url = found,
                            log = logBuf.toString()
                        )
                    }
                    line = reader.readLine()
                }
            }
            val exit = runCatching { p.waitFor() }.getOrDefault(-1)
            process = null
            _state.value = State(
                running = false,
                starting = false,
                url = null,
                error = if (exit == 0) null else "隧道进程已退出（code=" + exit + "）" + logBuf.toString().takeLast(400),
                log = logBuf.toString()
            )
        } catch (e: Throwable) {
            process = null
            _state.value = State(
                running = false,
                starting = false,
                url = null,
                error = "启动隧道失败：" + (e.message ?: e.javaClass.simpleName) + logBuf.toString().takeLast(300)
            )
        }
    }

    fun stop() {
        runCatching { process?.destroyForcibly() }
        process = null
        LocalDnsProxy.stop()
        _state.value = State()
    }
}
