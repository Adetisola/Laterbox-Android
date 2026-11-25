package com.laterbox.app.media.domain.model

enum class FailureCode(val userMessage: String) {
    NETWORK_ERROR("Network connection lost. Please check your internet connection."),
    CONNECTION_TIMEOUT("Connection timed out. Please try again."),
    EXTRACTION_TIMEOUT("Loading took too long. Please try again."),
    PYTHON_RUNTIME_ERROR("The media engine encountered a temporary issue."),
    YOUTUBE_CHALLENGE_ERROR("Verification is required for this link right now. Please try again later."),
    INSUFFICIENT_STORAGE("Not enough storage space available on device."),
    PRIVATE_CONTENT("This media is private or protected."),
    LOGIN_REQUIRED("Login required to access this media."),
    GEO_BLOCKED("Content is not available in your region."),
    RATE_LIMITED("Laterbox is temporarily busy. Please try again later."),
    EXTRACTOR_BROKEN("Unable to load this link right now. An update may help."),
    UNSUPPORTED_URL("No downloadable media found at this link."),
    HTTP_ERROR("Unable to complete the request right now. Please try again."),
    CORRUPT_MEDIA("Downloaded media was incomplete or invalid."),
    UNKNOWN("An unexpected error occurred during download.");

    val isRetryable: Boolean
        get() = this == NETWORK_ERROR || this == CONNECTION_TIMEOUT || this == EXTRACTION_TIMEOUT || this == RATE_LIMITED || this == HTTP_ERROR
}
