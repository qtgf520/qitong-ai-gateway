package com.qtwl.gateway.data.db

import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.ChatMessage
import com.qtwl.gateway.data.model.Conversation
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.service.GatewayForegroundService
import com.qtwl.gateway.service.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import java.security.SecureRandom

/**
 * 备份管理器 — GZIP压缩 + SHA256校验 + 可选AES-256加密
 * 格式: [header_len(4)][header_json][gzip_payload]
 */
class BackupManager(private val database: AppDatabase) {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 导出备份到文件
     */
    suspend fun exportToFile(file: File, encrypt: Boolean = false, password: String = ""): Result<File> = withContext(Dispatchers.IO) {
        try {
            val providers = database.providerDao().getAllProvidersOnce()
            val models = database.aiModelDao().getAllModelsOnce()
            val conversations = database.conversationDao().getAllConversationsOnce()
            val messages = database.chatMessageDao().getAllMessagesOnce()
            val tokenUsage = database.tokenUsageDao().getAllUsageOnce()
            val proxyListJson = GatewayForegroundService.getProxyListJson()
            val gatewayPort = GatewayForegroundService.getGatewayPort()
            val apiKeyEntriesJson = Json { ignoreUnknownKeys = true }.encodeToString(KeyManager.getAllKeys())

            val backupData = BackupData(
                providers = providers,
                models = models,
                conversations = conversations,
                messages = messages,
                tokenUsage = tokenUsage,
                proxyListJson = proxyListJson,
                gatewayPort = gatewayPort,
                apiKeyEntriesJson = apiKeyEntriesJson
            )

            val payload = json.encodeToString(BackupData.serializer(), backupData).toByteArray(Charsets.UTF_8)
            val compressed = gzipCompress(payload)

            val finalBytes = if (encrypt && password.isNotBlank()) {
                aesEncrypt(compressed, password)
            } else compressed

            val checksum = sha256(finalBytes)
            val header = BackupHeader(
                appVersion = GatewayForegroundService.getGatewayConfig("app_version", "3.16.0"),
                createdAt = System.currentTimeMillis(),
                encrypted = encrypt && password.isNotBlank(),
                checksum = checksum
            )

            FileOutputStream(file).use { fos ->
                val headerBytes = json.encodeToString(BackupHeader.serializer(), header).toByteArray()
                fos.write(intToBytes(headerBytes.size))
                fos.write(headerBytes)
                fos.write(finalBytes)
            }

            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从文件导入备份
     */
    suspend fun importFromFile(file: File, password: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bytes = file.readBytes()
            val headerLen = bytesToInt(bytes.copyOfRange(0, 4))
            val headerJson = bytes.copyOfRange(4, 4 + headerLen).toString(Charsets.UTF_8)
            val header = json.decodeFromString(BackupHeader.serializer(), headerJson)

            val payload = bytes.copyOfRange(4 + headerLen, bytes.size)
            val checksum = sha256(payload)
            if (checksum != header.checksum) {
                return@withContext Result.failure(Exception("校验和不匹配，文件可能损坏"))
            }

            val decrypted = if (header.encrypted) {
                if (password.isBlank()) return@withContext Result.failure(Exception("备份已加密，请输入密码"))
                try { aesDecrypt(payload, password) } catch (e: Exception) { return@withContext Result.failure(Exception("密码错误或解密失败")) }
            } else payload

            val jsonBytes = gzipDecompress(decrypted)
            val backupData = json.decodeFromString(BackupData.serializer(), jsonBytes.toString(Charsets.UTF_8))

            database.tokenUsageDao().clearAll()
            database.chatMessageDao().deleteAll()
            database.conversationDao().deleteAll()
            database.aiModelDao().deleteAll()
            database.providerDao().deleteAll()

            if (backupData.providers.isNotEmpty()) database.providerDao().insertAll(backupData.providers)
            if (backupData.models.isNotEmpty()) database.aiModelDao().insertAll(backupData.models)
            if (backupData.conversations.isNotEmpty()) database.conversationDao().insertAll(backupData.conversations)
            if (backupData.messages.isNotEmpty()) database.chatMessageDao().insertAll(backupData.messages)
            if (backupData.tokenUsage.isNotEmpty()) database.tokenUsageDao().insertAll(backupData.tokenUsage)

            if (backupData.proxyListJson.isNotBlank()) GatewayForegroundService.saveProxyListJson(backupData.proxyListJson)
            if (backupData.gatewayPort != 8889) GatewayForegroundService.saveGatewayPort(backupData.gatewayPort)
            if (backupData.apiKeyEntriesJson.isNotBlank()) {
                try {
                    val keys = Json { ignoreUnknownKeys = true }.decodeFromString<List<com.qtwl.gateway.service.ApiKeyEntry>>(backupData.apiKeyEntriesJson)
                    KeyManager.clearAllKeys()
                    keys.forEach { KeyManager.addKey(it.key, it.label, it.allowedModels, it.qtaiSjAccess) }
                } catch (_: Exception) { }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取备份文件目录
     */
    fun getBackupDir(): File {
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "QiTongGateway")
        dir.mkdirs()
        return dir
    }

    /**
     * 获取备份历史
     */
    fun getBackupHistory(): List<BackupMetadata> {
        val dir = getBackupDir()
        return dir.listFiles { f -> f.extension == "qtbk" }
            ?.mapNotNull { file ->
                try {
                    val bytes = file.readBytes()
                    val headerLen = bytesToInt(bytes.copyOfRange(0, 4))
                    val headerJson = bytes.copyOfRange(4, 4 + headerLen).toString(Charsets.UTF_8)
                    val header = json.decodeFromString(BackupHeader.serializer(), headerJson)
                    BackupMetadata(
                        filePath = file.absolutePath,
                        createdAt = header.createdAt,
                        appVersion = header.appVersion,
                        sizeBytes = file.length(),
                        itemCount = BackupItemCount(),
                        encrypted = header.encrypted,
                        isValid = true
                    )
                } catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun deleteBackup(file: File) = file.delete()

    fun cleanupOldBackups(retainCount: Int) {
        val history = getBackupHistory()
        if (history.size <= retainCount) return
        history.drop(retainCount).forEach { File(it.filePath).delete() }
    }

    // ── 工具方法 ──

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).readBytes()
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()
    )

    private fun bytesToInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)

    private fun aesEncrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        return salt + iv + encrypted
    }

    private fun aesDecrypt(data: ByteArray, password: String): ByteArray {
        val salt = data.copyOfRange(0, 16)
        val iv = data.copyOfRange(16, 28)
        val encrypted = data.copyOfRange(28, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec)
    }

    // ── 数据类 ──

    @kotlinx.serialization.Serializable
    data class BackupHeader(
        val appVersion: String,
        val createdAt: Long,
        val encrypted: Boolean,
        val checksum: String
    )

    data class BackupMetadata(
        val filePath: String,
        val createdAt: Long,
        val appVersion: String,
        val sizeBytes: Long,
        val itemCount: BackupItemCount,
        val encrypted: Boolean,
        val isValid: Boolean
    ) {
        val fileName: String get() = File(filePath).name
        val sizeReadable: String get() = formatFileSize(sizeBytes)

        companion object {
            private fun formatFileSize(bytes: Long): String {
                if (bytes < 1024) return "$bytes B"
                if (bytes < 1024 * 1024) return "${"%.1f".format(bytes / 1024.0)} KB"
                return "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            }
        }
        val createdAtReadable: String get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(createdAt))
    }

    data class BackupItemCount(val providers: Int = 0, val models: Int = 0, val chats: Int = 0, val messages: Int = 0, val tokenUsage: Int = 0)

    }
