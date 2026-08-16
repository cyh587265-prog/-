package com.dsh.mobile.utils

import com.dsh.mobile.ui.SettingsViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 服务器故障切换工具：按「当前活跃地址优先」顺序尝试所有地址，
 * 第一个成功即固定为活跃地址；全部失败抛最后异常。
 */
object FailoverHelper {

    private const val DEFAULT_TIMEOUT_MS = 5000L
    private const val RETRY_DELAY_MS = 300L

    suspend fun <T> callWithFailover(
        settingsViewModel: SettingsViewModel,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: suspend (String) -> T
    ): T {
        val urls = settingsViewModel.urls.value
        if (urls.isEmpty()) throw IllegalStateException("No server URLs configured")

        val active = settingsViewModel.activeUrl.value
        val tryUrls = if (active != null && active in urls) {
            listOf(active) + urls.filter { it != active }
        } else {
            urls
        }

        var lastException: Exception? = null
        for (url in tryUrls) {
            try {
                val result = withTimeout(timeoutMs) { block(url) }
                if (url != settingsViewModel.activeUrl.value) {
                    settingsViewModel.setActiveUrl(url)
                }
                return result
            } catch (e: TimeoutCancellationException) {
                lastException = e
            } catch (e: Exception) {
                lastException = e
            }
            delay(RETRY_DELAY_MS)
        }
        throw lastException ?: RuntimeException("All server URLs failed")
    }
}

/** 顶层便捷函数：带故障切换的网络调用。 */
suspend fun <T> failover(
    settingsViewModel: SettingsViewModel,
    block: suspend (String) -> T
): T = FailoverHelper.callWithFailover(settingsViewModel, block = block)
