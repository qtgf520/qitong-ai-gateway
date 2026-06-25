package com.qtwl.gateway.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * 支持 RFC 1929 用户名/密码认证的 SOCKS5 Socket 工厂
 *
 * OkHttp 调用流程：createSocket() → socket.connect(targetAddr)
 * 通过自定义 Socks5Socket 在 connect() 中拦截，
 * 先连代理服务器，再执行 SOCKS5 握手（认证 + CONNECT）
 */
class Socks5SocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val username: String = "",
    private val password: String = ""
) : SocketFactory() {

    override fun createSocket(): Socket = Socks5Socket()

    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socks5Socket()
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
        val socket = Socks5Socket()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket {
        val socket = Socks5Socket()
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
        val socket = Socks5Socket()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    /**
     * 自定义 Socket — 在 connect() 时拦截，先连代理再执行 SOCKS5 握手
     */
    private inner class Socks5Socket : Socket() {

        @Volatile
        private var handshakeDone = false

        @Volatile
        private var connectingToProxy = false

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (endpoint !is InetSocketAddress) {
                super.connect(endpoint, timeout)
                return
            }
            // 已握手完成 → 直接透传
            if (handshakeDone) {
                super.connect(endpoint, timeout)
                return
            }
            // 正在连代理服务器 → 透传（避免递归）
            if (connectingToProxy) {
                super.connect(endpoint, timeout)
                return
            }

            val targetHost = endpoint.hostString
            val targetPort = endpoint.port

            // 先连到 SOCKS5 代理服务器（设标志位防止递归）
            connectingToProxy = true
            try {
                super.connect(InetSocketAddress(proxyHost, proxyPort), timeout)
            } finally {
                connectingToProxy = false
            }

            try {
                val inputStream = getInputStream()
                val outputStream = getOutputStream()

                // SOCKS5 握手
                socks5Handshake(inputStream, outputStream, targetHost, targetPort)
                handshakeDone = true
            } catch (e: Exception) {
                try { close() } catch (_: Exception) {}
                throw ConnectException(
                    "SOCKS5代理失败 [${proxyHost}:${proxyPort}→${targetHost}:${targetPort}]: ${e.message}"
                )
            }
        }

        private fun socks5Handshake(input: InputStream, output: OutputStream, targetHost: String, targetPort: Int) {
            val hasAuth = username.isNotBlank()

            // 1) 认证方法协商
            val methods = if (hasAuth) {
                byteArrayOf(0x05, 0x02, 0x00, 0x02.toByte())
            } else {
                byteArrayOf(0x05, 0x01, 0x00)
            }
            output.write(methods)
            output.flush()

            val resp = ByteArray(2)
            readFully(input, resp)

            if (resp[0].toInt() != 0x05) {
                throw IOException("代理版本错误: ${resp[0].toInt()}")
            }

            val chosenMethod = resp[1].toInt() and 0xFF
            when (chosenMethod) {
                0xFF -> throw IOException("代理拒绝所有认证方法")
                0x02 -> {
                    // RFC 1929 用户名/密码认证
                    if (!hasAuth) throw IOException("代理要求认证但未提供用户名密码")
                    doAuth(input, output)
                }
                0x00 -> { /* 无认证，继续 */ }
                else -> throw IOException("代理选择未知认证方法: $chosenMethod")
            }

            // 2) CONNECT
            val bos = ByteArrayOutputStream()
            bos.write(0x05) // VER
            bos.write(0x01) // CMD CONNECT
            bos.write(0x00) // RSV

            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            if (isIPv4(targetHost)) {
                bos.write(0x01) // ATYP IPv4
                targetHost.split(".").forEach { bos.write(it.toInt() and 0xFF) }
            } else {
                bos.write(0x03) // ATYP DOMAINNAME
                bos.write(hostBytes.size)
                bos.write(hostBytes)
            }
            bos.write((targetPort shr 8) and 0xFF)
            bos.write(targetPort and 0xFF)

            output.write(bos.toByteArray())
            output.flush()

            val header = ByteArray(4)
            readFully(input, header)

            if (header[0].toInt() != 0x05) {
                throw IOException("CONNECT响应版本错误: ${header[0].toInt()}")
            }

            val rep = header[1].toInt() and 0xFF
            if (rep != 0x00) {
                val msg = when (rep) {
                    0x01 -> "通用服务器故障"
                    0x02 -> "连接不被允许"
                    0x03 -> "网络不可达"
                    0x04 -> "主机不可达"
                    0x05 -> "连接被拒绝"
                    0x06 -> "TTL超时"
                    0x07 -> "不支持的命令"
                    0x08 -> "不支持的地址类型"
                    else -> "未知错误($rep)"
                }
                throw IOException("代理拒绝连接 $targetHost:$targetPort → $msg")
            }

            // 读取绑定地址（忽略）
            val atyp = header[3].toInt() and 0xFF
            when (atyp) {
                0x01 -> readFully(input, ByteArray(4))
                0x03 -> { val len = input.read().toInt() and 0xFF; readFully(input, ByteArray(len)) }
                0x04 -> readFully(input, ByteArray(16))
            }
            readFully(input, ByteArray(2)) // 端口
        }

        private fun doAuth(input: InputStream, output: OutputStream) {
            val uBytes = username.toByteArray(Charsets.UTF_8)
            val pBytes = password.toByteArray(Charsets.UTF_8)
            val bos = ByteArrayOutputStream()
            bos.write(0x01) // 认证版本
            bos.write(uBytes.size)
            bos.write(uBytes)
            bos.write(pBytes.size)
            bos.write(pBytes)
            output.write(bos.toByteArray())
            output.flush()

            val resp = ByteArray(2)
            readFully(input, resp)
            if (resp[0].toInt() != 0x01) throw IOException("认证版本错误")
            if (resp[1].toInt() != 0x00) throw IOException("认证失败，请检查用户名密码")
        }

        private fun readFully(input: InputStream, buf: ByteArray) {
            var offset = 0
            while (offset < buf.size) {
                val n = input.read(buf, offset, buf.size - offset)
                if (n < 0) throw IOException("连接意外关闭")
                offset += n
            }
        }

        private fun isIPv4(host: String): Boolean {
            val parts = host.split(".")
            return parts.size == 4 && parts.all {
                it.toIntOrNull()?.let { n -> n in 0..255 } == true
            }
        }
    }
}