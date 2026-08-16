package com.dsh.mobile.pair
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.dsh.mobile.MainActivity
import com.dsh.mobile.net.Constants
import com.dsh.mobile.net.DshHttpClient
import com.dsh.mobile.net.PairingClient
import com.dsh.mobile.ui.SettingsViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PairScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 收集 ViewModel 多地址状态
    val currentUrlState = settingsViewModel.activeUrl.collectAsStateWithLifecycle()
    val currentUrl = currentUrlState.value
    val urls by settingsViewModel.urls.collectAsStateWithLifecycle()
    // 本地 UI 状态
    var linkInput by remember { mutableStateOf("") }
    var tokenOnlyInput by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddUrlDialog by remember { mutableStateOf(false) }
    var tempNewUrl by remember { mutableStateOf("") }
    // 服务器可达性状态
    var isReachable by remember { mutableStateOf<Boolean?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    // 轻量探测：切换 currentUrl 或进入页面时自动探测一次
    LaunchedEffect(currentUrl) {
        if (currentUrl.isNullOrBlank()) {
            isReachable = null
            isChecking = false
            return@LaunchedEffect
        }
        isChecking = true
        isReachable = null
        val result = withContext(Dispatchers.IO) {
            checkServerReachable(currentUrl)
        }
        isReachable = result
        isChecking = false
    }
    // 检查本地是否已有配对 cookie
    val hasPaired = remember(currentUrl) {
        val host = runCatching { Uri.parse(currentUrl).host }.getOrNull()
        host != null && DshHttpClient.getCookie(host, Constants.COOKIE_NAME) != null
    }
    // 扫码启动器
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        result.contents?.let { rawResult ->
            handlePairingLink(
                rawResult,
                currentUrl.orEmpty(),
                scope,
                context,
                navController,
                settingsViewModel,
                { isPairing = it },
                { errorMessage = it }
            )
        }
    }
    // 相机权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scanLauncher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("扫描配对二维码")
                    setCameraId(0)
                    setBeepEnabled(true)
                    setBarcodeImageEnabled(false)
                    setOrientationLocked(false)
                }
            )
        } else {
            Toast.makeText(context, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配对") },
                actions = {
                    IconButton(onClick = { showAddUrlDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加服务器地址"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ===== 服务器地址管理区域 =====
            ServerAddressCard(
                currentUrl = currentUrl.orEmpty(),
                urls = urls,
                isReachable = isReachable,
                isChecking = isChecking,
                onSelectUrl = { settingsViewModel.setActiveUrl(it) },
                onRemoveUrl = { urlToRemove ->
                    // 若删除的是当前激活地址，自动切换到其他可用地址
                    if (urlToRemove == currentUrl) {
                        val next = urls.firstOrNull { it != urlToRemove }
                        next?.let { settingsViewModel.setActiveUrl(it) }
                    }
                    settingsViewModel.removeUrl(urlToRemove)
                },
                onAddClick = { showAddUrlDialog = true }
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 已配对状态
            if (hasPaired && !currentUrl.isNullOrBlank()) {
                Button(
                    onClick = {
                        navController.navigate("workspaces")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("已配对，进入工作区")
                }
            }
            // 扫码配对
            Button(
                onClick = {
                    if (currentUrl.isNullOrBlank()) {
                        Toast.makeText(context, "请先设置或选择服务器地址", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isPairing
            ) {
                Text("扫码配对")
            }
            // 手动输入链接
            OutlinedTextField(
                value = linkInput,
                onValueChange = { linkInput = it },
                label = { Text("粘贴配对链接或令牌") },
                placeholder = { Text("https://.../?pair=xxx 或输入令牌") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isPairing,
                singleLine = true
            )
            Button(
                onClick = {
                    if (currentUrl.isNullOrBlank()) {
                        Toast.makeText(context, "请先设置或选择服务器地址", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    handlePairingLink(
                        linkInput,
                        currentUrl.orEmpty(),
                        scope,
                        context,
                        navController,
                        settingsViewModel,
                        { isPairing = it },
                        { errorMessage = it }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = linkInput.isNotBlank() && !isPairing
            ) {
                Text("手动配对")
            }
            // 手动输入令牌（仅 token，自动拼接到当前 currentUrl）
            OutlinedTextField(
                value = tokenOnlyInput,
                onValueChange = { tokenOnlyInput = it },
                label = { Text("仅输入令牌(自动拼接)") },
                placeholder = { Text("输入令牌，如 AbC123") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isPairing,
                singleLine = true
            )
            Button(
                onClick = {
                    if (currentUrl.isNullOrBlank()) {
                        Toast.makeText(context, "请先设置或选择服务器地址", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (tokenOnlyInput.isBlank()) {
                        Toast.makeText(context, "请输入令牌", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val pairLink = "$currentUrl/?pair=$tokenOnlyInput"
                    handlePairingLink(
                        pairLink,
                        currentUrl.orEmpty(),
                        scope,
                        context,
                        navController,
                        settingsViewModel,
                        { isPairing = it },
                        { errorMessage = it }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = tokenOnlyInput.isNotBlank() && !isPairing
            ) {
                Text("使用令牌配对")
            }
            // 错误提示
            errorMessage?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { errorMessage = null }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }
            }
            // 配对中进度
            if (isPairing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("配对中...", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    // 添加地址对话框（原「设置服务器地址」对话框升级）
    if (showAddUrlDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddUrlDialog = false
                tempNewUrl = ""
            },
            title = { Text("添加服务器地址") },
            text = {
                OutlinedTextField(
                    value = tempNewUrl,
                    onValueChange = { tempNewUrl = it },
                    label = { Text("服务器地址 (含协议和端口)") },
                    placeholder = { Text("http://192.168.1.100:3080") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = tempNewUrl.trim()
                        if (trimmed.isNotBlank()) {
                            settingsViewModel.addUrl(trimmed)
                            // 若当前无激活地址，自动激活新添加的地址
                            if (currentUrl.isNullOrBlank()) {
                                settingsViewModel.setActiveUrl(trimmed)
                            }
                            showAddUrlDialog = false
                            tempNewUrl = ""
                            Toast.makeText(context, "服务器地址已添加", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                Button(onClick = {
                    showAddUrlDialog = false
                    tempNewUrl = ""
                }) {
                    Text("取消")
                }
            }
        )
    }
}
/**
 * 服务器地址管理卡片：列表展示、切换、长按删除、在线状态
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerAddressCard(
    currentUrl: String,
    urls: List<String>,
    isReachable: Boolean?,
    isChecking: Boolean,
    onSelectUrl: (String) -> Unit,
    onRemoveUrl: (String) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题行 + 可达性状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "服务器地址",
                    style = MaterialTheme.typography.titleMedium
                )
                when {
                    isChecking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    isReachable == true -> {
                        StatusBadge(
                            text = "在线",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    isReachable == false -> {
                        StatusBadge(
                            text = "离线",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            // 当前激活地址
            if (!currentUrl.isNullOrBlank()) {
                Text(
                    text = "当前: $currentUrl",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "未选择服务器地址",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            // 离线提示
            if (isReachable == false && !currentUrl.isNullOrBlank()) {
                Text(
                    text = "当前服务器离线，请点击切换其他地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (urls.isNotEmpty()) {
                Divider()
                // 地址列表：点击切换、长按删除
                urls.forEach { url ->
                    val isActive = url == currentUrl
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSelectUrl(url) },
                                onLongClick = { onRemoveUrl(url) }
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (isActive) {
                            StatusBadge(
                                text = "当前",
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
            // 添加地址按钮
            TextButton(
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加地址")
            }
        }
    }
}
/**
 * 小型状态标签（用于「在线/离线/当前」徽章）
 */
@Composable
private fun StatusBadge(
    text: String,
    color: Color,
    contentColor: Color = contentColorFor(color),
    modifier: Modifier = Modifier
) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}
/**
 * 对指定地址做轻量 GET 探测，超时 5s
 */
private fun checkServerReachable(url: String): Boolean {
    return try {
        val normalizedUrl = url.trim().removeSuffix("/")
        val statusUrl = "$normalizedUrl/api/pair/status"
        val connection = URL(statusUrl).openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            doInput = true
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DSH-Android/1.0")
        }
        val responseCode = connection.responseCode
        connection.disconnect()
        responseCode in 200..299
    } catch (e: Exception) {
        false
    }
}
// ==================== 以下原有配对逻辑保持不变，仅将 baseUrl 入参替换为 currentUrl 传入 ====================
private fun handlePairingLink(
    input: String,
    baseUrl: String,
    scope: CoroutineScope,
    context: android.content.Context,
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    setPairing: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    if (baseUrl.isBlank()) {
        Toast.makeText(context, "请先设置或选择服务器地址", Toast.LENGTH_SHORT).show()
        return
    }
    val pairData = parsePairingLink(input, baseUrl)
    if (pairData == null) {
        Toast.makeText(context, "无效的配对链接或令牌", Toast.LENGTH_SHORT).show()
        return
    }
    val (token, workspaceId) = pairData
    if (token.isBlank()) {
        Toast.makeText(context, "配对令牌为空", Toast.LENGTH_SHORT).show()
        return
    }
    setPairing(true)
    setError(null)
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            PairingClient().accept(token, baseUrl)
        }
        withContext(Dispatchers.Main) {
            setPairing(false)
            result.fold(
                onSuccess = { deviceId ->
                    Toast.makeText(context, "配对成功", Toast.LENGTH_SHORT).show()
                    val route = if (workspaceId != null) {
                        "sessions/$workspaceId"
                    } else {
                        "workspaces"
                    }
                    navController.navigate(route) {
                        popUpTo("pair") { inclusive = true }
                    }
                },
                onFailure = { throwable ->
                    val msg = when (throwable) {
                        is java.net.UnknownHostException -> "无法解析服务器地址"
                        is java.net.ConnectException -> "连接服务器失败"
                        is java.net.SocketTimeoutException -> "请求超时"
                        else -> throwable.message ?: "配对失败"
                    }
                    setError(msg)
                    Toast.makeText(context, "配对失败: $msg", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
private fun parsePairingLink(input: String, baseUrl: String): Pair<String, String?>? {
    try {
        val uri = Uri.parse(input)
        val scheme = uri.scheme
        if (scheme == "http" || scheme == "https") {
            val pair = uri.getQueryParameter("pair")
            val workspace = uri.getQueryParameter("workspace")
            if (!pair.isNullOrBlank()) {
                return Pair(pair, workspace)
            }
        }
    } catch (_: Exception) {
        // 可能不是完整URL，尝试作为令牌处理
    }
    if (input.matches(Regex("^[A-Za-z0-9_-]+$"))) {
        return Pair(input, null)
    }
    if (input.startsWith("/")) {
        val token = input.removePrefix("/").trim()
        if (token.isNotBlank()) {
            return Pair(token, null)
        }
    }
    return null
}
