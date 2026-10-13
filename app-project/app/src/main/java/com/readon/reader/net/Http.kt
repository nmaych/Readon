package com.readon.reader.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The single OkHttp client.
 *
 * One client for the whole process so the connection pool and thread pool are
 * shared: the library listing, every cover-less book fetch and the update check
 * all go to the same host, and rebuilding the client per request would throw
 * away the pooled connection each time.
 *
 * ## The two timeout regimes
 *
 * On the networks this app is built for, a blocked host does not refuse a
 * connection -- it swallows the SYN, or completes the TLS handshake and then
 * sends nothing. A generous timeout therefore does not make the app more
 * reliable, it makes it *hang*: the reader sat on "正在排版…" for minutes because
 * nine mirrors were each given 45 seconds to say nothing at all.
 *
 * So the two kinds of request are treated differently:
 *
 *  * **Metadata** -- listings, route probes, the update manifest. These are small
 *    and should arrive promptly or not at all, so the callers pass a short
 *    explicit timeout (8-25 s) and a dead route becomes a quick loss.
 *  * **Transfers** -- a book or an APK. A 75 MB file legitimately takes minutes
 *    on a phone connection, so these pass a long timeout, and the client default
 *    below is long too. Anything that forgets to pass one must not be the reason
 *    a slow-but-working download is killed.
 *
 * The per-call ceiling matters on top of the connect/read timeouts because
 * `retryOnConnectionFailure` can otherwise multiply a black-holed route's connect
 * timeout across several attempts, and nothing above it is bounded.
 */
object Http {

    /**
     * Whole-call ceiling used when a caller does not name one.
     *
     * Long, because the requests that rely on it are the large transfers. Short
     * timeouts are always passed explicitly for the small ones.
     */
    private const val DEFAULT_CALL_TIMEOUT_MS = 20 * 60 * 1000L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            // Per socket read, not per body: a large file that keeps flowing is
            // fine, a route that goes silent mid-transfer fails promptly.
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(DEFAULT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // GitHub serves raw content from a CDN and redirects between hosts;
            // following redirects is required for `raw.githubusercontent.com`.
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    const val USER_AGENT = "Readon-Android"

    /**
     * Run [request] as a suspending, cancellable call.
     *
     * OkHttp's own `execute()` blocks a thread and cannot be interrupted when the
     * coroutine that started it is cancelled. That matters here because route
     * selection works by launching a request per candidate and keeping the first
     * to answer: with a blocking call the losers would keep their sockets open
     * until their timeouts expired, which is exactly the pile-up that made the
     * app look frozen. Enqueuing instead lets cancellation reach the socket, so
     * `job.cancel()` in [GithubClient] aborts the losing transfers immediately.
     *
     * @param timeoutMs a whole-call ceiling, or null for the client default.
     */
    suspend fun execute(request: Request, timeoutMs: Long? = null): Response =
        suspendCancellableCoroutine { cont ->
            val call = clientFor(timeoutMs).newCall(request)

            // The load-bearing line: cancelling the coroutine cancels the call,
            // which closes the socket and unblocks any in-flight read.
            cont.invokeOnCancellation { runCatching { call.cancel() } }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // If the race was already decided, this response belongs to
                    // nobody; close it rather than leak the connection.
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    /**
     * A client with [timeoutMs] as its whole-call ceiling.
     *
     * `newBuilder()` copies the dispatcher and the connection pool by reference,
     * so these variants still share connections with the main client; they are
     * cached anyway so a per-request timeout does not allocate a client every
     * time. OkHttp only offers call timeouts on the client, not per call.
     */
    private val timedClients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    private fun clientFor(timeoutMs: Long?): OkHttpClient {
        if (timeoutMs == null) return client
        return timedClients.getOrPut(timeoutMs) {
            client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }
    }

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
