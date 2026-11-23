package com.godot.game

import android.content.Context
import android.text.Html
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Loads Steam Workshop collections ("合集") and flattens them into downloadable [SteamWorkshopCatalog.Item]s.
 *
 * Resolution order:
 * 1. `ISteamRemoteStorage/GetCollectionDetails/v1` WebAPI (routed through the shared compatible-access client).
 * 2. Steam Community `sharedfiles/filedetails/?id=<collection>` HTML page fallback.
 *
 * Both paths reuse [SteamWorkshopDirectAccess] so the Workshop compatible-access preference applies.
 * Nested collections are flattened up to [MAX_COLLECTION_DEPTH]; children are enriched with the
 * batched published-file-details API from [SteamWorkshopCatalog.loadDetails] when a catalog is available.
 */
class SteamWorkshopCollections(
    private val context: Context,
    private val catalog: SteamWorkshopCatalog?,
) {
    data class Collection(
        val publishedFileId: String,
        val title: String,
        val children: List<SteamWorkshopCatalog.Item>,
        val source: String,
    )

    fun loadCollection(collectionId: String): Collection {
        val id = collectionId.trim()
        if (!isValidWorkshopPublishedFileId(id)) {
            throw IOException(context.getString(R.string.workshop_collection_invalid))
        }
        val visited = linkedSetOf(id)
        val parsed = loadParsedCollection(id, 0, visited)
            ?: throw IOException(context.getString(R.string.workshop_collection_load_failed))
        val shells = parsed.children
            .asSequence()
            .filter { it.publishedFileId.isNotBlank() && it.publishedFileId != "0" && it.fileType != COLLECTION_FILE_TYPE }
            .map { workshopChildShell(it.publishedFileId) }
            .distinctBy { it.publishedFileId }
            .take(MAX_COLLECTION_ITEMS)
            .toList()
        return Collection(
            publishedFileId = id,
            title = parsed.title.ifBlank { "Workshop $id" },
            children = enrichChildren(shells),
            source = parsed.source,
        )
    }

    private fun loadParsedCollection(
        id: String,
        depth: Int,
        visited: MutableSet<String>,
    ): ParsedCollection? {
        val parsed = loadCollectionDetailsResponse(id) ?: loadCollectionCommunityPage(id) ?: return null
        if (depth >= MAX_COLLECTION_DEPTH) {
            return parsed
        }
        val nestedIds = parsed.children
            .filter { it.fileType == COLLECTION_FILE_TYPE }
            .map { it.publishedFileId }
        if (nestedIds.isEmpty()) {
            return parsed
        }
        val children = parsed.children
            .filter { it.fileType != COLLECTION_FILE_TYPE }
            .toMutableList()
        for (nestedId in nestedIds) {
            if (!visited.add(nestedId)) {
                continue
            }
            val nested = runCatching { loadParsedCollection(nestedId, depth + 1, visited) }.getOrNull() ?: continue
            children.addAll(nested.children)
        }
        return parsed.copy(children = children)
    }

    private fun loadCollectionDetailsResponse(id: String): ParsedCollection? = runCatching {
        val body = FormBody.Builder()
            .add("collectioncount", "1")
            .add("publishedfileids[0]", id)
            .build()
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/")
            .post(body)
            .build()
        createCollectionClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam collection details request failed: HTTP ${response.code}")
            }
            parseSteamWorkshopCollectionDetailsResponse(
                response.body?.string().orEmpty(),
                id,
                SOURCE_WEBAPI,
            ) ?: throw IOException("Steam collection details response did not include id $id")
        }
    }.getOrNull()

    private fun loadCollectionCommunityPage(id: String): ParsedCollection? = runCatching {
        val url = "https://steamcommunity.com/".toHttpUrl().newBuilder()
            .addPathSegment("sharedfiles")
            .addPathSegment("filedetails")
            .addQueryParameter("id", id)
            .addQueryParameter("l", "schinese")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        createCollectionClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam collection page request failed: HTTP ${response.code}")
            }
            parseSteamWorkshopCollectionPage(
                response.body?.string().orEmpty(),
                id,
                SOURCE_COMMUNITY_PAGE,
            ) ?: throw IOException("Steam collection page did not list any child item for id $id")
        }
    }.getOrNull()

    private fun enrichChildren(shells: List<SteamWorkshopCatalog.Item>): List<SteamWorkshopCatalog.Item> {
        if (shells.isEmpty()) {
            return shells
        }
        val details = LinkedHashMap<String, SteamWorkshopCatalog.Item>()
        var index = 0
        while (index < shells.size) {
            val end = minOf(index + DETAIL_CHUNK, shells.size)
            val chunk = shells.subList(index, end).map { it.publishedFileId }
            index = end
            runCatching { catalog?.loadDetails(chunk) }
                .getOrNull()
                ?.let(details::putAll)
        }
        if (details.isEmpty()) {
            return shells
        }
        return shells.map { shell ->
            details[shell.publishedFileId]?.let { detail ->
                detail.copy(
                    title = detail.title.ifBlank { shell.title },
                    previewUrl = detail.previewUrl.ifBlank { shell.previewUrl },
                )
            } ?: shell
        }
    }

    private fun workshopChildShell(publishedFileId: String): SteamWorkshopCatalog.Item =
        SteamWorkshopCatalog.Item(
            appId = SteamWorkshopPreferences.DEFAULT_APP_ID,
            publishedFileId = publishedFileId,
            title = "Workshop $publishedFileId",
            authorName = "",
            description = "",
            previewUrl = "",
            creatorSteamId = 0L,
            fileSizeBytes = 0L,
            subscriptions = 0,
            views = 0,
            timeUpdatedEpochSeconds = 0L,
        )

    private fun createCollectionClient(): OkHttpClient =
        SteamWorkshopDirectAccess.buildClient(context) {
            connectTimeout(12, TimeUnit.SECONDS)
            readTimeout(18, TimeUnit.SECONDS)
            writeTimeout(12, TimeUnit.SECONDS)
            callTimeout(25, TimeUnit.SECONDS)
            protocols(listOf(Protocol.HTTP_1_1))
            connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            retryOnConnectionFailure(true)
        }

    companion object {
        // Steam marks collection children with file_type=2 in GetCollectionDetails responses.
        private const val COLLECTION_FILE_TYPE = 2
        private const val MAX_COLLECTION_DEPTH = 3
        private const val MAX_COLLECTION_ITEMS = 500
        private const val DETAIL_CHUNK = 50
        private const val USER_AGENT = "WorkshopOnAndroid/1.0"
        const val SOURCE_WEBAPI = "collection_details_webapi"
        const val SOURCE_COMMUNITY_PAGE = "collection_community_page"

        @JvmStatic
        fun parseSteamWorkshopCollectionDetailsResponse(
            payload: String,
            collectionId: String,
            source: String,
        ): ParsedCollection? {
            if (payload.isBlank()) {
                return null
            }
            val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val array = root.optJSONObject("response")?.optJSONArray("publishedfiledetails") ?: return null
            var entry: JSONObject? = null
            for (index in 0 until array.length()) {
                val candidate = array.optJSONObject(index) ?: continue
                if (candidate.optString("publishedfileid") == collectionId) {
                    entry = candidate
                    break
                }
            }
            entry ?: return null
            if (entry.optInt("result", 0) != 1) {
                return null
            }
            val children = mutableListOf<ParsedCollectionRef>()
            val childArray = entry.optJSONArray("children") ?: JSONArray()
            for (index in 0 until childArray.length()) {
                val child = childArray.optJSONObject(index) ?: continue
                val childId = child.optString("publishedfileid", "").trim()
                if (childId.isBlank() || childId == "0" || !isValidWorkshopPublishedFileId(childId)) {
                    continue
                }
                children.add(ParsedCollectionRef(childId, child.optInt("file_type", 0)))
            }
            return ParsedCollection(collectionId, entry.optString("title", "").trim(), children, source)
        }

        @JvmStatic
        fun parseSteamWorkshopCollectionPage(
            payload: String,
            collectionId: String,
            source: String,
        ): ParsedCollection? {
            if (payload.isBlank()) {
                return null
            }
            val refs = LinkedHashSet<String>()
            val childrenBlock = extractDivInnerHtmlById(payload, "collectionChildren")
            if (childrenBlock != null) {
                filedetailsIdRegex.findAll(childrenBlock).forEach { match ->
                    match.groupValues.getOrNull(1)?.let(::normalizeChildId)?.let(refs::add)
                }
            }
            if (refs.isEmpty()) {
                val segments = collectionItemMarkerRegex.split(payload)
                for (segmentIndex in 1 until segments.size) {
                    filedetailsIdRegex.find(segments[segmentIndex])
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let(::normalizeChildId)
                        ?.let(refs::add)
                }
            }
            refs.remove(collectionId)
            if (refs.isEmpty()) {
                return null
            }
            val children = refs.map { ParsedCollectionRef(it, 0) }
            return ParsedCollection(collectionId, extractCollectionTitle(payload), children, source)
        }

        private fun extractCollectionTitle(payload: String): String {
            var title = ogTitleRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::decodeHtmlText)
                .orEmpty()
            if (title.contains("::")) {
                val parts = title.split("::", limit = 2)
                val prefix = parts.getOrNull(0).orEmpty()
                if (prefix.contains("Workshop") || prefix.contains("创意工坊") || prefix.contains("創意工坊")) {
                    title = parts.getOrNull(1).orEmpty()
                }
            }
            if (title.isBlank()) {
                title = workshopItemTitleRegex.find(payload)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::decodeHtmlText)
                    .orEmpty()
            }
            return title.trim()
        }

        private fun normalizeChildId(value: String): String? =
            value.takeIf(String::isNotBlank)
                ?.takeIf { it.length <= 20 }
                ?.takeIf { it.all(Char::isDigit) }
                ?.takeIf { it.any { digit -> digit != '0' } }

        private fun isValidWorkshopPublishedFileId(value: String): Boolean = normalizeChildId(value) != null

        private fun decodeHtmlText(value: String): String =
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun extractDivInnerHtmlById(payload: String, id: String): String? {
            val openingTag = Regex(
                """<div\b[^>]*\bid="${Regex.escape(id)}"[^>]*>""",
                RegexOption.IGNORE_CASE,
            ).find(payload) ?: return null
            return extractDivInnerHtml(payload, openingTag.value)
        }

        private fun extractDivInnerHtml(payload: String, openingTag: String): String? {
            val start = payload.indexOf(openingTag)
            if (start < 0) {
                return null
            }
            var cursor = start + openingTag.length
            var depth = 1
            while (cursor < payload.length) {
                val nextOpen = payload.indexOf("<div", cursor, ignoreCase = true).takeIf { it >= 0 }
                val nextClose = payload.indexOf("</div", cursor, ignoreCase = true).takeIf { it >= 0 }
                val nextIndex = listOfNotNull(nextOpen, nextClose).minOrNull() ?: break
                if (nextIndex == nextOpen) {
                    depth += 1
                    cursor = nextIndex + 4
                    continue
                }
                depth -= 1
                if (depth == 0) {
                    return payload.substring(start + openingTag.length, nextIndex)
                }
                cursor = nextIndex + 5
            }
            return null
        }

        private val filedetailsIdRegex = Regex(
            """filedetails/\?[^"'&>]*?\bid=(\d+)""",
        )
        private val collectionItemMarkerRegex = Regex(
            """class="collectionItem"""",
            RegexOption.IGNORE_CASE,
        )
        private val ogTitleRegex = Regex(
            """<meta\b[^>]*property="og:title"[^>]*content="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        private val workshopItemTitleRegex = Regex(
            """<div\b[^>]*class="workshopItemTitle[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    }
}

data class ParsedCollectionRef(
    val publishedFileId: String,
    val fileType: Int,
)

data class ParsedCollection(
    val publishedFileId: String,
    val title: String,
    val children: List<ParsedCollectionRef>,
    val source: String,
)
