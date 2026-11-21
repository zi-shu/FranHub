package me.rerere.rikkahub.data.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** 酒馆市场索引条目，字段与 plugin-market 的 tavern.json 一致 */
@Serializable
data class TavernListing(
    val id: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val emoji: String = "\uD83C\uDFAD",
    val file: String = "",
    val type: String = "card",
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val keyword = query.trim()
        return name.contains(keyword, ignoreCase = true) ||
            description.contains(keyword, ignoreCase = true) ||
            tags.any { it.contains(keyword, ignoreCase = true) }
    }
}

/**
 * 酒馆角色卡市场数据源。
 *
 * 索引地址与改款一致，指向 plugin-market 仓库的 tavern.json.gz。
 * 索引体积较大，落盘缓存 24 小时。
 */
class TavernMarketDataSource(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val cacheFile: File
        get() = File(context.cacheDir, "tavern_market_index.json")

    suspend fun loadIndex(forceRefresh: Boolean = false): List<TavernListing> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val cached = readCache()
            if (cached != null) return@withContext cached
        }
        var lastError: Exception? = null
        for (url in INDEX_URLS) {
            try {
                val bytes = download(url, MAX_INDEX_BYTES)
                val text = decodeIndexBytes(bytes)
                val listings = json.decodeFromString<List<TavernListing>>(text)
                runCatching {
                    cacheFile.writeText(text)
                }
                return@withContext listings
            } catch (exception: Exception) {
                lastError = exception
            }
        }
        throw lastError ?: IllegalStateException("Tavern market index unavailable")
    }

    suspend fun downloadCard(listing: TavernListing): ByteArray = withContext(Dispatchers.IO) {
        if (listing.file.isBlank()) error("Listing has no file: ${listing.name}")
        download(listing.file, MAX_CARD_BYTES)
    }

    private fun readCache(): List<TavernListing>? {
        val file = cacheFile
        if (!file.isFile) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > CACHE_TTL_MS) return null
        return runCatching {
            json.decodeFromString<List<TavernListing>>(file.readText())
        }.getOrNull()
    }

    private fun download(url: String, maxBytes: Long): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FranHub")
            .header("Accept", "*/*")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            val body = response.body
            val length = body.contentLength()
            if (length > maxBytes) error("Response too large: $length bytes")
            val bytes = body.bytes()
            if (bytes.size > maxBytes) error("Response too large: ${bytes.size} bytes")
            return bytes
        }
    }

    private fun decodeIndexBytes(bytes: ByteArray): String {
        val isGzip = bytes.size >= 2 &&
            bytes[0] == 0x1f.toByte() &&
            bytes[1] == 0x8b.toByte()
        return if (isGzip) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_INDEX_BYTES = 128L * 1024 * 1024
        private const val MAX_CARD_BYTES = 32L * 1024 * 1024

        val INDEX_URLS = listOf(
            "https://raw.githubusercontent.com/995fuviokd-crypto/plugin-market/main/tavern.json.gz",
            "https://github.com/995fuviokd-crypto/plugin-market/raw/main/tavern.json.gz",
            "https://ghfast.top/https://raw.githubusercontent.com/995fuviokd-crypto/plugin-market/main/tavern.json.gz",
            "https://gh-proxy.com/https://raw.githubusercontent.com/995fuviokd-crypto/plugin-market/main/tavern.json.gz",
        )
    }
}
