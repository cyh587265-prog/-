package com.dsh.mobile.net
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
object DshHttpClient {
    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()
    val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val store = cookieStore.getOrPut(host) { mutableMapOf() }
            cookies.forEach { cookie ->
                store[cookie.name] = cookie
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            return cookieStore[host]?.values?.toList() ?: emptyList()
        }
    }
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()
    fun getCookie(host: String, name: String): String? {
        return cookieStore[host]?.get(name)?.value
    }
    fun clearCookies() {
        cookieStore.clear()
    }
    fun clearCookiesForHost(host: String) {
        cookieStore.remove(host)
    }
}
