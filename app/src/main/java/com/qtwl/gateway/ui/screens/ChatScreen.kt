package com.qtwl.gateway.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.ChatMessage
import com.qtwl.gateway.ui.theme.Error
import com.qtwl.gateway.ui.theme.Online
import com.qtwl.gateway.ui.viewmodel.GatewayViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.qtwl.gateway.utils.localizedText
import com.qtwl.gateway.utils.localizeRuntimeText
import com.qtwl.gateway.utils.localizeGeneratedName

/**
 * 聊天屏幕 —— 完整的对话界面
 * 支持：会话管理、流式消息、模型选择、复制/分享/重生成/编辑重发
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(viewModel: GatewayViewModel) {
    com.qtwl.gateway.utils.TranslationManager.currentLanguageFlow.collectAsState().value
    val conversations by viewModel.conversations.collectAsState()
    val currentConversation by viewModel.currentConversation.collectAsState()
    val messages by viewModel.currentMessages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val models by viewModel.models.collectAsState()
    val enabledModels by viewModel.enabledModels.collectAsState()
    val context = LocalContext.current

    var showConversationList by remember { mutableStateOf(false) }
    var showModelSelector by remember { mutableStateOf(false) }
    var editingConversationId by remember { mutableStateOf<Long?>(null) }
    var editTitle by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // ★★ 编辑用户消息状态 ★★
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editMessageText by remember { mutableStateOf("") }

    // 新消息时自动滚动到底部
    val lastMessageId = messages.lastOrNull()?.id
    LaunchedEffect(lastMessageId, isSending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // ★★ 顶部按钮行（紧贴「綦桐AI网关」标题栏下方，不再是独立 TopAppBar）★★
        Surface(
            color = MaterialTheme.colorScheme.primary,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showConversationList = true }) {
                    Text("💬", fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentConversation?.title?.let(::localizeGeneratedName) ?: localizedText("聊天", "Chat"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    if (selectedModel != null) {
                        Text(
                            text = localizedText("模型: ", "Model: ") + viewModel.getDisplayModelName(selectedModel!!),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                }
                val streamEnabled by viewModel.streamEnabled.collectAsState()
                Text(
                    text = if (streamEnabled) "🔊" else "🔇",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clickable { viewModel.setStreamEnabled(!streamEnabled) }
                        .padding(horizontal = 8.dp)
                )
                IconButton(onClick = { showModelSelector = true }) {
                    Text("🤖", fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
                IconButton(onClick = { viewModel.createNewConversation() }) {
                    Icon(Icons.Default.Add, contentDescription = localizedText("新对话", "New chat"), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        // 错误提示
        AnimatedVisibility(
            visible = chatError != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            chatError?.let { error ->
                Surface(
                    color = Error.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = localizeRuntimeText(error),
                            style = MaterialTheme.typography.bodySmall,
                            color = Error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearChatError() }) {
                            Text(localizedText("关闭", "Close"), color = Error)
                        }
                    }
                }
            }
        }

        // ★★ 编辑消息模式 ★★
        if (editingMessage != null) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                Text(
                    text = localizedText("✏️ 编辑消息", "✏️ Edit message"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editMessageText,
                    onValueChange = { editMessageText = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    maxLines = 10,
                    label = { Text(localizedText("编辑内容", "Edit content")) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingMessage = null }) {
                        Text(localizedText("取消", "Cancel"))
                    }
                    Button(
                        onClick = {
                            editingMessage?.let { msg ->
                                viewModel.updateMessageContent(msg.id, editMessageText)
                                if (msg.role == "user") {
                                    viewModel.setInputText(editMessageText)
                                    viewModel.sendMessage()
                                }
                            }
                            editingMessage = null
                        },
                        enabled = editMessageText.isNotBlank()
                    ) {
                        Text(localizedText("保存", "Save") + if (editingMessage?.role == "user") localizedText("并发送", " and send") else "")
                    }
                }
            }
        } else if (currentConversation == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💬", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = localizedText("选择或创建对话", "Select or create a conversation"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = localizedText("点击左上角 💬 选择已有对话\\n或点击 ➕ 创建新对话", "Tap 💬 at the top left to select an existing conversation\\nor tap ➕ to create a new conversation"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (models.isEmpty()) {
                        Text(
                            text = localizedText("⚠️ 请先在「服务商」页面添加并同步模型", "⚠️ Add a provider and sync models on the Providers page first"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Error
                        )
                    }
                }
            }
        } else {
            // 消息列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onCopy = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(localizedText("AI回复", "AI reply"), message.content))
                            viewModel.showSnackbar(localizedText("✅ 已复制", "✅ Copied"))
                        },
                        onShare = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, message.content)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, localizedText("分享", "Share")))
                        },
                        onRegenerate = {
                            viewModel.regenerateLastMessage()
                        },
                        onEdit = {
                            editingMessage = message
                            editMessageText = message.content
                        },
                        onResend = {
                            viewModel.setInputText(message.content)
                            viewModel.sendMessage()
                        }
                    )
                }

                // 流式加载指示器
                if (isSending && messages.lastOrNull()?.isStreaming == true) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("⏳", style = MaterialTheme.typography.titleSmall)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = localizedText("AI 思考中...", "AI is thinking..."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ★★ AI 思考中提示（紧贴输入框上方） ★★
        if (isSending && currentConversation != null && editingMessage == null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = localizedText("💭 AI 正在思考... 请耐心等待", "💭 AI is thinking... please wait"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        // 输入区
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (selectedModel == null && currentConversation != null) {
                    Text(
                        text = localizedText("⚠️ 请点击 🤖 选择模型后再发送消息", "⚠️ Tap 🤖 to select a model before sending"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { viewModel.updateInputText(it) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp, max = 120.dp),
                        placeholder = {
                            Text(
                                text = if (selectedModel != null) localizedText("输入消息...", "Type a message...")
                                else localizedText("请先选择模型", "Please select a model first")
                            )
                        },
                        enabled = !isSending,
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // ★★ 发送中显示停止按钮，空闲时显示发送按钮 ★★
                    if (isSending) {
                        FilledIconButton(
                            onClick = { viewModel.cancelSend() },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = localizedText("停止", "Stop"),
                                tint = MaterialTheme.colorScheme.onError
                            )
                        }
                    } else {
                        FilledIconButton(
                            onClick = { viewModel.sendMessage() },
                            enabled = inputText.isNotBlank() && selectedModel != null,
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = localizedText("发送", "Send"),
                                tint = if (inputText.isNotBlank() && selectedModel != null)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }
        }
    }

    // ==================== 对话列表侧边栏 ====================
    if (showConversationList) {
        AlertDialog(
            onDismissRequest = { showConversationList = false },
            title = { Text(localizedText("对话列表", "Conversation list"), fontWeight = FontWeight.Bold) },
            text = {
                if (conversations.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = localizedText("暂无对话\\n点击 ➕ 创建新对话", "No conversations yet\\nTap ➕ to create a new conversation"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(conversations, key = { it.id }) { conv ->
                            val isSelected = currentConversation?.id == conv.id
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.selectConversation(conv)
                                    showConversationList = false
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(localizeGeneratedName(conv.title), style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatTimestamp(conv.updatedAt), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { editingConversationId = conv.id; editTitle = localizeGeneratedName(conv.title) },
                                        modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = localizedText("重命名", "Rename"), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { viewModel.deleteConversation(conv) },
                                        modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = localizedText("删除", "Delete"), tint = Error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showConversationList = false }) { Text(localizedText("关闭", "Close")) } }
        )
    }

    // ==================== 重命名对话框 ====================
    if (editingConversationId != null) {
        AlertDialog(
            onDismissRequest = { editingConversationId = null },
            title = { Text(localizedText("重命名对话", "Rename conversation")) },
            text = {
                OutlinedTextField(value = editTitle, onValueChange = { editTitle = it },
                    label = { Text(localizedText("对话标题", "Conversation title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = { editingConversationId?.let { id -> viewModel.renameConversation(id, editTitle) }; editingConversationId = null }) { Text(localizedText("保存", "Save")) }
            },
            dismissButton = { TextButton(onClick = { editingConversationId = null }) { Text(localizedText("取消", "Cancel")) } }
        )
    }

    // ==================== 模型选择对话框 ====================
    if (showModelSelector) {
        AlertDialog(
            onDismissRequest = { showModelSelector = false },
            title = { Text(localizedText("选择模型", "Select model"), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(key = "qtai-sj") {
                        val isQtAiSjSelected = selectedModel?.modelId == "qtai-sj"
                        Card(modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.selectModel(AiModel(id = -1, modelId = "qtai-sj", displayName = localizedText("🔄 自动化切换", "🔄 Auto switch"), providerId = 0, isEnabled = true))
                            showModelSelector = false
                        }, colors = CardDefaults.cardColors(containerColor = if (isQtAiSjSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = if (isQtAiSjSelected) "●" else "○",
                                    color = if (isQtAiSjSelected) Online else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(localizedText("🔄 自动化切换", "🔄 Auto switch"), style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isQtAiSjSelected) FontWeight.Bold else FontWeight.Normal)
                                    Text(localizedText("qtai-sj · 自动选最快模型", "qtai-sj · automatically selects the fastest model"), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                    if (enabledModels.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                Text(localizedText("暂无可用模型\\n请先在「模型」页面启用模型", "No available models\\nEnable a model on the Models page first"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(enabledModels, key = { it.id }) { model ->
                            val isSelected = selectedModel?.id == model.id
                            Card(modifier = Modifier.fillMaxWidth().clickable { viewModel.selectModel(model); showModelSelector = false },
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = if (isSelected) "●" else "○",
                                        color = if (isSelected) Online else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(viewModel.getDisplayModelName(model), style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        Text(model.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.selectModel(null); showModelSelector = false }) { Text(localizedText("取消选择", "Clear selection")) } },
            dismissButton = { TextButton(onClick = { showModelSelector = false }) { Text(localizedText("关闭", "Close")) } }
        )
    }

    // ==================== 编辑模型别名对话框 ====================
    val editingModel by viewModel.showEditModelDialog.collectAsState()
    if (editingModel != null) {
        var aliasText by remember { mutableStateOf(editingModel!!.customAlias) }
        AlertDialog(
            onDismissRequest = { viewModel.hideEditModelAlias() },
            title = { Text(localizedText("✏️ 编辑模型别名", "✏️ Edit model alias"), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(localizedText("原始名称: ", "Original name: ") + editingModel!!.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(localizedText("模型 ID: ", "Model ID: ") + editingModel!!.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = aliasText, onValueChange = { aliasText = it },
                        label = { Text(localizedText("自定义别名 (留空则使用原始名称)", "Custom alias (leave empty to use original name)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { viewModel.saveModelAlias(editingModel!!, aliasText); viewModel.hideEditModelAlias() }) { Text(localizedText("保存", "Save")) } },
            dismissButton = { TextButton(onClick = { viewModel.hideEditModelAlias() }) { Text(localizedText("取消", "Cancel")) } }
        )
    }
}

// ============================================================
// 消息气泡组件（支持复制/编辑/重发/分享/重生成）
// ============================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onEdit: () -> Unit = {},
    onResend: () -> Unit = {}
) {
    val isUser = message.role == "user"
    val isStreaming = message.isStreaming
    var showActions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("🤖", fontSize = 16.sp) }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            // ★★ 消息气泡 + 长按复制 ★★
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 1.dp,
                modifier = Modifier.combinedClickable(
                    onClick = { showActions = !showActions },
                    onLongClick = { onCopy(); showActions = true }
                )
            ) {
                Column(modifier = Modifier.padding(
                    start = if (isUser) 16.dp else 12.dp,
                    end = if (isUser) 12.dp else 16.dp,
                    top = 10.dp, bottom = 10.dp
                )) {
                    Text(
                        text = message.content.ifEmpty { if (isStreaming) "..." else localizedText("(空消息)", "(empty message)") }
                            .replace("null", "").replace("undefined", "").trim().ifEmpty { if (isStreaming) "..." else localizedText("(空消息)", "(empty message)") },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )

                    if (isStreaming && message.content.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(localizedText("⏳ 生成中...", "⏳ Generating..."), style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (!isStreaming && message.totalTokens > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("⚡ ${message.totalTokens} tokens", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }

            // ★★ 操作按钮 ★★
            if (showActions && !isStreaming) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    AssistChip(onClick = { onCopy(); showActions = false },
                        label = { Text(localizedText("📋 复制", "📋 Copy"), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    if (isUser) {
                        AssistChip(onClick = { onEdit(); showActions = false },
                            label = { Text(localizedText("✏️ 编辑", "✏️ Edit"), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                        AssistChip(onClick = { onResend(); showActions = false },
                            label = { Text(localizedText("🔄 重发", "🔄 Resend"), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    }
                    AssistChip(onClick = { onShare(); showActions = false },
                        label = { Text(localizedText("📤 分享", "📤 Share"), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    if (!isUser) {
                        AssistChip(onClick = { onRegenerate(); showActions = false },
                            label = { Text(localizedText("🔄 重生成", "🔄 Regenerate"), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    }
                }
            }

            // 时间戳
            Spacer(modifier = Modifier.height(2.dp))
            Text(formatTimestamp(message.timestamp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 4.dp))
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("👤", fontSize = 16.sp) }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return try { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)) } catch (_: Exception) { "" }
}
