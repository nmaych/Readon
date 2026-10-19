package com.readon.reader.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Which routes have recently failed, so the next request does not wait on them again.
 *
 * ## Why this exists
 *
 * Route selection already remembers the host that last *worked* (see
 * `Prefs.preferredMirror`), but it had no memory for the opposite: a host that just
 * timed out is a candidate again on the very next request, and on a network where
 * three of the nine routes are black-holed, every book, every listing and every
 * update check paid their connect timeouts again. That is the "网络连接慢" half of
 * the complaint that is not about bandwidth at all -- it is about waiting for hosts
 * that are known not to answer.
 *
 * The policy is deliberately mild: a route is only demoted after
 * [FAILURES_BEFORE_COOLDOWN] consecutive failures, the demotion lasts
 * [COOLDOWN_MS], and a demoted route is *moved to the back* rather than removed.
 * Removing it would be the obvious implementation and the wrong one: if the whole
 * network is briefly down, every route cools down, and a filter would then have
 * nothing left to try -- the app would report "没有可用的访问线路" for a minute
 * after the connection came back. Ordering keeps that from being possible while
 * still putting the healthy hosts first.
 *
 * The class has no Android types and takes its clock as a parameter, so
 * `tools/check-book-logic.cmd` can exercise the cooldown arithmetic on the desktop
 * with a fake clock -- a policy that is wrong by a factor of 1000 (seconds vs
 * milliseconds) looks exactly like one that works, from the outside.
 */
class RouteHealth(
    private val cooldownMs: Long = COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Entry(var failures: Int, var lastFailureAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** A route answered. Its history is dropped, not merely forgiven. */
    fun recordSuccess(key: String) {
        entries.remove(key)
    }

    /**
     * A route did not answer.
     *
     * The counter is reset when the previous failure is already older than the
     * cooldown: two failures an hour apart are not "the route is down", they are two
     * unrelated network hiccups, and treating them as one would keep a working route
     * demoted forever.
     */
    fun recordFailure(key: String) {
        val now = clock()
        entries.compute(key) { _, old ->
            val entry = old ?: Entry(0, 0L)
            if (now - entry.lastFailureAt > cooldownMs) entry.failures = 0
            entry.failures += 1
            entry.lastFailureAt = now
            entry
        }
    }

    /** True while [key] is being kept at the back of the queue. */
    fun isCoolingDown(key: String): Boolean {
        val entry = entries[key] ?: return false
        if (entry.failures < FAILURES_BEFORE_COOLDOWN) return false
        return clock() - entry.lastFailureAt < cooldownMs
    }

    /**
     * [items] with the cooling routes moved to the end.
     *
     * The caller's order is preserved inside each group, so this composes with the
     * existing "remembered winner first, then the measured race order" ordering
     * rather than replacing it.
     *
     * Returns the *same list instance* when nothing is cooling down, so the common
     * case allocates nothing.
     */
    fun <T> order(items: List<T>, key: (T) -> String): List<T> {
        if (entries.isEmpty()) return items
        var cooling: ArrayList<T>? = null
        val healthy = ArrayList<T>(items.size)
        for (item in items) {
            if (isCoolingDown(key(item))) {
                (cooling ?: ArrayList<T>(2).also { cooling = it }).add(item)
            } else {
                healthy.add(item)
            }
        }
        val cold = cooling ?: return items
        healthy.addAll(cold)
        return healthy
    }

    /** Forget every history. Used by 检测线路, which is an explicit "re-check them all". */
    fun forgetAll() {
        entries.clear()
    }

    companion object {
        /**
         * How many consecutive failures demote a route.
         *
         * Two, not one: a single timeout is as often a dropped packet or a moment of
         * bad signal as it is a blocked host, and demoting on one failure would let a
         * flaky moment reorder the queue for a route that is working.
         */
        const val FAILURES_BEFORE_COOLDOWN = 2

        /**
         * How long a demoted route stays at the back.
         *
         * Long enough that a book opened a minute later does not re-pay the timeout,
         * short enough that a route which came back (a VPN switched on, a captive
         * portal signed in) is usable again without restarting the app.
         */
        const val COOLDOWN_MS = 90_000L
    }
}
