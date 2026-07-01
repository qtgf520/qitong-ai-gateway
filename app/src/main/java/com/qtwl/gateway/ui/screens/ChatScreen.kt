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

/**
 * 聊天屏幕 —— 完整的对话界面
 * 支持：会话管理、流式消息、模型选择、复制/分享/重生成/编辑重发
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(viewModel: GatewayViewModel) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentConversation?.title ?: "聊天",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selectedModel != null) {
                            Text(
                                text = "模型: ${viewModel.getDisplayModelName(selectedModel!!)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showConversationList = true }) {
                        Text("💬", fontSize = 20.sp)
                    }
                },
                actions = {
                    val streamEnabled by viewModel.streamEnabled.collectAsState()
                    Text(
                        text = if (streamEnabled) "🔊" else "🔇",
                        fontSize = 16.sp,
                        modifier = Modifier.clickable { viewModel.setStreamEnabled(!streamEnabled) }
                    )
                    IconButton(onClick = { showModelSelector = true }) {
                        Text("🤖", fontSize = 20.sp)
                    }
                    IconButton(onClick = { viewModel.createNewConversation() }) {
                        Icon(Icons.Default.Add, contentDescription = "新对话")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearChatError() }) {
                                Text("关闭", color = Error)
                            }
                        }
                    }
                }
            }

            // ★★ 编辑消息模式 ★★
            if (editingMessage != null) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "✏️ 编辑消息",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editMessageText,
                        onValueChange = { editMessageText = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        maxLines = 10,
                        label = { Text("编辑内容") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editingMessage = null }) {
                            Text("取消")
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
                            Text("保存${if (editingMessage?.role == "user") "并发送" else ""}")
                        }
                    }
                }
            } else if (currentConversation == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💬", fontSize = 64.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "选择或创建对话",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击左上角 💬 选择已有对话\n或点击 ➕ 创建新对话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (models.isEmpty()) {
                            Text(
                                text = "⚠️ 请先在「服务商」页面添加并同步模型",
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
                                cm.setPrimaryClip(ClipData.newPlainText("AI回复", message.content))
                                viewModel.showSnackbar("✅ 已复制")
                            },
                            onShare = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, message.content)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "分享"))
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
                                            text = "AI 思考中...",
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

            // ★★ 需求1：输入框上方思考小字 ★★
            if (isSending && currentConversation != null && editingMessage == null) {
                Text(
                    text = "💭 AI 正在思考... 请耐心等待",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 0.dp)
                )
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
                            text = "⚠️ 请点击 🤖 选择模型后再发送消息",
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
                                    text = if (selectedModel != null) "输入消息..."
                                    else "请先选择模型"
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
                        FilledIconButton(
                            onClick = { viewModel.sendMessage() },
                            enabled = !isSending && inputText.isNotBlank() && selectedModel != null,
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "发送",
                                tint = if (!isSending && inputText.isNotBlank() && selectedModel != null)
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
            title = { Text("对话列表", fontWeight = FontWeight.Bold) },
            text = {
                if (conversations.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无对话\n点击 ➕ 创建新对话",
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
                                        Text(conv.title, style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatTimestamp(conv.updatedAt), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { editingConversationId = conv.id; editTitle = conv.title },
                                        modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "重命名", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { viewModel.deleteConversation(conv) },
                                        modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = Error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showConversationList = false }) { Text("关闭") } }
        )
    }

    // ==================== 重命名对话框 ====================
    if (editingConversationId != null) {
        AlertDialog(
            onDismissRequest = { editingConversationId = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(value = editTitle, onValueChange = { editTitle = it },
                    label = { Text("对话标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = { editingConversationId?.let { id -> viewModel.renameConversation(id, editTitle) }; editingConversationId = null }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingConversationId = null }) { Text("取消") } }
        )
    }

    // ==================== 模型选择对话框 ====================
    if (showModelSelector) {
        AlertDialog(
            onDismissRequest = { showModelSelector = false },
            title = { Text("选择模型", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(key = "qtai-sj") {
                        val isQtAiSjSelected = selectedModel?.modelId == "qtai-sj"
                        Card(modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.selectModel(AiModel(id = -1, modelId = "qtai-sj", displayName = "🔄 自动化切换", providerId = 0, isEnabled = true))
                            showModelSelector = false
                        }, colors = CardDefaults.cardColors(containerColor = if (isQtAiSjSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = if (isQtAiSjSelected) "●" else "○",
                                    color = if (isQtAiSjSelected) Online else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("🔄 自动化切换", style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isQtAiSjSelected) FontWeight.Bold else FontWeight.Normal)
                                    Text("qtai-sj · 自动选最快模型", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                    if (enabledModels.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                Text("暂无可用模型\n请先在「模型」页面启用模型", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            confirmButton = { TextButton(onClick = { viewModel.selectModel(null); showModelSelector = false }) { Text("取消选择") } },
            dismissButton = { TextButton(onClick = { showModelSelector = false }) { Text("关闭") } }
        )
    }

    // ==================== 编辑模型别名对话框 ====================
    val editingModel by viewModel.showEditModelDialog.collectAsState()
    if (editingModel != null) {
        var aliasText by remember { mutableStateOf(editingModel!!.customAlias) }
        AlertDialog(
            onDismissRequest = { viewModel.hideEditModelAlias() },
            title = { Text("✏️ 编辑模型别名", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("原始名称: ${editingModel!!.displayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("模型 ID: ${editingModel!!.modelId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = aliasText, onValueChange = { aliasText = it },
                        label = { Text("自定义别名 (留空则使用原始名称)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { viewModel.saveModelAlias(editingModel!!, aliasText); viewModel.hideEditModelAlias() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { viewModel.hideEditModelAlias() }) { Text("取消") } }
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
                        text = message.content.ifEmpty { if (isStreaming) "..." else "(空消息)" }
                            .replace("null", "").replace("undefined", "").trim().ifEmpty { if (isStreaming) "..." else "(空消息)" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )

                    if (isStreaming && message.content.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("⏳ 生成中...", style = MaterialTheme.typography.labelSmall,
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
                        label = { Text("📋 复制", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    if (isUser) {
                        AssistChip(onClick = { onEdit(); showActions = false },
                            label = { Text("✏️ 编辑", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                        AssistChip(onClick = { onResend(); showActions = false },
                            label = { Text("🔄 重发", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    }
                    AssistChip(onClick = { onShare(); showActions = false },
                        label = { Text("📤 分享", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    if (!isUser) {
                        AssistChip(onClick = { onRegenerate(); showActions = false },
                            label = { Text("🔄 重生成", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
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