package com.readon.reader.update

/**
 * What an update check found.
 *
 * Free of Android types on purpose: [UpdateDecision] is the part of the update
 * check that can be *wrong* rather than merely fail, and
 * `tools/check-book-logic.cmd` compiles this file and exercises it on the desktop.
 */
sealed interface UpdateResult {
    /** A newer build is available. */
    data class Available(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val sha256: String?,
        val notes: String,
        val mandatory: Boolean,
    ) : UpdateResult

    /** The installed build is current. */
    data class UpToDate(val latestName: String?) : UpdateResult

    /**
     * The check could not run, or found something it cannot install.
     *
     * [newerExists] marks the second case: a newer release was seen but has no
     * APK attached. Reporting "已是最新版本" there would be a lie, so the caller
     * surfaces this even when the other source claims to be up to date.
     */
    data class Failed(val message: String, val newerExists: Boolean = false) : UpdateResult
}

/**
 * How the two update sources are combined into one answer.
 *
 * ## The bug this exists to prevent
 *
 * The update check used to consult `dist/update.json` first and treat it as
 * authoritative: a manifest that said "no update" ended the check, and the GitHub
 * releases API was asked only when the manifest could not be read at all. But a
 * *stale* manifest is the ordinary failure, not a rare one -- it lives on the
 * default branch and is written by the release workflow, so a release published by
 * hand, or a run whose manifest commit did not land, leaves it describing the
 * previous version. The app then reported 已是最新版本 while a newer release sat on
 * the releases page. In the old code those two states were indistinguishable.
 *
 * So both sources are consulted on every check and the newer answer wins. The rule
 * is deliberately *asymmetric*: a source that found something newer is positive
 * evidence, and the other source's "up to date" is not evidence against it -- it is
 * precisely what a stale manifest says. That asymmetry is the fix, and it is why
 * this is a separate, tested function rather than three lines inside the manager.
 */
object UpdateDecision {

    /**
     * Combine the manifest's and the releases API's answers.
     *
     * Preference order:
     *
     *  1. The highest `versionCode` that is actually installable.
     *  2. A newer version that exists but cannot be installed from here (no APK
     *     attached). This outranks "up to date" for the reason above: telling the
     *     user they are current when a newer release is visible is the bug, whether
     *     or not that release happens to be installable in-app.
     *  3. "Up to date", when at least one source answered and neither found
     *     anything newer. The name reported is the newest one seen, so the card
     *     cannot display an older version than the one just observed.
     *  4. A transport failure.
     */
    fun reconcile(a: UpdateResult, b: UpdateResult): UpdateResult {
        val available = listOfNotNull(
            a as? UpdateResult.Available,
            b as? UpdateResult.Available,
        )
        if (available.isNotEmpty()) {
            return available.maxByOrNull { it.versionCode }!!
        }

        // Before "up to date" on purpose; see the class comment.
        val uninstallable = listOfNotNull(a as? UpdateResult.Failed, b as? UpdateResult.Failed)
            .firstOrNull { it.newerExists }
        if (uninstallable != null) return uninstallable

        // `latestName` is nullable, so "at least one source is up to date" is not
        // the same question as "some name was reported": two sources that are both
        // current but silent about the version must still produce an answer rather
        // than fall through to the failure branch below and find nothing there.
        val upToDate = listOfNotNull(a as? UpdateResult.UpToDate, b as? UpdateResult.UpToDate)
        if (upToDate.isNotEmpty()) {
            val names = upToDate.mapNotNull { it.latestName }
            return UpdateResult.UpToDate(names.maxByOrNull { versionCodeOf(it) })
        }

        // Reached only when neither source produced an update or an "up to date".
        // A transport failure's own message is what the user needs -- "连接 GitHub
        // 超时" tells them to retry, where a generic "检查更新失败" tells them
        // nothing -- so it is passed through rather than replaced.
        val failures = listOfNotNull(a as? UpdateResult.Failed, b as? UpdateResult.Failed)
        return failures.firstOrNull { it.newerExists }
            ?: failures.firstOrNull()
            ?: UpdateResult.Failed("检查更新失败")
    }

    /**
     * `1.2.3` -> `10203`, matching the build's version scheme.
     *
     * The comparison is always on this number, never on the version name: string
     * comparison puts "1.10.0" before "1.9.0", which would silently stop offering
     * updates at exactly the wrong moment. Must stay identical to
     * `tools/ci_release.py`'s `version_code_of` and to the release workflow's
     * arithmetic.
     *
     * A leading `v` (a release tag such as `v1.2.3`) is tolerated. The one caller
     * strips it already, but tolerating it here costs nothing and removes a trap:
     * without this, `versionCodeOf("v1.0.5")` quietly yields `500` rather than
     * `10005`, because the leading `v` makes the first component unparseable and
     * the remaining numbers are read as minor and patch. A version that is off by
     * two orders of magnitude fails in the worst way -- it looks like a
     * plausible answer.
     */
    fun versionCodeOf(versionName: String): Int {
        val cleaned = versionName.trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split('.', '-', '+')
        val nums = parts.mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        if (nums.isEmpty()) return 0
        val major = nums.getOrElse(0) { 0 }
        val minor = nums.getOrElse(1) { 0 }
        val patch = nums.getOrElse(2) { 0 }
        return major * 10000 + minor * 100 + patch
    }
}
