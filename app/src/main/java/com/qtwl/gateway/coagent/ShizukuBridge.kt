package com.qtwl.gateway.coagent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Shizuku 桥接。
 *
 * Shizuku 是一个由用户手动启动（无线调试或 adb）的助手服务，
 * 启动后第三方 App 可以通过它获得 UID 2000 的进程 —— 不需要 root。
 * 这是 Android 上"没有 root 却要 ADB 权限"的唯一正当途径。
 */
object ShizukuBridge {

    const val REQ_CODE = 10021
    private const val MANAGER_PKG = "moe.shizuku.manager"

    /** Binder 是否活着（Shizuku 是否在运行） */
    fun alive(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 本应用是否已获得授权 */
    fun granted(): Boolean = try {
        alive() && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Shizuku 侧的 uid（拿到就是 2000） */
    fun remoteUid(): Int = try {
        if (granted()) rikka.shizuku.Shizuku.getUid() else -1
    } catch (_: Throwable) {
        -1
    }

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(MANAGER_PKG, 0) != null
    } catch (_: Throwable) {
        false
    }

    fun status(context: Context): String = when {
        !installed(context) -> "未安装 Shizuku"
        !alive() -> "Shizuku 未运行（打开它点「启动」）"
        granted() -> "已授权 · 可提供 UID " + remoteUid()
        else -> "已运行，尚未授权给本应用"
    }

    fun requestPermission(): String = try {
        if (!alive()) return "Shizuku 没在运行，先打开 Shizuku 启动服务"
        if (granted()) return "已经授权过了"
        rikka.shizuku.Shizuku.requestPermission(REQ_CODE)
        "已发送授权请求，去 Shizuku 弹窗点「允许」"
    } catch (e: Throwable) {
        "请求失败：" + (e.message ?: e.javaClass.simpleName)
    }

    fun openManager(context: Context): String = try {
        val i = context.packageManager.getLaunchIntentForPackage(MANAGER_PKG)
            ?: return "没有找到 Shizuku，请先安装"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        "已打开 Shizuku"
    } catch (e: Throwable) {
        "打开失败：" + (e.message ?: e.javaClass.simpleName)
    }

    /**
     * 拿一个 UID 2000 的 shell 进程（Shizuku 通道）。
     *
     * Shizuku.newProcess 带 @RestrictTo(LIBRARY)，直接调编译不过，
     * 这里用反射调 —— 运行期完全可用，也是社区通行做法。
     */
    fun newShell(workdir: String): Process? = try {
        if (!granted()) {
            null
        } else {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val m = cls.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            m.invoke(null, arrayOf("sh"), null, if (workdir.isBlank()) "/" else workdir) as? Process
        }
    } catch (_: Throwable) {
        null
    }
}
