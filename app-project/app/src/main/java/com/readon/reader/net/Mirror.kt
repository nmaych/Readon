package com.readon.reader.net

import com.readon.reader.data.LibrarySource

/**
 * A host a repository file can be fetched from.
 *
 * The library lives on GitHub, and `raw.githubusercontent.com` is unreachable on
 * a lot of networks -- the first release failed there with a 20-second connect
 * timeout and then an SSL reset, and because reading *and* downloading both went
 * through that one hostname, the whole app was unusable.
 *
 * Every entry below serves the same bytes over a different host, so the client
 * can race them and take whichever answers first. jsDelivr appears three times
 * on purpose: it is the mirror that works most reliably on mainland networks,
 * and its three hostnames fail independently.
 *
 * The URLs are pure functions of the source and path, which keeps them testable
 * and keeps the client free of string building.
 */
enum class Mirror(
    val id: String,
    val label: String,
    val hint: String,
) {
    Direct("direct", "GitHub 直连", "raw.githubusercontent.com，最快，但国内常被阻断"),
    JsDelivr("jsdelivr", "jsDelivr", "公共 CDN，快；但单文件超过 20 MB 会拒绝"),
    Fastly("fastly", "jsDelivr · Fastly", "jsDelivr 的 Fastly 节点"),
    Gcore("gcore", "jsDelivr · Gcore", "jsDelivr 的 Gcore 节点"),
    GhProxy("ghproxy", "ghproxy.net", "GitHub 代理，大文件也可用"),
    GhProxyCom("ghproxycom", "gh-proxy.com", "GitHub 代理，大文件也可用"),
    GhFast("ghfast", "ghfast.top", "GitHub 代理加速"),
    GitMirror("gitmirror", "gitmirror", "GitHub 镜像站"),
    Statically("statically", "Statically", "公共 CDN"),
    Custom("custom", "自定义加速", "把下面填写的地址前缀拼在 GitHub 地址前"),
    ;

    fun rawUrl(source: LibrarySource, path: String, customPrefix: String = ""): String {
        val direct = directUrl(source, path)
        return when (this) {
            Direct -> direct
            JsDelivr -> jsDelivr("https://cdn.jsdelivr.net/gh", source, path)
            Fastly -> jsDelivr("https://fastly.jsdelivr.net/gh", source, path)
            Gcore -> jsDelivr("https://gcore.jsdelivr.net/gh", source, path)
            Statically -> "https://cdn.statically.io/gh/${source.owner}/${source.repo}/" +
                "${Http.encodePath(source.branch)}/${Http.encodePath(path)}"
            GitMirror -> "https://raw.gitmirror.com/${source.owner}/${source.repo}/" +
                "${Http.encodePath(source.branch)}/${Http.encodePath(path)}"
            GhProxy -> "https://ghproxy.net/$direct"
            GhFast -> "https://ghfast.top/$direct"
            GhProxyCom -> "https://gh-proxy.com/$direct"
            Custom -> customPrefix.trim().trimEnd('/') + "/" + direct
        }
    }

    private fun directUrl(source: LibrarySource, path: String): String =
        "https://raw.githubusercontent.com/${source.owner}/${source.repo}/" +
            "${Http.encodePath(source.branch)}/${Http.encodePath(path)}"

    private fun jsDelivr(base: String, source: LibrarySource, path: String): String =
        "$base/${source.owner}/${source.repo}@${Http.encodePath(source.branch)}/" +
            Http.encodePath(path)

    companion object {
        /**
         * Probe order.
         *
         * Measured against the live library: the two `gh-proxy` hosts served every
         * file tested including the 75 MB one, the jsDelivr hosts served only files
         * under 20 MB, and `raw.githubusercontent.com` timed out on every attempt.
         *
         * Order matters less than it looks, because [GithubClient.pickMirror]
         * probes all of them concurrently and takes whichever answers first -- so
         * this list decides the fallback sequence, not the usual case. The
         * known-good hosts lead so that a sequential retry starts somewhere
         * sensible.
         */
        val RACE_ORDER = listOf(
            GhProxyCom, GhProxy, JsDelivr, Fastly, Gcore,
            Direct, GhFast, GitMirror, Statically,
        )

        fun byId(id: String?): Mirror? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where the repository *listing* comes from.
 *
 * GitHub's tree API is the only source that reports file sizes and the truncation
 * flag, but it is also the one that gets rate-limited and blocked. jsDelivr
 * publishes the same file list through its own data API, which is a genuinely
 * different path to the same information rather than a proxy of the same call.
 */
enum class ListSource(val id: String, val label: String) {
    GithubApi("gh-api", "GitHub API"),
    JsDelivrData("jsdelivr-data", "jsDelivr 数据接口"),
    GhProxyApi("ghproxy-api", "GitHub API · ghproxy"),
    GhFastApi("ghfast-api", "GitHub API · ghfast"),
    GhProxyComApi("ghproxycom-api", "GitHub API · gh-proxy"),
    ;

    fun url(source: LibrarySource): String {
        val api = "https://api.github.com/repos/${source.owner}/${source.repo}" +
            "/git/trees/${Http.encodePath(source.branch)}?recursive=1"
        return when (this) {
            GithubApi -> api
            JsDelivrData -> "https://data.jsdelivr.com/v1/packages/gh/" +
                "${source.owner}/${source.repo}@${Http.encodePath(source.branch)}?structure=flat"
            GhProxyApi -> "https://ghproxy.net/$api"
            GhFastApi -> "https://ghfast.top/$api"
            GhProxyComApi -> "https://gh-proxy.com/$api"
        }
    }

    companion object {
        /**
         * Listing order.
         *
         * `api.github.com` is a different service from
         * `raw.githubusercontent.com` and was reachable even while the raw host was
         * blocked, so it leads. jsDelivr's data API is last because it cannot
         * describe this repository at all -- the package is over its 50 MB limit
         * and it answers 403 -- but it stays for smaller libraries, where it is a
         * genuinely independent route.
         */
        val RACE_ORDER = listOf(GithubApi, GhProxyComApi, GhProxyApi, GhFastApi, JsDelivrData)

        fun byId(id: String?): ListSource? = entries.firstOrNull { it.id == id }
    }
}
