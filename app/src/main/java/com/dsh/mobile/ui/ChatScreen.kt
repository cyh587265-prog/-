package com.dsh.mobile.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
data class ChatScreenArgs(
    val workspaceId: String,
    val sessionId: String
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    args: ChatScreenArgs,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(args.workspaceId, args.sessionId)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("聊天") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadModels() }) {
                        Icon(Icons.Default.Settings, contentDescription = "模型设置")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 模型选择 Chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.currentModel != null) {
                    AssistChip(
                        onClick = { viewModel.loadModels() },
                        label = { Text("当前模型: ${uiState.currentModel}") },
                        modifier = Modifier.wrapContentWidth()
                    )
                }
                if (uiState.isLoadingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            // 消息列表
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (uiState.messages.isEmpty() && !uiState.isLoadingHistory) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("暂无消息", fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("发送一条消息开始对话", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = false
                    ) {
                        if (uiState.hasMoreHistory) {
                            item {
                                Button(
                                    onClick = { viewModel.loadOlder() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    enabled = !uiState.isLoadingHistory
                                ) {
                                    if (uiState.isLoadingHistory) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text("加载更早的消息")
                                    }
                                }
                            }
                        }
                        items(
                            items = uiState.messages,
                            key = { it.id }
                        ) { message ->
                            ChatMessageItem(
                                message = message,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (uiState.isSending) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "思考中...",
                                        modifier = Modifier.padding(start = 8.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 输入区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = uiState.inputText,
                    onValueChange = { viewModel.updateInputText(it) },
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && !event.isShiftPressed) {
                                if (uiState.inputText.isNotBlank()) {
                                    viewModel.sendMessage(uiState.inputText)
                                    focusManager.clearFocus()
                                }
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text("输入消息...") },
                    enabled = !uiState.isSending,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (uiState.inputText.isNotBlank()) {
                                viewModel.sendMessage(uiState.inputText)
                                focusManager.clearFocus()
                            }
                        }
                    )
                )
                Button(
                    onClick = {
                        if (uiState.inputText.isNotBlank()) {
                            viewModel.sendMessage(uiState.inputText)
                            focusManager.clearFocus()
                        }
                    },
                    enabled = !uiState.isSending && uiState.inputText.isNotBlank()
                ) {
                    if (uiState.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
    if (uiState.showModelDialog) {
        ModelSelectionDialog(
            models = uiState.models,
            isLoading = uiState.isLoadingModels,
            onDismiss = { viewModel.dismissModelDialog() },
            onSelectModel = { provider, model, effort ->
                viewModel.selectModel(provider, model, effort)
            }
        )
    }
    // 错误提示
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            // 显示 Snackbar
            // 这里简化处理
            viewModel.clearError()
        }
    }
}
@Composable
fun ChatMessageItem(
    message: ChatMessageUi,
    modifier: Modifier = Modifier
) {
    val isUser = message.kind == MessageKind.User
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (message.text.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .padding(horizontal = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = backgroundColor,
                contentColor = contentColor
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp
                )
            }
        }
        if (message.reasoning.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .widthIn(max = 300.dp)
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start)
                ) {
                    Text(if (expanded) "收起深度思考" else "展开深度思考")
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
                if (expanded) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = message.reasoning,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (message.isPending) {
            Text(
                text = "生成中...",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionDialog(
    models: Map<String, List<ModelItem>>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelectModel: (String, String, Int?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (models.isEmpty()) {
                Text("暂无可用模型")
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    models.forEach { (provider, items) ->
                        Text(
                            text = provider,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        items.forEach { model ->
                            ModelRowItem(
                                model = model,
                                provider = provider,
                                onSelectModel = onSelectModel,
                                onDismiss = onDismiss
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
@Composable
fun ModelRowItem(
    model: ModelItem,
    provider: String,
    onSelectModel: (String, String, Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var showEffortDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showEffortDialog = true }
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(model.name)
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
    }
    if (showEffortDialog) {
        ReasoningEffortDialog(
            modelName = model.name,
            currentEffort = model.defaultEffort,
            onDismiss = { showEffortDialog = false },
            onConfirm = { effort ->
                onSelectModel(provider, model.id, effort)
                showEffortDialog = false
                onDismiss()
            }
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningEffortDialog(
    modelName: String,
    currentEffort: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit
) {
    var effort by remember { mutableStateOf(currentEffort ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置思考强度") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("模型: $modelName")
                Text("思考强度: $effort")
                Slider(
                    value = effort.toFloat(),
                    onValueChange = { effort = it.toInt() },
                    valueRange = 0f..5f,
                    steps = 5
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("低")
                    Text("高")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (effort > 0) effort else null) }) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
enum class MessageKind {
    User, Assistant
}
data class ChatMessageUi(
    val id: String,
    val text: String,
    val reasoning: String,
    val kind: MessageKind,
    val isPending: Boolean = false,
    val turn: Int? = null,
    val step: Int? = null,
    val seq: Int? = null
)
data class ModelItem(
    val id: String,
    val name: String,
    val provider: String,
    val defaultEffort: Int? = null
)
data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val hasMoreHistory: Boolean = true,
    val currentModel: String? = null,
    val models: Map<String, List<ModelItem>> = emptyMap(),
    val isLoadingModels: Boolean = false,
    val showModelDialog: Boolean = false,
    val error: String? = null
)
