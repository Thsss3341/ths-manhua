package eu.kanade.tachiyomi.extension.zh.yueman

import android.util.Base64
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.charset.Charset
import java.text.Normalizer

/**
 * 漫画大全 (yueman) runs qingtiancms (qTcms). Its current domains are published at http://reman.cc.
 * The mobile site is used because the desktop one sits behind a slider captcha. The site's own
 * search is disabled/captcha-blocked, so keyword search runs against an index of the whole
 * catalogue that this repository's "Search index" workflow rebuilds daily. If the index can't be
 * loaded, it falls back to the search of the sister directory 古古漫画 (gugu5.cc), whose manga pages
 * link to yueman chapters by the same manga id.
 */
@Source
abstract class Yueman : KeiSource() {

    // The site sits behind an anti-CC firewall that answers fast bursts of requests, such as a
    // library update, with a slide verification page, so keep requests to it slow.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 4) { it.host.endsWith(GUGU5_HOST) }
        .rateLimit(permits = 1) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage = parseItemBoxList(fetchDocument("$baseUrl/manhua/o/m_waphit.html"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseItemBoxList(fetchDocument("$baseUrl/manhua/o/m_waprecent.html"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            return runCatching { searchIndex(page, query) }.getOrNull() ?: searchViaGugu5(page, query.trim())
        }

        val categoryId = filters.firstInstanceOrNull<CategoryFilter>()?.selectedId
            ?: return getPopularManga(page)
        val path = if (page > 1) "/mmm/$categoryId/$page.html" else "/mmm/$categoryId/"
        return parseCategoryList(fetchDocument(baseUrl + path))
    }

    private fun parseItemBoxList(document: Document): MangasPage {
        val mangas = document.select(".itemBox").mapNotNull { element ->
            mangaFromElement(element, ".itemTxt a.title", ".itemImg img")
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    private fun parseCategoryList(document: Document): MangasPage {
        val mangas = document.select("#dmList li").mapNotNull { element ->
            mangaFromElement(element, "dt a", "p.cover img")
        }
        val hasNextPage = document.selectFirst("#pager a:containsOwn(下一页)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element, linkSelector: String, coverSelector: String): SManga? {
        val link = element.selectFirst(linkSelector) ?: return null
        val id = MANGA_ID_REGEX.find(link.attr("href"))?.groupValues?.get(1) ?: return null
        return SManga.create().apply {
            url = mangaPath(id)
            title = link.text()
            thumbnail_url = element.selectFirst(coverSelector)?.absUrl("src")
        }
    }

    private suspend fun searchIndex(page: Int, query: String): MangasPage {
        val terms = query.split(WHITESPACE_REGEX).map(::searchKey).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return MangasPage(emptyList(), hasNextPage = false)
        val whole = terms.joinToString("")
        // The index is ordered by last update; the stable sort keeps that order within each rank.
        val matches = loadSearchIndex()
            .filter { entry -> terms.all { it in entry.key } }
            .sortedBy { entry ->
                when {
                    entry.key == whole -> 0
                    entry.key.startsWith(whole) -> 1
                    else -> 2
                }
            }
        val from = (page - 1) * SEARCH_PAGE_SIZE
        val mangas = matches.drop(from).take(SEARCH_PAGE_SIZE).map { entry ->
            SManga.create().apply {
                url = mangaPath(entry.id)
                title = entry.title
                thumbnail_url = entry.cover.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("http")) it else COVER_HOST + it }
            }
        }
        return MangasPage(mangas, hasNextPage = from + SEARCH_PAGE_SIZE < matches.size)
    }

    private val searchIndexMutex = Mutex()
    private var cachedSearchIndex: List<IndexEntry>? = null
    private var searchIndexLoadedAt = 0L

    private suspend fun loadSearchIndex(): List<IndexEntry> = searchIndexMutex.withLock {
        val cached = cachedSearchIndex
        if (cached != null && System.currentTimeMillis() - searchIndexLoadedAt < SEARCH_INDEX_TTL_MS) {
            return@withLock cached
        }
        val entries = client.get(SEARCH_INDEX_URL).use { it.body.string() }
            .lineSequence()
            .mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size < 2) return@mapNotNull null
                IndexEntry(id = fields[0], title = fields[1], cover = fields.getOrElse(2) { "" }, key = searchKey(fields[1]))
            }
            .toList()
        check(entries.isNotEmpty()) { "Empty search index" }
        cachedSearchIndex = entries
        searchIndexLoadedAt = System.currentTimeMillis()
        entries
    }

    // Case-, width- and punctuation-insensitive, so "天命大反派" matches "我！天命大反派".
    private fun searchKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase()
        .filter { it.isLetterOrDigit() }

    private class IndexEntry(val id: String, val title: String, val cover: String, val key: String)

    private suspend fun searchViaGugu5(page: Int, query: String): MangasPage {
        val url = GUGU5_URL.toHttpUrl().newBuilder()
            .addPathSegments("statics/search.aspx")
            .addQueryParameter("key", query)
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
            .toString()
        val document = fetchDocument(url)
        val mangas = coroutineScope {
            document.select(".cy_list_mh ul").map { element ->
                async {
                    val link = element.selectFirst("li.title a") ?: return@async null
                    val id = findYuemanId(link.absUrl("href")) ?: return@async null
                    SManga.create().apply {
                        this.url = mangaPath(id)
                        title = link.text()
                        thumbnail_url = element.selectFirst("a.pic img")?.absUrl("src")
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val hasNextPage = document.selectFirst(".NewPages a:containsOwn(下一页)") != null
        return MangasPage(mangas, hasNextPage)
    }

    // gugu5 chapter lists link to yueman (/p/{id}/...) or to gugu5's own reader (/n/{id}/...); both use
    // yueman's manga id. Manga that only link to other sites (e.g. ac.qq.com) have no id to find.
    // Only the chapter list is read: legacy pages also link to other manga's chapters in a sidebar.
    private suspend fun findYuemanId(gugu5MangaUrl: String): String? = fetchDocument(gugu5MangaUrl)
        .select(GUGU5_CHAPTER_LINK_SELECTOR)
        .firstNotNullOfOrNull { CHAPTER_ID_REGEX.find(it.attr("href"))?.groupValues?.get(1) }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = when {
            url.host.endsWith(GUGU5_HOST) -> {
                if (!url.encodedPath.startsWith("/o/")) return null
                findYuemanId(url.toString())
            }
            url.host.substringAfter('.') == baseUrl.toHttpUrl().host.substringAfter('.') ->
                MANGA_ID_REGEX.find(url.encodedPath)?.groupValues?.get(1)
            else -> null
        } ?: return null
        val mangaUrl = mangaPath(id)
        return parseDetails(fetchDocument(baseUrl + mangaUrl)).apply { this.url = mangaUrl }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchDocument(baseUrl + manga.url)
        return SMangaUpdate(
            manga = parseDetails(document),
            chapters = parseChapters(document),
        )
    }

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("#comicName")?.text()
            ?: document.title().substringAfter("《").substringBefore("》")
        thumbnail_url = document.selectFirst("#Cover img")?.absUrl("src")
        val info = document.select(".sub_r p.txtItme")
        author = info.getOrNull(1)?.text()?.takeIf { it.isNotEmpty() }
        val categoryLine = info.firstOrNull { it.selectFirst("a.pd") != null }
        genre = categoryLine?.selectFirst("a.pd")?.text()
        status = when {
            categoryLine == null -> SManga.UNKNOWN
            categoryLine.ownText().contains("连载") -> SManga.ONGOING
            categoryLine.ownText().contains("完结") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        description = document.selectFirst("p.txtDesc")?.text()?.removePrefix("介绍:")?.trim()
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val script = document.select("script").firstOrNull { it.data().contains("initIntroData(") }?.data()
            ?: return emptyList()
        // Chapter names can contain raw tabs, which strict JSON rejects; outside strings they are whitespace.
        val json = CHAPTER_DATA_REGEX.find(script)?.groupValues?.get(1)?.replace(JSON_CONTROL_CHARS, " ")
            ?: return emptyList()
        return json.parseAs<List<ChapterGroupDto>>()
            .flatMap { it.data }
            .mapNotNull { chapter ->
                val path = chapter.url.toHttpUrlOrNull()?.encodedPath ?: return@mapNotNull null
                SChapter.create().apply {
                    url = path
                    name = chapter.name.trim()
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val html = fetchHtml(baseUrl + chapter.url)
        val encoded = IMAGE_LIST_REGEX.find(html)?.groupValues?.get(1)
            ?: throw Exception("找不到图片列表，页面结构可能已更改")
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        return decoded.split(IMAGE_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith(PROMO_IMAGE) }
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = realImageUrl(imageUrl)) }
    }

    // Mirrors f_qTcms_Pic_curUrl_realpic() in the site's show.js, which moves images off dead hosts.
    private fun realImageUrl(url: String): String {
        val rewritten = IMAGE_HOST_REWRITES.fold(url) { acc, (from, to) -> acc.replace(from, to) }
        val scomic = SCOMIC_REGEX.replace(rewritten, "https://p8.taoman.cc$1")
        // show.js maps these to a mistyped host; the files live on gmh1234 as .webp.
        return WSZWHG_JPG_REGEX.replace(WSZWHG_HOST_REGEX.replace(scomic, "//gmh1234.wszwhg.net/"), "$1.webp")
    }

    // yueman pages declare GB2312 but may contain GBK-only characters; gugu5 pages are UTF-8 or GB2312.
    private suspend fun fetchHtml(url: String): String {
        val bytes = client.get(url, ensureSuccess = false).use { response ->
            val body = response.body.bytes()
            // The firewall's 「滑动验证」 page loads its slider from /huadong_<hash>.js. Passing it once
            // in WebView sets a cookie that the app shares with the extension.
            if (String(body, Charsets.ISO_8859_1).contains(VERIFICATION_SCRIPT)) {
                val site = if (response.request.url.host.endsWith(GUGU5_HOST)) "古古漫画" else "漫画大全"
                throw Exception("${site}要求人机验证：请在${site}中打开WebView（地球图标），完成滑动验证后再试")
            }
            if (!response.isSuccessful) throw Exception("HTTP error ${response.code}")
            body
        }
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val charset = if (GB_CHARSET_REGEX.containsMatchIn(head)) GBK else Charsets.UTF_8
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    private suspend fun fetchDocument(url: String): Document = fetchHtml(url).asJsoup(url)

    private fun mangaPath(id: String) = "/manhua/$id.html"

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(CategoryFilter())

    companion object {
        private const val GUGU5_HOST = "gugu5.cc"
        private const val GUGU5_URL = "http://www.gugu5.cc"
        private const val GUGU5_CHAPTER_LINK_SELECTOR = "ul[id^=mh-chapter-list-ol] li a, #play_0 ul li a"
        private const val SEARCH_INDEX_URL = "https://raw.githubusercontent.com/Thsss3341/ths-manhua/search/yueman.tsv"
        private const val SEARCH_INDEX_TTL_MS = 6 * 60 * 60 * 1000L
        private const val SEARCH_PAGE_SIZE = 50
        private const val COVER_HOST = "http://www.yueman1.cc"
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private const val IMAGE_SEPARATOR = "\$qingtiandy\$"

        // "Please bookmark reman.cc" banner appended to some chapters.
        private const val PROMO_IMAGE = "reman.cc/reman.jpg"
        private const val VERIFICATION_SCRIPT = "/huadong_"
        private val MANGA_ID_REGEX = Regex("""/(?:manhua|p)/(\d+)(?:\.html|/)""")
        private val CHAPTER_ID_REGEX = Regex("""/[pn]/(\d+)/\d+\.html""")
        private val CHAPTER_DATA_REGEX = Regex("""initIntroData\((\[.*])\)""", RegexOption.DOT_MATCHES_ALL)
        private val JSON_CONTROL_CHARS = Regex("""[\t\r\n]""")
        private val IMAGE_HOST_REWRITES = listOf(
            "http://ltpic.sfacg.com" to "http://pic3.sfacg.com",
            "http://mhpic" to "http://t2.taoman.cc/t.php?url=http://mhpic",
            "http://t1.taoman.cc/pic/" to "https://p8.taoman.cc/qTcms_Cache/picls/",
            "http://t1.taoman.cc/picls/" to "https://p8.taoman.cc/qTcms_Cache/picls/",
            "http://t1.reman.cc/pic/" to "https://p8.taoman.cc/qTcms_Cache/picls/",
            "https://t40-1-4.g-mh.online" to "https://t2.taoman.cc",
            "https://c-nd3-1.6wm.top" to "https://t2.taoman.cc",
            "http://f2-img.534zm.com" to "https://t2.taoman.cc",
        )
        private val SCOMIC_REGEX = Regex("""https://s[12]\.[^/]+(/scomic/)""")
        private val WSZWHG_HOST_REGEX = Regex("""//(?:images|gmh1234)\.wszwhg\.net/""")
        private val WSZWHG_JPG_REGEX = Regex("""(wszwhg\.net/[^?#]*)\.jpg(?=[?#]|$)""", RegexOption.IGNORE_CASE)
        private val IMAGE_LIST_REGEX = Regex("""qTcms_S_m_murl_e\s*=\s*"([^"]+)"""")
        private val GBK = Charset.forName("GBK")
        private val GB_CHARSET_REGEX = Regex("""charset\s*=\s*"?(gb2312|gbk)""", RegexOption.IGNORE_CASE)
    }
}
