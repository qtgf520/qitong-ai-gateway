package com.qtwl.gateway.utils

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 免账号、免 root 的内网穿透：SSH 反向隧道。
 *
 * 为什么不用 cloudflared：它是 Go 静态程序，靠 /etc/resolv.conf 找 DNS，
 * Android 没有这个文件 → 连 api.trycloudflare.com 都解析不了，直接退出。
 * 而且它是独立进程，出问题只能在日志里看。
 *
 * SSH 隧道完全在 App 进程内跑：JSch 是纯 Java，域名用 Android 自己的解析器，
 * 既不需要 root，也不需要装任何二进制，还不需要注册账号。
 *
 * 用的是 localhost.run / pinggy 这类公开的免费 SSH 跳板：
 *   ssh -R 80:localhost:<网关端口> nokey@localhost.run
 * 连上之后对方会在会话里打印一个 https 地址，我们把它解析出来给用户。
 */
object SshTunnel {

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
    private var session: Session? = null

    private val URL_REGEX = Regex("https://[a-zA-Z0-9._-]+")

    /** 依次尝试的免费跳板（都支持匿名，无需注册） */
    private data class Relay(val host: String, val port: Int, val user: String, val remotePort: Int)

    private val relays = listOf(
        Relay("localhost.run", 22, "nokey", 80),
        Relay("a.pinggy.io", 443, "a", 0),
        Relay("serveo.net", 22, "serveo", 80)
    )

    fun isRunning(): Boolean = session?.isConnected == true

    fun start(context: Context, localPort: Int) {
        if (isRunning()) return
        _state.value = State(starting = true)
        val th = Thread({ connect(localPort) }, "ssh-tunnel")
        th.isDaemon = true
        th.start()
    }

    private fun connect(localPort: Int) {
        val logBuf = StringBuilder()
        var lastError = ""

        for (relay in relays) {
            try {
                logBuf.append("尝试 ").append(relay.host).append('\n')
                val jsch = JSch()
                val s = jsch.getSession(relay.user, relay.host, relay.port)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "none,publickey,password,keyboard-interactive")
                s.serverAliveInterval = 20000
                s.serverAliveCountMax = 3
                s.connect(20000)

                // 反向端口转发：远端端口 → 本机网关
                s.setPortForwardingR(relay.remotePort, "127.0.0.1", localPort)
                session = s

                // 打开一个 shell 通道，用来读对方打印的公网地址
                val channel = s.openChannel("shell")
                val out = channel.outputStream
                val reader = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
                channel.connect(15000)

                val readerThread = Thread({
                    try {
                        var line: String? = reader.readLine()
                        while (line != null) {
                            if (logBuf.length < 4000) logBuf.append(line).append(10.toChar())
                            val hit = URL_REGEX.find(line)?.value
                            if (hit != null && _state.value.url != hit) {
                                _state.value = State(
                                    running = true,
                                    starting = false,
                                    url = hit,
                                    log = logBuf.toString()
                                )
                            }
                            line = reader.readLine()
                        }
                    } catch (_: Throwable) {
                    }
                }, "ssh-reader")
                readerThread.isDaemon = true
                readerThread.start()

                // 保活：连接断开就把状态置错
                val monitor = Thread({
                    while (s.isConnected) {
                        try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
                    }
                    if (_state.value.running) {
                        _state.value = State(
                            running = false,
                            starting = false,
                            error = "隧道连接已断开",
                            log = logBuf.toString()
                        )
                    }
                }, "ssh-monitor")
                monitor.isDaemon = true
                monitor.start()

                // 连接成功就返回，不再尝试后面的跳板
                return
            } catch (e: Throwable) {
                lastError = relay.host + "：" + (e.message ?: e.javaClass.simpleName)
                logBuf.append(lastError).append('\n')
                runCatching { session?.disconnect() }
                session = null
            }
        }

        _state.value = State(
            running = false,
            starting = false,
            error = "所有免账号跳板都连不上\n" + lastError,
            log = logBuf.toString()
        )
    }

    fun stop() {
        runCatching { session?.disconnect() }
        session = null
        _state.value = State()
    }
}
