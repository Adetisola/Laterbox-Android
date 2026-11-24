package com.laterbox.app.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

/** Request tag recording the host a media request was originally issued to. */
data class InitialMediaHost(val host: String)

/**
 * Header-scoping rules shared by playback, downloads and subtitle retrieval.
 *
 * Provider stream credentials (signed cookies, MovieBox tokens, Referer/Origin) are only meant
 * for the CDN site the provider issued them for. OkHttp itself only strips `Authorization`
 * on cross-host redirects, so everything else must be removed here.
 */
object MediaHeaderScope {
    fun isStreamScopedAuthHeader(name: String): Boolean {
        val lower = name.trim().lowercase(Locale.ROOT)
        return lower == "cookie" ||
            lower == "x-mb-token" ||
            lower == "referer" ||
            lower == "origin" ||
            (lower.startsWith("x-") && lower.endsWith("-token"))
    }

    fun isSameSiteHost(hostA: String, hostB: String): Boolean {
        val a = hostA.trim().trimEnd('.').lowercase(Locale.ROOT)
        val b = hostB.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (a == b) return true
        if (a.all { it.isDigit() || it == '.' } || b.all { it.isDigit() || it == '.' } || ':' in a || ':' in b) {
            return false
        }
        val partsA = a.split('.')
        val partsB = b.split('.')
        if (partsA.size >= 2 && partsB.size >= 2) {
            val siteA = "${partsA[partsA.size - 2]}.${partsA[partsA.size - 1]}"
            val siteB = "${partsB[partsB.size - 2]}.${partsB[partsB.size - 1]}"
            return siteA == siteB
        }
        return false
    }
}

/**
 * Network interceptor that strips stream-scoped credentials when a redirect leaves the initial
 * site. [cookieFor] may supply cookies legitimately issued for the redirect target
 * (e.g. captured from that host's own `Set-Cookie`). Requires the [InitialMediaHost] request tag.
 */
class RedirectHeaderGuardInterceptor(
    private val cookieFor: ((HttpUrl) -> String?)? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val initialHost = req.tag(InitialMediaHost::class.java)?.host
        if (initialHost == null || MediaHeaderScope.isSameSiteHost(initialHost, req.url.host)) {
            return chain.proceed(req)
        }
        val cleaned = req.newBuilder()
        for (name in req.headers.names()) {
            if (MediaHeaderScope.isStreamScopedAuthHeader(name) ||
                name.equals("Authorization", ignoreCase = true) ||
                name.equals("Proxy-Authorization", ignoreCase = true)
            ) {
                cleaned.removeHeader(name)
            }
        }
        cookieFor?.invoke(req.url)?.takeIf { it.isNotBlank() }?.let { cleaned.header("Cookie", it) }
        return chain.proceed(cleaned.build())
    }
}
