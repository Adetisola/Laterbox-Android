package com.laterbox.app.network

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OkHttp [Dns] resolver that uses the device's system DNS first and automatically
 * falls back to DNS-over-HTTPS (Google / Cloudflare JSON DoH over direct bootstrap IPs)
 * whenever ISP or carrier DNS fails with [UnknownHostException] (for example when
 * mobile networks return NXDOMAIN for media CDN hosts such as *.hakunaymatata.com).
 */
class ResilientDns(
    private val systemDns: Dns = Dns.SYSTEM,
    private val dohClient: OkHttpClient = defaultDohClient(),
    private val dohEndpoints: List<String> = DEFAULT_DOH_ENDPOINTS,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Dns {

    private data class CachedEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim()
        if (cleanHost.isEmpty()) {
            throw UnknownHostException("Empty hostname")
        }
        try {
            val systemResult = systemDns.lookup(cleanHost)
            if (systemResult.isNotEmpty()) {
                return systemResult
            }
        } catch (systemErr: UnknownHostException) {
            if (isIpLiteral(cleanHost)) {
                throw systemErr
            }
            val now = nowMs()
            val cached = cache[cleanHost.lowercase()]
            if (cached != null && cached.expiresAtMs > now && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
            val resolved = resolveViaDoh(cleanHost)
            if (resolved.isNotEmpty()) {
                if (cache.size >= MAX_CACHE_ENTRIES) {
                    val expired = cache.entries.filter { it.value.expiresAtMs <= now }.map { it.key }
                    expired.forEach { cache.remove(it) }
                    if (cache.size >= MAX_CACHE_ENTRIES) {
                        cache.keys.take(32).forEach { cache.remove(it) }
                    }
                }
                cache[cleanHost.lowercase()] = CachedEntry(
                    addresses = resolved,
                    expiresAtMs = now + cacheTtlMs,
                )
                return resolved
            }
            throw systemErr
        }
        throw UnknownHostException("Unable to resolve host \"$cleanHost\"")
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val encoded = URLEncoder.encode(hostname, StandardCharsets.UTF_8.name())
        for (endpoint in dohEndpoints) {
            val separator = if ('?' in endpoint) "&" else "?"
            val url = "$endpoint${separator}name=$encoded&type=A"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            try {
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val parsed = parseDohJsonAddresses(body)
                    if (parsed.isNotEmpty()) {
                        return parsed
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "DoH lookup failed for $hostname via $endpoint: ${t.javaClass.simpleName}")
            }
        }
        return emptyList()
    }

    companion object {
        private const val TAG = "ResilientDns"
        private const val DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val MAX_CACHE_ENTRIES = 256

        private val IPV4_REGEX = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
        private val IPV6_HEX_REGEX = Regex("""^[0-9a-fA-F:]+$""")

        val DEFAULT_DOH_ENDPOINTS: List<String> = listOf(
            "https://8.8.8.8/resolve",
            "https://1.1.1.1/dns-query",
            "https://8.8.4.4/resolve",
        )

        val INSTANCE: ResilientDns by lazy { ResilientDns() }

        fun defaultDohClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        internal fun isIpLiteral(value: String): Boolean {
            val clean = value.trim()
            return IPV4_REGEX.matches(clean) || (clean.contains(':') && IPV6_HEX_REGEX.matches(clean))
        }

        internal fun parseDohJsonAddresses(rawJson: String): List<InetAddress> {
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
            if (root.optInt("Status", -1) != 0) return emptyList()
            val answers = root.optJSONArray("Answer") ?: return emptyList()
            val out = mutableListOf<InetAddress>()
            val seen = mutableSetOf<String>()
            for (i in 0 until answers.length()) {
                val obj = answers.optJSONObject(i) ?: continue
                val recordType = obj.optInt("type", -1)
                // Only A (1) and AAAA (28) literal IP records; skip CNAME (5)
                if (recordType != 1 && recordType != 28) continue
                val data = obj.optString("data").trim()
                if (!isIpLiteral(data) || !seen.add(data)) continue
                runCatching { InetAddress.getByName(data) }.getOrNull()?.let { out += it }
            }
            return out
        }
    }
}
