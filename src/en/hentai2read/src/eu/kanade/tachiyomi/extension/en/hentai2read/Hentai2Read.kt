package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.intOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.selectLast
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Hentai2Read : KeiSource() {

    override val supportsFilterFetching = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 2, period = 1.seconds)

    // Unlike the other listings, rankings page with ?page=N.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page > 1) "$baseUrl/ranking?page=$page" else "$baseUrl/ranking"
        return client.get(url).asJsoup().toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get(listingUrl("/browse", page)).asJsoup().toMangasPage()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val tags = filters.filterIsInstance<TagGroup>().flatMap { it.state }
        val included = tags.filter { it.isIncluded() }.map { it.id }
        val excluded = tags.filter { it.isExcluded() }.map { it.id }

        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            filters.firstInstance<TypeFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("type", it) }
            filters.firstInstance<StatusFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("status", it) }
            filters.firstInstance<SortFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("sort", it) }
            if (included.isNotEmpty()) addQueryParameter("with", included.joinToString(","))
            if (excluded.isNotEmpty()) addQueryParameter("without", excluded.joinToString(","))
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return client.get(url).asJsoup().toMangasPage()
    }

    // Returns null when the page isn't a manga page (chapter or unknown URL).
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val document = client.get(url).asJsoup()
        if (document.selectFirst(TITLE_SELECTOR) == null) return null
        return parseMangaDetails(document, document.singleChapterPages()).apply {
            setUrlWithoutDomain(document.selectFirst("link[rel=canonical]")!!.absUrl("href"))
        }
    }

    // Search and tag pages (/category/18-adult, /artist/6197-…) resolve to a listing; anything else is treated as a single manga.
    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host != baseUrl.toHttpUrl().host) return MangasPage(emptyList(), false)

        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val listing = when {
            segments.firstOrNull() == "search" -> url.newBuilder().setQueryParameter("page", page.toString()).build().toString()
            segments.size >= 2 && segments[0] in LISTING_TYPES -> listingUrl("/${segments[0]}/${segments[1]}", page)
            else -> return MangasPage(listOfNotNull(getMangaByUrl(url)), false)
        }
        return client.get(listing).asJsoup().toMangasPage()
    }

    private fun listingUrl(path: String, page: Int) = if (page > 1) "$baseUrl$path/page-$page" else "$baseUrl$path"

    private fun Document.toMangasPage(): MangasPage {
        // The cover badge abbreviates above 1000 ("1.8K pages"), so multi-chapter totals come from the page JSON.
        val pageCounts = parsePageDataOrNull<ListingDto>()?.props?.run { books?.data.orEmpty() + podium + grid }.orEmpty()
            .filter { (it.chapters ?: 0) > 1 }
            .mapNotNull { book -> book.pageCount?.let { book.id to it } }
            .toMap()
        val mangas = select(MANGA_SELECTOR).map { mangaFromElement(it, pageCounts) }
        val hasNextPage = selectFirst(NEXT_PAGE_SELECTOR) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element, pageCounts: Map<Int, Int>) = SManga.create().apply {
        val link = element.selectFirst("h3 a, h2 a")!!
        setUrlWithoutDomain(link.absUrl("href"))
        title = link.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")

        element.selectFirst("a[href^=/artist/]")?.textOrNull()
            ?.takeIf { it != "Unknown" }
            ?.let {
                artist = it
                author = it
            }

        element.attr("data-book").ifEmpty { element.attr("data-peek") }.toIntOrNull()
            ?.let { pageCounts[it] }
            ?.let { count -> memo = buildJsonObject { put(MEMO_PAGE_COUNT, count) } }
    }

    // Details and chapters share one page, so both are always returned.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}").asJsoup()
        val title = document.selectFirst(TITLE_SELECTOR)?.text()
            ?: throw Exception("Manga page not found. Migrate or re-add this entry.")

        // Multi-chapter books have no count on their own page: a details refresh looks up the current one,
        // otherwise the count from browsing or an earlier refresh is kept.
        val pages = document.singleChapterPages()
            ?: (if (fetchDetails) fetchPagesFromSearch(document, title) else null)
            ?: manga.memo[MEMO_PAGE_COUNT]?.intOrNull?.toString()
            ?: manga.description?.let { PAGES_DESC_REGEX.find(it)?.groupValues?.get(1) }?.let(::parsePageCount)?.toString()

        val parsedChapters = parseChapters(document)

        return SMangaUpdate(
            manga = parseMangaDetails(document, pages).apply {
                url = manga.url
                memo = manga.memo
                if (status == SManga.COMPLETED && parsedChapters.size == 1) {
                    update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
                }
            },
            chapters = parsedChapters,
        )
    }

    private fun parseMangaDetails(document: Document, pages: String?) = SManga.create().apply {
        title = document.selectFirst(TITLE_SELECTOR)!!.text()
        thumbnail_url = document.selectFirst("img[alt^=Cover of]")?.absUrl("src")

        val artists = document.select("dl a[href^=/artist/]")
            .map { it.text() }
            .filter { it.isNotEmpty() && it != "Unknown" }
            .joinToString()
            .ifEmpty { null }
        artist = artists
        author = artists

        genre = document.select("ul[aria-label=Tags] a").joinToString { it.text() }
        status = parseStatus(document.infoValue("Status").orEmpty())

        description = buildString {
            fun section(label: String, value: String?) {
                if (value.isNullOrEmpty()) return
                if (isNotEmpty()) append("\n\n")
                append(label, ":\n", value)
            }

            section("Alternative Title", document.selectFirst("$TITLE_SELECTOR + p")?.text())
            section("Type", document.infoValue("Type"))
            section("Pages", pages)
            section("Language", document.infoValue("Language"))
            section("Released", document.infoValue("Released"))
        }
    }

    // The book id comes from the page, so entries stored under pre-redesign URLs are found too.
    private suspend fun fetchPagesFromSearch(document: Document, title: String): String? {
        val id = document.parsePageDataOrNull<BookPageDto>()?.props?.book?.id ?: return null
        return try {
            val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("q", title).build()
            // An error page (e.g. a Cloudflare 403) has no page data, so it yields null instead of throwing.
            client.get(url, ensureSuccess = false).asJsoup().parsePageDataOrNull<ListingDto>()?.props?.books?.data.orEmpty()
                .firstOrNull { it.id == id }?.pageCount?.toString()
        } catch (_: IOException) {
            null // a failed lookup shouldn't break the details refresh
        }
    }

    // Only single-chapter books have numberOfPages in their JSON-LD.
    private fun Document.singleChapterPages(): String? {
        if (select("li[id^=chapter-]").size != 1) return null
        return runCatching {
            selectFirst("script[type=application/ld+json]")
                ?.data()
                ?.parseAs<List<JsonLdDto>>()
                ?.firstOrNull { it.type == "Book" }
                ?.numberOfPages
                ?.toString()
        }.getOrNull()
    }

    private fun Document.infoValue(label: String): String? = select("dl dt")
        .firstOrNull { it.text().equals(label, ignoreCase = true) }
        ?.nextElementSibling()
        ?.textOrNull()

    private fun parseStatus(status: String): Int {
        val value = status.lowercase()
        return when {
            "ongoing" in value -> SManga.ONGOING
            "completed" in value -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapters(document: Document): List<SChapter> = document.select("li[id^=chapter-]").map { li ->
        val link = li.selectFirst("a[href^=/reader/]")!!
        val number = li.selectFirst("span")?.text().orEmpty()

        SChapter.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            name = if (number.isNotEmpty()) "Ch. $number - ${link.text()}" else link.text()
            chapter_number = number.toFloatOrNull() ?: -1f
            // A "New" badge span can sit between the title and the date, so take the last span.
            date_upload = dateFormat.tryParseDate(li.selectLast("a[href^=/reader/] ~ span")?.text(), ZoneOffset.UTC)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()
        return document.parsePageData<ReaderDto>().toPages()
    }

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        TAG_TYPES.map { type -> async { fetchTags(type) } }.awaitAll().flatten()
    }.toJsonElement()

    private suspend fun fetchTags(type: String): List<TagDto> {
        val first = client.get("$baseUrl/tags/$type").asJsoup().parsePageData<TagPageDto>()
        val rest = coroutineScope {
            (2..minOf(first.lastPage, MAX_TAG_PAGES)).map { page ->
                async { client.get("$baseUrl/tags/$type/page-$page").asJsoup().parsePageData<TagPageDto>().tags }
            }.awaitAll()
        }.flatten()
        return first.tags + rest
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val tags = data?.parseAs<List<TagDto>>().orEmpty()
        return hentai2readFilters(tags)
    }

    // Inertia page JSON, embedded in <script data-page="app">.
    private inline fun <reified T> Document.parsePageData(): T {
        val script = selectFirst("script[data-page=app]")?.data()
            ?: throw Exception("Page data not found")
        return script.parseAs<T>()
    }

    private inline fun <reified T> Document.parsePageDataOrNull(): T? = runCatching { parsePageData<T>() }.getOrNull()

    companion object {
        private const val MAX_TAG_PAGES = 20
        private const val MEMO_PAGE_COUNT = "pageCount"

        private const val TITLE_SELECTOR = "h1#book-title"

        // Sponsored tiles use <article data-promo> and are excluded. The ranking's top three aren't cards:
        // they're <li data-peek> items with an h2 title.
        private const val MANGA_SELECTOR = "article[data-card][data-book], ol[aria-label='Top three'] > li[data-peek]"
        private const val NEXT_PAGE_SELECTOR = "nav[aria-label=Pages] a[rel=next]"

        private val LISTING_TYPES = setOf("category", "content", "artist", "parody", "character")

        // "Pages:\n33" saved by an earlier refresh; earlier builds also wrote "1,842" or "1.8K".
        private val PAGES_DESC_REGEX = Regex("""^Pages:\s*([0-9][0-9,]*(?:\.[0-9]+)?\s*[KkMm]?)""", RegexOption.MULTILINE)

        private val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

        // "1,842" -> 1842, "1.8K" -> 1800
        private fun parsePageCount(raw: String): Int? {
            val value = raw.trim().ifEmpty { return null }
            val multiplier = when (value.last().uppercaseChar()) {
                'K' -> 1_000
                'M' -> 1_000_000
                else -> 1
            }
            val number = (if (multiplier > 1) value.dropLast(1) else value)
                .replace(",", "")
                .toDoubleOrNull() ?: return null
            return (number * multiplier).roundToInt()
        }
    }
}
