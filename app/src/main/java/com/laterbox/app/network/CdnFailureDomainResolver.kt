package com.laterbox.app.network

import java.net.URI
import java.util.Locale

/**
 * Maps media CDN hostnames (or full stream URLs) onto canonical failure domains so
 * rate-limit cooldowns and fallback exclusions apply across known DNS aliases sharing the
 * same edge cluster, without performing runtime DNS lookups or guessing unverified clusters.
 */
object CdnFailureDomainResolver {

    const val DOMAIN_EDGENEXT_MP4 = "edgenext:mp4:u855cyvod2"
    const val DOMAIN_EDGENEXT_DASH = "edgenext:dash:u855cydli"

    private val KNOWN_HOST_DOMAINS: Map<String, String> = mapOf(
        "bcdnxw.hakunaymatata.com" to DOMAIN_EDGENEXT_MP4,
        "bcdnw.hakunaymatata.com" to DOMAIN_EDGENEXT_MP4,
        "sbcdnw.hakunaymatata.com" to DOMAIN_EDGENEXT_DASH,
    )
    private val CANONICAL_DOMAINS: Set<String> = KNOWN_HOST_DOMAINS.values.toSet()
    private val PORT_SUFFIX_REGEX = Regex(""":\d+$""")

    fun extractNormalizedHost(hostOrUrl: String?): String? {
        val raw = hostOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hostCandidate = if (raw.contains("://")) {
            runCatching { URI(raw).host }.getOrNull()
        } else {
            val authority = raw.substringBefore('/').substringBefore('?').substringBefore('#')
            authority.replace(PORT_SUFFIX_REGEX, "").takeIf { it.isNotBlank() }
        }
        return hostCandidate
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
    }

    fun resolve(hostOrUrl: String?): String? {
        val raw = hostOrUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lowerRaw = raw.lowercase(Locale.ROOT)
        if (lowerRaw in CANONICAL_DOMAINS) return lowerRaw
        val normalizedHost = extractNormalizedHost(raw) ?: return null
        return KNOWN_HOST_DOMAINS[normalizedHost] ?: normalizedHost
    }
}
