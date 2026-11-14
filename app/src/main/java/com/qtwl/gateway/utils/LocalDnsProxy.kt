package com.qtwl.gateway.utils

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 极简本地 CONNECT 代理，只为解决一个死结：
 *
 * cloudflared 是 Go 静态程序，靠 /etc/resolv.conf 找 DNS。
 * Android 没有这个文件（DNS 在 netd 里，只有 App 走 Bionic 解析器才用得到），
 * 于是 Go 退回到 127.0.0.1:53 / ::1:53，解析必然失败：
 *   dial tcp: lookup api.trycloudflare.com on [::1]:53: connection refused
 *
 * 办法：cloudflared 走 HTTPS_PROXY 时，会把 "CONNECT 主机名:端口" 原样发给代理，
 * 自己不做 DNS 查询。代理在我们自己的进程里，可以调 InetAddress.getByName，
 * 用的是 Android 的 DNS —— 死结就解开了。
 *
 * 只支持 CONNECT（即 TLS 隧道），够 cloudflared 用；不是通用代理。
 */
object LocalDnsProxy {

    private var serverSocket: ServerSocket? = null
    @Volatile
    var port: Int = 0
        private set

    fun start(): Int {
        if (serverSocket != null) return port
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        val acceptor = Thread({
            while (!ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: continue
                val th = Thread({ handle(client) }, "dns-proxy")
                th.isDaemon = true
                th.start()
            }
        }, "dns-proxy-acceptor")
        acceptor.isDaemon = true
        acceptor.start()
        return port
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val head = readHead(input) ?: run { client.close(); return }
            val first = head.lineSequence().firstOrNull().orEmpty()
            val parts = first.split(" ")
            if (parts.size < 2 || !parts[0].equals("CONNECT", true)) {
                client.getOutputStream().write("HTTP/1.1 405 Only CONNECT\r\n\r\n".toByteArray())
                client.close(); return
            }
            val target = parts[1]
            val idx = target.lastIndexOf(':')
            if (idx <= 0) { client.close(); return }
            val host = target.substring(0, idx).trim('[', ']')
            val portNum = target.substring(idx + 1).toIntOrNull() ?: 443

            // ★ 关键：在这里用 Android 的解析器解析域名
            val addr = InetAddress.getByName(host)
            val upstream = Socket()
            upstream.connect(InetSocketAddress(addr, portNum), 15000)
            upstream.soTimeout = 0
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            client.getOutputStream().flush()

            val t1 = Thread({ pump(input, upstream.getOutputStream()) }, "proxy-c2s")
            val t2 = Thread({ pump(upstream.getInputStream(), client.getOutputStream()) }, "proxy-s2c")
            t1.isDaemon = true; t2.isDaemon = true
            t1.start(); t2.start()
            t1.join()
            runCatching { upstream.close() }
            runCatching { client.close() }
        } catch (e: Throwable) {
            runCatching { client.close() }
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (sb.length < 8192) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return sb.toString()
        }
        return sb.toString()
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = from.read(buf)
                if (n <= 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { to.close() }
        }
    }
}
