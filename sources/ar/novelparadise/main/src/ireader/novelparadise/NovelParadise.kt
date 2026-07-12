package ireader.novelparadise

import ireader.core.source.Dependencies
import ireader.core.source.SourceFactory
import ireader.core.source.model.Command
import ireader.core.source.model.CommandList
import ireader.core.source.model.Filter
import ireader.core.source.model.FilterList
import ireader.core.source.model.Page
import ireader.core.source.model.Text
import com.fleeksoft.ksoup.nodes.Document
import tachiyomix.annotations.Extension


@Extension
abstract class NovelParadise(private val deps: Dependencies) : SourceFactory(
    deps = deps,
) {

    override val lang: String
        get() = "ar"
    override val baseUrl: String
        get() = "https://novelsparadise.site"
    override val id: Long
        get() = 50
    override val name: String
        get() = "NovelParadise"

    override fun getFilters(): FilterList = listOf(
        Filter.Title()
    )

    override fun getCommands(): CommandList {
        return listOf(
            Command.Detail.Fetch(),
            Command.Chapter.Fetch(),
            Command.Content.Fetch(),
        )
    }

    fun fetcherCreator(name: String, endpoint: String): BaseExploreFetcher {
        return BaseExploreFetcher(
            name,
            endpoint = "/series/?page={page}&status=&type=&order=$name",
            selector = ".maindet",
            nameSelector = ".mdinfo h2 a",
            coverSelector = "img",
            coverAtt = "src",
            linkSelector = ".mdinfo h2 a",
            linkAtt = "href",
            maxPage = 50,
        )
    }

    fun search(): BaseExploreFetcher {
        return BaseExploreFetcher(
            "Search",
            endpoint = "/page/{page}/?s={query}",
            selector = ".maindet",
            nameSelector = ".mdinfo h2 a",
            coverSelector = "img",
            coverAtt = "src",
            linkSelector = ".mdinfo h2 a",
            linkAtt = "href",
            maxPage = 50,
            type = SourceFactory.Type.Search
        )
    }

    override val exploreFetchers: List<BaseExploreFetcher>
        get() = listOf(
            fetcherCreator("Last Update", "update"),
            search()
        )

    override val detailFetcher: Detail
        get() = SourceFactory.Detail(
            nameSelector = "h1.entry-title",
            coverSelector = ".sertothumb img",
            coverAtt = "src",
            authorBookSelector = ".serl:nth-child(4) .serval",
            categorySelector = ".sertogenre a",
            descriptionSelector = ".sersys p",
        )

    override val chapterFetcher: Chapters
        get() = SourceFactory.Chapters(
            selector = ".eplisterfull ul li",
            nameSelector = ".epl-num",
            linkSelector = "a",
            linkAtt = "href",
        )

    override val contentFetcher: Content
        get() = SourceFactory.Content(
            pageTitleSelector = "h1.entry-title",
            pageContentSelector = ".entry-content",
        )

    override fun pageContentParse(document: Document): List<Page> {
        // Remove noise elements first
        document.select("script, style, noscript, iframe, nav, footer, header, .sidebar, .comments, .navigation, .ads, .ad-placeholder, .ad-placeholder, [class*=ad-], [id*=ad-]").remove()

        val content = mutableListOf<String>()

        // Extract title
        val title = document.selectFirst("h1.entry-title, h1.chapter-heading, h2.entry-title, .chapter-heading")
            ?.text()?.trim()
        if (!title.isNullOrBlank()) {
            content.add(title)
        }

        // Try multiple selectors to find chapter content paragraphs
        val paragraphSelectors = listOf(
            ".entry-content p",
            ".chapter-content p",
            ".reading-content p",
            "#chapter-content p",
            ".post-body p",
            ".text-left p",
            "article .entry-content p",
            "article p",
        )

        for (selector in paragraphSelectors) {
            val paragraphs = document.select(selector)
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .filter { it.length > 3 }
                .filter { !it.contains("Loading", ignoreCase = true) }
                .filter { !it.contains("click here", ignoreCase = true) }
                .distinct()
            if (paragraphs.size >= 2) {
                content.addAll(paragraphs)
                return content.map { Text(it) }
            }
        }

        // Fallback: try getting text from content containers
        val containerSelectors = listOf(
            ".entry-content",
            ".chapter-content",
            ".reading-content",
            "#chapter-content",
            "article .entry-content",
            "article",
            ".post-content",
            ".the-content",
        )

        for (selector in containerSelectors) {
            val container = document.selectFirst(selector) ?: continue
            // Remove noise inside the container too
            container.select("script, style, noscript, .ads, [class*=ad-]").remove()

            val text = container.text().trim()
            if (text.isNotBlank() && text.length > 50) {
                val lines = text.split("\n", "\r\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .filter { !it.contains("Loading", ignoreCase = true) }
                    .filter { !it.contains("click here", ignoreCase = true) }
                    .distinct()
                if (lines.isNotEmpty()) {
                    content.addAll(lines)
                    return content.map { Text(it) }
                }
            }
        }

        // Last resort: if we have at least a title, return it
        if (content.isNotEmpty()) {
            return content.map { Text(it) }
        }

        // Absolute fallback: never return empty list
        return listOf(Text("جاري تحميل المحتوى... يرجى المحاولة مرة أخرى"))
    }
}
