package com.example.mkvbase

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class MkvBase : MainAPI() {
    override var mainUrl    = "https://mkvbase.site"
    override var name       = "MkvBase"
    override val hasMainPage = true
    override var lang       = "hi"
    override val hasSearch  = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    data class ApiResponse(
        @JsonProperty("results") val results: List<LinkEntry> = emptyList(),
        @JsonProperty("count")   val count: Int = 0
    )

    data class LinkEntry(
        @JsonProperty("id")         val id: Int,
        @JsonProperty("title")      val title: String,
        @JsonProperty("url")        val url: String,
        @JsonProperty("created_at") val createdAt: String,
        @JsonProperty("status")     val status: String
    )

    private suspend fun fetchLinks(query: String = "", page: Int = 1): List<LinkEntry> {
        val params = buildString {
            if (query.isNotBlank()) append("search=${query.encodeUri()}&")
            append("page=$page")
        }
        return app.get(
            "$mainUrl/api/links?$params",
            headers = mapOf("Referer" to mainUrl)
        ).parsedSafe<ApiResponse>()?.results
            ?.filter { it.status == "active" }
            ?: emptyList()
    }

    override val mainPage = mainPageOf(
        "recent" to "Recent Links"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val entries = fetchLinks(page = page)
        return newHomePageResponse(request.name, entries.map { it.toSearchResponse() }, hasNext = entries.size >= 50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchLinks(query = query).map { it.toSearchResponse() }
    }

    private fun LinkEntry.toSearchResponse(): SearchResponse {
        val cleanTitle = title.replace(Regex("^[^|]+\\|\\s*"), "").trim()
        return newMovieSearchResponse(
            name = cleanTitle.ifBlank { title },
            url  = url,
            type = if (title.contains(Regex("(?i)S\\d{2}E\\d{2}|season|series")))
                       TvType.TvSeries else TvType.Movie
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val title = url.substringAfterLast("/").replace(Regex("[._-]"), " ").trim()
        return newMovieLoadResponse(
            name    = title,
            url     = url,
            type    = TvType.Movie,
            dataUrl = url
        ) {
            this.plot = "Hoster: ${url.substringBefore("/drive/").substringAfterLast("//")}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            data.contains("hubcloud.pw") ||
            data.contains("hubcloud.list") -> resolveHubcloud(data, callback)
            data.contains("titancloud.site") -> resolveTitanCloud(data, callback)
            else -> { loadExtractor(data, mainUrl, subtitleCallback, callback); true }
        }
    }

    private suspend fun resolveHubcloud(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val fileId   = url.substringAfterLast("/")
        val baseHost = "https://${url.removePrefix("https://").substringBefore("/")}"
        for (endpoint in listOf(
            "$baseHost/api/drive?id=$fileId",
            "$baseHost/drive/download?id=$fileId",
            "$baseHost/generate?id=$fileId"
        )) {
            val resp = app.get(endpoint, headers = mapOf("Referer" to url, "X-Requested-With" to "XMLHttpRequest"))
            if (!resp.isSuccessful) continue
            val text = resp.text
            Regex("""https://[^"'\s]+r2\.cloudflarestorage\.com/[^"'\s]+""").find(text)?.value?.let {
                callback(ExtractorLink("HubCloud", "HubCloud [R2]", it, url, getQuality(it), false))
                return true
            }
            Regex("""https://[^"'\s]+\.(mkv|mp4)(?:\?[^"'\s]*)?""").find(text)?.value?.let {
                callback(ExtractorLink("HubCloud", "HubCloud Direct", it, url, getQuality(it), false))
                return true
            }
        }
        runCatching {
            app.post("$baseHost/drive", data = mapOf("id" to fileId, "action" to "generate"),
                headers = mapOf("Referer" to url))
        }.getOrNull()?.document?.select("a[href]")?.forEach { el ->
            val href = el.attr("href")
            if (href.startsWith("http") && el.text().contains(Regex("(?i)download|FSL|10Gbps")))
                callback(ExtractorLink("HubCloud", "HubCloud [${el.text().trim()}]", href, url, getQuality(href), false))
        }
        return true
    }

    private suspend fun resolveTitanCloud(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(url, headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        )).document
        val html = doc.html()
        Regex("""https://[^"'\s]+r2\.cloudflarestorage\.com/[^"'\s]+""").findAll(html).forEach {
            callback(ExtractorLink("TitanCloud", "TitanCloud [R2]", it.value, url, getQuality(it.value), false))
        }
        doc.select("a[href]").forEach { el ->
            val href = el.attr("abs:href")
            if (href.endsWith(".mkv") || href.endsWith(".mp4"))
                callback(ExtractorLink("TitanCloud", "TitanCloud [Direct]", href, url, getQuality(href), false))
        }
        doc.select("a[href*='/zfile/'], a[href*='/file/']").forEach { el ->
            val epUrl = el.attr("abs:href")
            if (epUrl.isNotBlank())
                callback(ExtractorLink("TitanCloud Pack", el.text().take(50).ifBlank { epUrl }, epUrl, url, Qualities.Unknown.value, false))
        }
        return true
    }

    private fun getQuality(url: String): Int = when {
        url.contains("2160p", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080p", true) -> Qualities.P1080.value
        url.contains("720p",  true) -> Qualities.P720.value
        url.contains("480p",  true) -> Qualities.P480.value
        url.contains("360p",  true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun String.encodeUri() = java.net.URLEncoder.encode(this, "UTF-8")
}
