package com.readon.reader.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single OkHttp client.
 *
 * One client for the whole process so the connection pool and thread pool are
 * shared: the library listing, every cover-less book fetch and the update check
 * all go to the same host, and rebuilding the client per request would throw
 * away the pooled connection each time.
 */
object Http {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // GitHub serves raw content from a CDN and redirects between hosts;
            // following redirects is required for `raw.githubusercontent.com`.
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    const val USER_AGENT = "Readon-Android"

    /**
     * Percent-encode each path segment but keep the separators.
     *
     * Library paths contain non-ASCII collection names -- `默认/xxx.txt` -- and
     * those must be encoded before they go into a raw URL. `URLEncoder` encodes
     * for form bodies, where a space becomes `+`; in a path that would be a
     * literal plus, so `%20` is substituted back.
     */
    fun encodePath(path: String): String =
        path.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
}
