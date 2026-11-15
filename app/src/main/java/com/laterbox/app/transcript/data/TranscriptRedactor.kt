package com.laterbox.app.transcript.data

/**
 * Redacts signed caption URL query parameters, PO tokens, cookies, API keys, auth headers,
 * and private device file paths before logging or surfacing diagnostic details.
 */
object TranscriptRedactor {

    private val SENSITIVE_QUERY_KEYS = setOf(
        "sig",
        "signature",
        "lsig",
        "s",
        "pot",
        "po_token",
        "potc",
        "key",
        "api_key",
        "x-goog-api-key",
        "access_token",
        "token",
        "auth",
        "cookie",
        "sid",
        "sapisid",
        "hsid",
        "ssid",
        "expire",
        "sparams",
        "ip",
        "ipbits"
    )

    private val TIMEDTEXT_URL_REGEX = Regex(
        """https?://[^\s"'<>]*?(?:timedtext|googlevideo\.com/api/timedtext)[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )
    private val GENERIC_URL_QUERY_PARAM_REGEX = Regex(
        """([?&])(sig|signature|lsig|pot|po_token|potc|key|api_key|x-goog-api-key|access_token|token|auth|cookie|sid|sapisid|hsid|ssid|expire|sparams)=([^&\s"'<>]+)""",
        RegexOption.IGNORE_CASE
    )
    private val HEADER_SECRET_REGEX = Regex(
        """(?im)(Cookie|Set-Cookie|Authorization|X-Goog-Api-Key|Proxy-Authorization)\s*:\s*[^\r\n]+"""
    )
    private val API_KEY_REGEX = Regex("""AIza[0-9A-Za-z_\-]{16,}""")
    private val OAUTH_TOKEN_REGEX = Regex("""ya29\.[0-9A-Za-z_\-]{16,}""")
    private val PRIVATE_PATH_REGEX = Regex("""/(?:data/user/\d+|data/data|storage/emulated/\d+|sdcard)/[^\s"'<>]+""")

    fun sanitizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return TIMEDTEXT_URL_REGEX.replace(url) { match ->
            val raw = match.value
            val qIdx = raw.indexOf('?')
            if (qIdx >= 0) "${raw.substring(0, qIdx)}?[redacted-params]" else raw
        }.let { intermediate ->
            GENERIC_URL_QUERY_PARAM_REGEX.replace(intermediate) { m ->
                "${m.groupValues[1]}${m.groupValues[2]}=[redacted]"
            }
        }
    }

    fun sanitize(raw: String?, maxLen: Int = 260): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = sanitizeUrl(raw)
        cleaned = HEADER_SECRET_REGEX.replace(cleaned) { m -> "${m.groupValues[1]}: [redacted]" }
        cleaned = API_KEY_REGEX.replace(cleaned, "[redacted-api-key]")
        cleaned = OAUTH_TOKEN_REGEX.replace(cleaned, "[redacted-token]")
        cleaned = PRIVATE_PATH_REGEX.replace(cleaned, "[redacted-path]")
        cleaned = cleaned
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Double check key=value secrets for non-URL tokens
        for (key in SENSITIVE_QUERY_KEYS) {
            if (key == "s" || key == "ip") continue
            val kvRegex = Regex("""(?i)\b${Regex.escape(key)}=([^\s&;"']{6,})""")
            cleaned = kvRegex.replace(cleaned) { "$key=[redacted]" }
        }

        return if (cleaned.length > maxLen) {
            cleaned.take(maxLen - 3) + "..."
        } else {
            cleaned
        }
    }
}
