package com.dsh.mobile.pair
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.net.URLDecoder
import java.net.URLEncoder
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseUrl by settingsViewModel.baseUrl.collectAsStateWithLifecycle()
    var linkInput by remember { mutableStateOf("") }
    var tokenOnlyInput by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var tempBaseUrl by remember { mutableStateOf(baseUrl) }
    // 扫码启动器
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        result.contents?.let { rawResult ->
            handlePairingLink(
                rawResult,
                baseUrl,
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
    // 检查本地是否已有配对 cookie
    val hasPaired = remember(baseUrl) {
        val host = runCatching { Uri.parse(baseUrl).host }.getOrNull()
        host != null && DshHttpClient.getCookie(host, Constants.COOKIE_NAME) != null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配对") },
                actions = {
                    IconButton(onClick = { showBaseUrlDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置服务器地址")
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
            // 当前服务器地址
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前服务器地址",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = baseUrl.ifEmpty { "未设置" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 已配对状态
            if (hasPaired) {
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
                    if (baseUrl.isEmpty()) {
                        Toast.makeText(context, "请先设置服务器地址", Toast.LENGTH_SHORT).show()
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
                    if (baseUrl.isEmpty()) {
                        Toast.makeText(context, "请先设置服务器地址", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    handlePairingLink(
                        linkInput,
                        baseUrl,
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
            // 手动输入令牌（仅token，自动拼接到当前baseUrl）
            OutlinedTextField(
                value = tokenOnlyInput,
                onValueChange = { tokenOnlyInput = it },
                label = { Text("仅输入令牌 (自动拼接)") },
                placeholder = { Text("输入令牌，如 AbC123") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isPairing,
                singleLine = true
            )
            Button(
                onClick = {
                    if (baseUrl.isEmpty()) {
                        Toast.makeText(context, "请先设置服务器地址", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (tokenOnlyInput.isBlank()) {
                        Toast.makeText(context, "请输入令牌", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    // 构造配对链接
                    val pairLink = "$baseUrl/?pair=$tokenOnlyInput"
                    handlePairingLink(
                        pairLink,
                        baseUrl,
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
    // 设置地址对话框
    if (showBaseUrlDialog) {
        AlertDialog(
            onDismissRequest = { showBaseUrlDialog = false },
            title = { Text("设置服务器地址") },
            text = {
                OutlinedTextField(
                    value = tempBaseUrl,
                    onValueChange = { tempBaseUrl = it },
                    label = { Text("服务器地址 (含协议和端口)") },
                    placeholder = { Text("http://192.168.1.100:3080") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempBaseUrl.isNotBlank()) {
                            settingsViewModel.setBaseUrl(tempBaseUrl)
                            showBaseUrlDialog = false
                            Toast.makeText(context, "服务器地址已更新", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                Button(onClick = { showBaseUrlDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
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
    if (baseUrl.isEmpty()) {
        Toast.makeText(context, "请先设置服务器地址", Toast.LENGTH_SHORT).show()
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
                    // 导航到会话列表（工作区 id 可选）
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
    // 尝试解析为完整URL
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
    // 尝试作为纯令牌
    if (input.matches(Regex("^[A-Za-z0-9_-]+$"))) {
        return Pair(input, null)
    }
    // 尝试解析为 baseUrl + 令牌
    if (input.startsWith("/")) {
        val token = input.removePrefix("/").trim()
        if (token.isNotBlank()) {
            return Pair(token, null)
        }
    }
    return null
}
