package ireader.kolnovel

import io.ktor.client.request.*
import io.ktor.client.statement.*
import ireader.core.log.Log
import ireader.core.source.Dependencies
import ireader.core.source.SourceFactory
import ireader.core.source.findInstance
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Command
import ireader.core.source.model.CommandList
import ireader.core.source.model.Filter
import ireader.core.source.model.FilterList
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.Page
import ireader.core.source.model.Text
import com.fleeksoft.ksoup.Ksoup
import tachiyomix.annotations.Extension
import tachiyomix.annotations.AutoSourceId
import tachiyomix.annotations.GenerateTests
import tachiyomix.annotations.TestFixture
import tachiyomix.annotations.TestExpectations

@Extension
@AutoSourceId(seed = "KolNovel")
@GenerateTests(
    unitTests = true,
    integrationTests = false,
    searchQuery = "مانهوا",
    minSearchResults = 1
)
@TestFixture(
    novelUrl = "https://kolnovel.com/series/48hours-a-day/",
    chapterUrl = "https://kolnovel.com/shaag2448hours-a-dayz435ggye-100093/",
    expectedTitle = "48 ساعة باليوم",
    expectedMinChapters = 100
)
@TestExpectations(
    minLatestNovels = 5,
    minChapters = 100,
    supportsPagination = true,
    requiresLogin = false
)
abstract class KolNovel(private val deps: Dependencies) : SourceFactory(deps = deps) {

    override val lang: String get() = "ar"
    override val baseUrl: String get() = "https://kolnovel.com"
    override val id: Long get() = KolNovelSourceId.ID
    override val name: String get() = "KolNovel"

    override fun getFilters(): FilterList = listOf(
        Filter.Title(),
        Filter.Sort("Sort By:", arrayOf("Latest", "Popular")),
        Filter.Select("Type", arrayOf("All", "Japanese", "Korean", "English", "Chinese")),
    )

    override fun getCommands(): CommandList = listOf(
        Command.Detail.Fetch(),
        Command.Content.Fetch(),
        Command.Chapter.Fetch(),
    )

    override suspend fun getMangaList(sort: Listing?, page: Int): MangasPageInfo {
        return try {
            val response = client.get(requestBuilder("$baseUrl/series/?order=update&page=$page"))
            val body = response.bodyAsText()
            val doc = Ksoup.parse(body)
            val mangaList = doc.select(".post-title a, .novel-title a, h3 a").mapNotNull { el ->
                val title = el.text().trim()
                val href = el.attr("href")
                if (href.isBlank() || title.isBlank()) return@mapNotNull null
                val slug = href.substringAfter("/series/").substringBefore("/").substringBefore("?")
                if (slug.isBlank()) return@mapNotNull null
                val cover = el.closest(".post, .novel-item, .card")?.selectFirst("img")?.attr("src") ?: ""
                MangaInfo(key = "$baseUrl/series/$slug/", title = title, cover = cover)
            }.distinctBy { it.key }
            MangasPageInfo(mangaList, mangaList.isNotEmpty())
        } catch (e: Exception) {
            Log.error { "Error fetching manga list: ${e.message}" }
            MangasPageInfo(emptyList(), false)
        }
    }

    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        val titleFilter = filters.findInstance<Filter.Title>()
        val sortFilter = filters.findInstance<Filter.Sort>()
        val selectFilters = filters.filterIsInstance<Filter.Select>()

        val searchQuery = titleFilter?.value?.takeIf { it.isNotBlank() }
        if (searchQuery != null) {
            return try {
                val response = client.get(requestBuilder("$baseUrl/?s=$searchQuery"))
                val body = response.bodyAsText()
                val doc = Ksoup.parse(body)
                val mangaList = doc.select(".post-title a, .novel-title a, h3 a").mapNotNull { el ->
                    val title = el.text().trim()
                    val href = el.attr("href")
                    if (href.isBlank() || title.isBlank()) return@mapNotNull null
                    val slug = href.substringAfter("/series/").substringBefore("/").substringBefore("?")
                    if (slug.isBlank()) return@mapNotNull null
                    val cover = el.closest(".post, .novel-item, .card")?.selectFirst("img")?.attr("src") ?: ""
                    MangaInfo(key = "$baseUrl/series/$slug/", title = title, cover = cover)
                }.distinctBy { it.key }
                MangasPageInfo(mangaList, mangaList.isNotEmpty())
            } catch (e: Exception) { MangasPageInfo(emptyList(), false) }
        }

        val sortPath = sortFilter?.value?.index?.let { index ->
            when (index) {
                1 -> "order=popular"
                else -> "order=update"
            }
        } ?: "order=update"

        return try {
            val response = client.get(requestBuilder("$baseUrl/series/?$sortPath&page=$page"))
            val body = response.bodyAsText()
            val doc = Ksoup.parse(body)
            val mangaList = doc.select(".post-title a, .novel-title a, h3 a").mapNotNull { el ->
                val title = el.text().trim()
                val href = el.attr("href")
                if (href.isBlank() || title.isBlank()) return@mapNotNull null
                val slug = href.substringAfter("/series/").substringBefore("/").substringBefore("?")
                if (slug.isBlank()) return@mapNotNull null
                val cover = el.closest(".post, .novel-item, .card")?.selectFirst("img")?.attr("src") ?: ""
                MangaInfo(key = "$baseUrl/series/$slug/", title = title, cover = cover)
            }.distinctBy { it.key }
            MangasPageInfo(mangaList, mangaList.isNotEmpty())
        } catch (e: Exception) { MangasPageInfo(emptyList(), false) }
    }

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo {
        commands.findInstance<Command.Detail.Fetch>()?.let { cmd ->
            if (cmd.html.isNotBlank()) return parseDetailsFromHtml(cmd.html, manga)
        }

        val html = try {
            val browserResult = deps.httpClients.browser.fetch(
                url = manga.key,
                selector = ".summary__content, .description-summary",
                timeout = 30000
            )
            if (browserResult.isSuccess && browserResult.responseBody.isNotBlank()) {
                browserResult.responseBody
            } else {
                val response = client.get(requestBuilder(manga.key))
                response.bodyAsText()
            }
        } catch (e: Exception) {
            val response = client.get(requestBuilder(manga.key))
            response.bodyAsText()
        }

        return parseDetailsFromHtml(html, manga)
    }

    private fun parseDetailsFromHtml(html: String, manga: MangaInfo): MangaInfo {
        val doc = Ksoup.parse(html)
        val scrapedTitle = doc.selectFirst(".post-title h1, h1")?.text()
        val title = if (!scrapedTitle.isNullOrBlank() && !scrapedTitle.contains("Loading", ignoreCase = true)) scrapedTitle else manga.title
        val cover = doc.selectFirst(".summary_image img, meta[property=og:image]")?.attr("src") ?: manga.cover
        val description = doc.selectFirst(".summary__content, .description-summary")?.text() ?: ""
        val author = doc.selectFirst(".author a, .summary-content .author")?.text() ?: ""
        return manga.copy(title = title, cover = cover, description = description, author = author)
    }

    override suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*>>): List<ChapterInfo> {
        val slug = manga.key.substringAfter("/series/").substringBefore("/")
        commands.findInstance<Command.Chapter.Fetch>()?.let { cmd ->
            if (cmd.html.isNotBlank()) return parseChaptersFromHtml(cmd.html, slug)
        }

        return try {
            val response = client.get(requestBuilder(manga.key))
            val body = response.bodyAsText()
            parseChaptersFromHtml(body, slug)
        } catch (e: Exception) { emptyList() }
    }

    private fun parseChaptersFromHtml(html: String, slug: String): List<ChapterInfo> {
        val doc = Ksoup.parse(html)
        val chapters = mutableListOf<ChapterInfo>()
        doc.select("a[href*='/shaag'], a[href*='/chapter/']").forEach { link ->
            val href = link.attr("href")
            val linkText = link.text().trim()
            if (linkText.isBlank() || linkText.contains("Start Reading", ignoreCase = true)) return@forEach
            val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
            chapters.add(ChapterInfo(name = linkText, key = fullUrl))
        }
        return chapters.distinctBy { it.key }
    }

    override suspend fun getPageList(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        commands.findInstance<Command.Content.Fetch>()?.let { cmd ->
            if (cmd.html.isNotBlank()) return parseContentFromHtml(cmd.html)
        }

        return try {
            val html = try {
                val browserResult = deps.httpClients.browser.fetch(
                    url = chapter.key,
                    selector = ".reading-content, .text-left, .chapter-content",
                    timeout = 30000
                )
                if (browserResult.isSuccess && browserResult.responseBody.isNotBlank()) {
                    browserResult.responseBody
                } else {
                    val response = client.get(requestBuilder(chapter.key))
                    response.bodyAsText()
                }
            } catch (e: Exception) {
                val response = client.get(requestBuilder(chapter.key))
                response.bodyAsText()
            }
            parseContentFromHtml(html)
        } catch (e: Exception) {
            listOf(Text("Chapter content not available."))
        }
    }

    private fun parseContentFromHtml(html: String): List<Page> {
        val doc = Ksoup.parse(html)

        // Try to find the main content container
        val contentDiv = doc.selectFirst(".reading-content .text-left, .reading-content, .chapter-content, .entry-content")

        if (contentDiv != null) {
            // Remove script, style, and hidden elements
            contentDiv.select("script, style, .hidden, .d-none, iframe, .code-block, .wp-block-spacer, noscript").remove()

            val paragraphs = contentDiv.select("p")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .filter { it.length > 3 }
                .distinct()
            if (paragraphs.isNotEmpty()) return paragraphs.map { Text(it) }

            val text = contentDiv.text().trim()
            if (text.isNotBlank()) {
                return text.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .distinct()
                    .map { Text(it) }
            }
        }

        // Fallback: try to find content in article body only
        val article = doc.selectFirst("article, .post-body, .single-content")
        if (article != null) {
            article.select("script, style, .hidden, d-none, iframe, nav, footer, header, .sidebar, .comments, .navigation").remove()
            val paragraphs = article.select("p")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && it.length > 3 }
                .distinct()
            if (paragraphs.isNotEmpty()) return paragraphs.map { Text(it) }
        }

        return listOf(Text("المحتوى غير متوفر حالياً."))
    }
}
