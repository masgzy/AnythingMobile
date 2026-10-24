package com.masgzy.anything.data

import com.masgzy.anything.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查 —— GitHub Releases API（alpha24）。
 *
 * 设计要点：
 *  - 用 `releases?per_page=1` 而非 `releases/latest`：本项目所有发布均标记
 *    prerelease（--prerelease），而 latest 端点会跳过 prerelease 返回 404；
 *  - 零依赖：HttpsURLConnection + org.json（Android 自带），不引 HTTP/JSON 库；
 *  - 仅在协程里经 [Dispatchers.IO] 调用，8 秒连接/读取超时，异常由调用方兜底；
 *  - 版本比较用结构化元组而非 ImageToolbox 的"拍平成整数"法 —— 拍平法在
 *    alpha9→alpha10 进位时位数不同会误判（10019 padEnd 后 > 100110），
 *    元组逐段比较对任意位数/未来 beta、rc、正式版都正确。
 */
object UpdateRepository {

    const val REPO_URL = "https://github.com/masgzy/AnythingMobile"

    private const val API_RELEASES =
        "https://api.github.com/repos/masgzy/AnythingMobile/releases?per_page=1"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** 远端最新一条 Release（本项目的发布均为 prerelease，取列表第一条即最新）。 */
    data class ReleaseInfo(
        val tagName: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
    )

    /**
     * 拉取远端最新 Release。
     * @return 成功返回 [ReleaseInfo]；网络/解析失败抛出异常（调用方兜底为「检查失败」）。
     */
    suspend fun fetchLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = (URL(API_RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            // GitHub API 要求 UA，缺失会 403
            setRequestProperty("User-Agent", "AnythingMobile/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                error("GitHub API HTTP $code")
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(json)
            if (arr.length() == 0) error("仓库暂无任何 Release")
            val o = arr.getJSONObject(0) as JSONObject
            ReleaseInfo(
                tagName = o.optString("tag_name").ifBlank { "unknown" },
                name = o.optString("name"),
                body = o.optString("body"),
                htmlUrl = o.optString("html_url").ifBlank { REPO_URL },
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 远端版本是否比当前版本更新。
     *
     * 解析规则（对 `1.0.0-alpha23` 与 tag `v1.0.0-alpha23` 一视同仁）：
     *  - 去掉 `v`/`V` 前缀，按 `-` 拆成「数字段 + 预发布段」；
     *  - 数字段 1.0.0 逐段转 Int 比较；
     *  - 预发布段映射 stage：alpha=1 / beta=2 / rc=3 / 正式版=4（正式版 > 一切预发布），
     *    同 stage 再比数字（alpha9 < alpha10，拍平法在此会翻车）；
     *  - 无法解析的字符串不抛错，返回 false（视为无需更新，宁可漏报不误弹）。
     */
    fun isNewer(remote: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val r = parseVersion(remote) ?: return false
        val c = parseVersion(current) ?: return false
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrNull(i) ?: 0
            val cv = c.getOrNull(i) ?: 0
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** `v1.0.0-alpha23` -> [1, 0, 0, 1, 23]；`1.0.0` -> [1, 0, 0, 4, 0]。 */
    private fun parseVersion(raw: String): List<Int>? {
        val v = raw.trim().removePrefix("v").removePrefix("V")
        val (numericPart, stagePart) = if ('-' in v) {
            v.substringBefore('-') to v.substringAfter('-', "")
        } else {
            v to ""
        }
        val numeric = numericPart.split('.').map { it.toIntOrNull() ?: return null }
        if (numeric.size !in 1..3) return null
        val (stage, stageNum) = when {
            stagePart.isEmpty() -> 4 to 0
            stagePart.startsWith("alpha") ->
                1 to (stagePart.removePrefix("alpha").toIntOrNull() ?: 0)
            stagePart.startsWith("beta") ->
                2 to (stagePart.removePrefix("beta").toIntOrNull() ?: 0)
            stagePart.startsWith("rc") ->
                3 to (stagePart.removePrefix("rc").toIntOrNull() ?: 0)
            else -> return null
        }
        return numeric + stage + stageNum
    }
}
