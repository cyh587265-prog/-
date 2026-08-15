package com.dsh.mobile.ui
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dsh.mobile.net.SessionRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    navController: NavController,
    workspaceId: String,
    viewModel: SessionListViewModel = viewModel(
        factory = SessionListViewModel.provideFactory(workspaceId)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val listState = rememberLazyListState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        viewModel.loadFirstPage()
    }
    LaunchedEffect(listState) {
        // 滚动到底部自动加载更多
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null && lastVisibleIndex >= uiState.sessions.size - 2) {
                    viewModel.loadNextPage()
                }
            }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                viewModel.createSession { sessionId ->
                    navController.navigate("chat/$workspaceId/$sessionId")
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "新建会话")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading && uiState.sessions.isEmpty() -> {
                    LoadingView()
                }
                uiState.error != null && uiState.sessions.isEmpty() -> {
                    ErrorView(error = uiState.error!!, onRetry = { viewModel.refresh() })
                }
                uiState.sessions.isEmpty() -> {
                    EmptyView()
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (uiState.isRefreshing) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("刷新中...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(uiState.sessions) { session ->
                                SessionItem(
                                    session = session,
                                    onClick = {
                                        navController.navigate("chat/$workspaceId/${session.sessionId}")
                                    },
                                    onLongClick = {
                                        renameSessionId = session.sessionId
                                        newName = sessionDisplayName(session)
                                        showRenameDialog = true
                                    }
                                )
                            }
                            if (uiState.isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // 重命名对话框
    if (showRenameDialog && renameSessionId != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("会话名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        renameSessionId?.let { sessionId ->
                            viewModel.renameSession(sessionId, newName)
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                Button(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
@Composable
fun SessionItem(
    session: SessionRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = sessionDisplayName(session),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onLongClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "重命名",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            session.updatedAt?.let { updated ->
                val raw = (updated as? kotlinx.serialization.json.JsonPrimitive)?.content ?: updated.toString()
                Text(
                    text = formatTimestamp(raw),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
private fun sessionDisplayName(session: SessionRow): String {
    // 会话标题在 projections.values 里（容错提取）
    val proj = session.projections
    if (proj is kotlinx.serialization.json.JsonObject) {
        val values = proj["values"]
        if (values is kotlinx.serialization.json.JsonObject) {
            val title = values["title"]
            if (title is kotlinx.serialization.json.JsonPrimitive && title.content.isNotBlank()) {
                return title.content
            }
        }
    }
    return "会话 ${session.sessionId.take(8)}"
}
private fun formatTimestamp(timestamp: String): String {
    return try {
        // 假设服务端返回的是 ISO 8601 格式
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val date = sdf.parse(timestamp) ?: return timestamp
        val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        displayFormat.format(date)
    } catch (e: Exception) {
        timestamp
    }
}
