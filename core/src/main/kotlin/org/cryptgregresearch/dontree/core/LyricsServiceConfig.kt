package org.cryptgregresearch.dontree.core

import java.net.URI

data class LyricsServiceConfig(val endpoint: String = "", val apiKey: String = "") {
    val baseUrl: String get() = endpoint.trim().trimEnd('/').ifEmpty { "https://lrclib.net/api" }
    fun validate() {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Use an HTTPS lyrics API base URL without a query, password or fragment."
        }
        require(!apiKey.contains('\r') && !apiKey.contains('\n')) { "The lyrics API key contains an invalid line break." }
        require(endpoint.isNotBlank() || apiKey.isBlank()) { "A custom API key requires a custom lyrics service URL." }
    }
}
