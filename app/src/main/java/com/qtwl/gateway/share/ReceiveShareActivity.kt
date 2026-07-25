package com.qtwl.gateway.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.qtwl.gateway.service.GroupChatManager

/**
 * 接收外部转发消息（ACTION_SEND）
 * 支持从其他App转发文本到网关
 */
class ReceiveShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> {
                // 普通文本转发
                intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // 多条文本转发
                intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.joinToString("\n")
            }
            else -> null
        }

        if (sharedText.isNullOrBlank()) {
            finish()
            return
        }

        // 转发消息直接进入群聊模式
        if (GroupChatManager.isEnabled()) {
            // TODO: 发送到群聊引擎处理
            // 暂时记录日志
            android.util.Log.i("ReceiveShare", "收到转发消息: ${sharedText.take(100)}")
        }

        // 关闭透明Activity
        finish()
    }

    companion object {
        /**
         * 创建转发Intent
         */
        fun createIntent(text: String): Intent {
            return Intent(ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        }

        private const val ACTION_SEND = "android.intent.action.SEND"
    }
}