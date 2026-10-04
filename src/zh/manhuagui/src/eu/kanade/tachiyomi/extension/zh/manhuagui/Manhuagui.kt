package eu.kanade.tachiyomi.extension.zh.manhuagui

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.lzstring.LZString
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Manhuagui :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val malTitles by lazy { MalTitles(network.client, preferences) }

    private val imageServer = arrayOf("https://i.hamreus.com", "https://cf.hamreus.com")
    private val mobileWebsiteUrl: String
        get() = baseUrl.replace("www.", "m.").replace("tw.", "m.")

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

    override fun OkHttpClient.Builder.configureClient() = apply {
        if (getShowR18()) {
            addNetworkInterceptor(AddCookieHeaderInterceptor())
        }
    }
        .rateLimit(
            preferences.getString(MAINSITE_RATELIMIT_PREF, MAINSITE_RATELIMIT_DEFAULT_VALUE)!!.toInt(),
            10.seconds,
        ) { it.host == baseUrl.toHttpUrl().host }
        .rateLimit(
            preferences.getString(IMAGE_CDN_RATELIMIT_PREF, IMAGE_CDN_RATELIMIT_DEFAULT_VALUE)!!.toInt(),
        ) { url -> url.host in imageServer.map { it.toHttpUrl().host } }

    inner class AddCookieHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (chain.request().url.host == baseUrl.toHttpUrl().host) {
                val originalCookies = chain.request().header("Cookie") ?: ""
                if (originalCookies.isNotEmpty() && !originalCookies.contains("isAdult=1")) {
                    return chain.proceed(
                        chain.request().newBuilder()
                            .header("Cookie", "$originalCookies; isAdult=1")
                            .build(),
                    )
                }
            }
            return chain.proceed(chain.request())
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(client.get("$baseUrl/list/view_p$page.html").asJsoup())

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("ul#contList > li").map(::mangaFromElement)
        val hasNextPage = document.selectFirst("span.current + a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(client.get("$baseUrl/list/update_p$page.html").asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/s/${query}_p$page.html"
        } else {
            val params = filters.filterIsInstance<UriPartFilter>()
                .filter { it !is SortFilter }
                .map { it.toUriPart() }
                .filter { it.isNotEmpty() }
                .joinToString("_")

            val sortOrder = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: ""

            when {
                sortOrder.isEmpty() -> "$baseUrl/list${params.toPathOrEmpty()}/index_p$page.html"
                sortOrder.startsWith(RANK_PREFIX) -> {
                    "$baseUrl/rank${params.toPathOrEmpty()}".let {
                        if (it.endsWith("rank")) {
                            "$it/${sortOrder.removePrefix(RANK_PREFIX).toPathOrEmpty("", ".html")}"
                        } else {
                            "$it${sortOrder.removePrefix(RANK_PREFIX).toPathOrEmpty("_")}.html"
                        }
                    }
                }
                else -> "$baseUrl/list${params.toPathOrEmpty()}/${sortOrder}_p$page.html"
            }
        }

        val response = client.get(url)
        val path = response.request.url.encodedPath
        val document = response.asJsoup()
        return when {
            path.startsWith("/s/") -> {
                val mangas = document.select("div.book-result > ul > li").map(::searchMangaFromElement)
                val hasNextPage = document.selectFirst("span.current + a") != null
                MangasPage(mangas, hasNextPage)
            }
            path.startsWith("/rank/") -> {
                val mangas = document.select("td.rank-title").map {
                    SManga.create().apply {
                        this.url = it.selectFirst("a")?.attr("href") ?: ""
                        title = it.selectFirst("a")?.text() ?: ""
                    }
                }
                MangasPage(mangas, false)
            }
            else -> parseMangaList(document)
        }
    }

    private fun String.toPathOrEmpty(prefix: String = "/", suffix: String = ""): String = if (isEmpty()) {
        this
    } else {
        "$prefix$this$suffix"
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst("a.bcover")?.let {
            url = it.attr("href")
            title = it.attr("title")
            val thumbnailElement = it.selectFirst("img")
            if (thumbnailElement != null) {
                thumbnail_url = if (thumbnailElement.hasAttr("src")) {
                    thumbnailElement.absUrl("src")
                } else {
                    thumbnailElement.absUrl("data-src")
                }
            }
        }
    }

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst("div.book-detail")?.let {
            val a = it.selectFirst("dl > dt > a")
            if (a != null) {
                url = a.attr("href")
                title = a.attr("title")
            }
        }
        thumbnail_url = element.selectFirst("div.book-cover > a.bcover > img")?.absUrl("src")
    }

    override fun getMangaUrl(manga: SManga): String = mobileWebsiteUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith("manhuagui.com") && !url.host.endsWith("mhgui.com")) return null
        val id = url.pathSegments.getOrNull(1) ?: return null

        val document = client.get("$baseUrl/comic/$id").asJsoup()
        return SManga.create().apply {
            parseDetails(document)
            this.url = "/comic/$id/"
        }.also { applyMalTitle(it, document) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        val bid = Regex("""\d+""").find(manga.url)?.value
        if (fetchDetails && bid != null) {
            GlobalScope.launch(Dispatchers.IO) {
                delay(1000L)
                val ajaxHeaders = headersBuilder()
                    .set("Referer", manga.url)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .build()
                runCatching {
                    client.post(
                        "$baseUrl/tools/submit_ajax.ashx?action=user_check_login",
                        ajaxHeaders,
                        FormBody.Builder().build(),
                        ensureSuccess = false,
                    ).close()
                }.onFailure { it.printStackTrace() }
                runCatching {
                    client.get(
                        "$baseUrl/tools/vote.ashx?act=get&bid=$bid",
                        ajaxHeaders,
                        ensureSuccess = false,
                    ).close()
                }.onFailure { it.printStackTrace() }
            }
        }

        // Only touch the details when asked: the app may apply them anyway, and a chapter-only
        // update would otherwise put back the Chinese title of a manga renamed for MAL.
        if (fetchDetails) {
            manga.parseDetails(document)
            applyMalTitle(manga, document)
        }

        return SMangaUpdate(manga, parseChapterList(document))
    }

    private suspend fun applyMalTitle(manga: SManga, document: Document) {
        if (!malTitles.isEnabled) return
        // Bangumi only knows Simplified Chinese titles, and the tw mirrors show Traditional ones.
        val simplified = if (baseUrl.toHttpUrl().host.startsWith("tw.")) {
            runCatching { client.get(baseUrl.replaceFirst("://tw.", "://www.") + manga.url).asJsoup() }.getOrNull()
        } else {
            null
        }
        malTitles.apply(manga, (simplified ?: document).toMangaInfo())
    }

    private fun Document.toMangaInfo(): MangaInfo {
        val details = select("div.book-detail > ul.detail-list span")
        fun field(label: String) = details.firstOrNull { it.selectFirst("strong")?.text()?.startsWith(label) == true }
        val aliases = field("漫画别名")?.let { span ->
            span.select("a").map { it.text() }.ifEmpty { span.ownText().split(',', '，') }
        }.orEmpty()
        return MangaInfo(
            title = selectFirst("div.book-title > h1")?.text().orEmpty(),
            otherNames = (listOfNotNull(selectFirst("div.book-title > h2")?.text()) + aliases)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "暂无" },
            year = field("出品年代")?.text()?.let { YEAR_REGEX.find(it)?.value?.toIntOrNull() },
            authors = field("漫画作者")?.select("a")?.map { it.text() }.orEmpty(),
        )
    }

    private fun SManga.parseDetails(document: Document) {
        title = document.selectFirst("div.book-title > h1:nth-child(1)")?.text() ?: ""
        description = document.selectFirst("div#intro-all")?.text() ?: ""
        thumbnail_url = document.selectFirst("p.hcover > img")?.absUrl("src")
        author = document.select("span:contains(漫画作者) > a , span:contains(漫畫作者) > a").text().replace(" ", ", ")
        genre = document.select("span:contains(漫画剧情) > a , span:contains(漫畫劇情) > a").text().replace(" ", ", ")
        status = when (document.selectFirst("div.book-detail > ul.detail-list > li.status > span > span")?.text()) {
            "连载中", "連載中" -> SManga.ONGOING
            "已完结", "已完結" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        val hiddenEncryptedChapterList = document.selectFirst("#__VIEWSTATE")
        if (hiddenEncryptedChapterList != null) {
            if (getShowR18()) {
                val decodedHiddenChapterList = LZString.decompressFromBase64(hiddenEncryptedChapterList.`val`())
                val hiddenChapterList = Jsoup.parseBodyFragment(decodedHiddenChapterList, document.location())

                document.selectFirst("#erroraudit_show")?.replaceWith(hiddenChapterList)
                hiddenEncryptedChapterList.remove()
            } else {
                error("您需要打开R18作品显示开关并重启软件才能阅读此作品")
            }
        }
        val latestChapterHref = document.selectFirst("div.book-detail > ul.detail-list > li.status > span > a.blue")?.attr("href")

        // The site groups chapters into sections such as 单话, 单行本 and 番外篇, each list preceded
        // by an <h4> heading. Mihon has no chapter groups, so the heading goes in the scanlator
        // field: it shows on every chapter and Mihon's scanlator filter can hide whole sections.
        val sectionList = document.select("[id^=chapter-list-]").map { section ->
            val heading = section.previousElementSiblings().firstOrNull { it.tagName() == "h4" }?.text()?.trim()
            heading.orEmpty() to section
        }
        // Volumes and extras restart their numbering (第16卷, 番外篇19), which Mihon would read as
        // chapter numbers and send to trackers. When the manga also has a 话 section, mark them
        // as unnumbered so only real chapters count.
        val hasEpisodeSection = sectionList.any { (heading, _) -> heading.isEpisodeSection() }
        sectionList.forEach { (heading, section) ->
            val unnumbered = hasEpisodeSection && !heading.isEpisodeSection()
            val pageList = section.select("ul").reversed()
            pageList.forEach { page ->
                val chapterList = page.select("li > a.status0")
                chapterList.forEach {
                    val currentChapter = SChapter.create()
                    currentChapter.url = it.attr("href")

                    val titleAttr = it.attr("title")
                    currentChapter.name = titleAttr.ifEmpty { it.selectFirst("span")?.ownText() ?: "" }
                    currentChapter.scanlator = heading.ifEmpty { null }
                    if (unnumbered) currentChapter.chapter_number = UNNUMBERED_CHAPTER

                    if (currentChapter.url == latestChapterHref) {
                        val dateElement = document.select("div.book-detail > ul.detail-list > li.status > span > span.red").last()
                        if (dateElement != null) {
                            currentChapter.date_upload = dateFormat.tryParseDate(dateElement.text())
                        }
                    }
                    chapters.add(currentChapter)
                }
            }
        }

        // The page only dates the newest chapter, so apps can't sort the others by upload date.
        // Chapter ids grow with each upload across all sections, so ordering by id puts the list
        // in upload order, newest first: the app's "by source" sort then follows upload order.
        if (preferences.getBoolean(UPLOAD_ORDER_PREF, true)) {
            return chapters.sortedByDescending { CHAPTER_ID_REGEX.find(it.url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L }
        }
        return chapters
    }

    private fun String.isEpisodeSection() = contains('话') || contains('話')

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        if (document.selectFirst("#erroraudit_show") != null && !getShowR18()) {
            error("R18作品显示开关未开启或未生效")
        }

        val html = document.outerHtml()
        val imgCode = packedRegex.find(html)?.groupValues?.get(1)?.let {
            it.replace(packedContentRegex) { match ->
                val lzs = match.groupValues[1]
                val decoded = LZString.decompressFromBase64(lzs)
                "'$decoded'.split('|')"
            }
        } ?: throw Exception("Failed to find image code")

        val imgDecode = Unpacker.unpack(singleQuoteRegex.replace(imgCode, "-"))
        val imgJsonStr = blockCcArgRegex.find(imgDecode)?.groupValues?.get(0) ?: throw Exception("Failed to extract JSON from parsed code")
        val imageJson = imgJsonStr.parseAs<Comic>()

        return imageJson.files.orEmpty().mapIndexed { i, imgStr ->
            val imgurl = "${imageServer[0]}${imageJson.path}$imgStr?e=${imageJson.sl?.e}&m=${imageJson.sl?.m}"
            Page(i, imageUrl = imgurl)
        }
    }

    override fun Headers.Builder.configureHeaders() = set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
        .set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).run {
            key = MAINSITE_RATELIMIT_PREF
            title = MAINSITE_RATELIMIT_PREF_TITLE
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY
            summary = MAINSITE_RATELIMIT_PREF_SUMMARY

            setDefaultValue(MAINSITE_RATELIMIT_DEFAULT_VALUE)
            screen.addPreference(this)
        }

        ListPreference(screen.context).run {
            key = IMAGE_CDN_RATELIMIT_PREF
            title = IMAGE_CDN_RATELIMIT_PREF_TITLE
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY
            summary = IMAGE_CDN_RATELIMIT_PREF_SUMMARY

            setDefaultValue(IMAGE_CDN_RATELIMIT_DEFAULT_VALUE)
            screen.addPreference(this)
        }

        CheckBoxPreference(screen.context).run {
            key = SHOW_R18_PREF
            title = SHOW_R18_PREF_TITLE
            summary = SHOW_R18_PREF_SUMMARY
            screen.addPreference(this)
        }

        CheckBoxPreference(screen.context).run {
            key = UPLOAD_ORDER_PREF
            title = "章节按上传顺序排列"
            summary = "开启：所有分区（单话、单行本、番外篇……）的章节按上传先后排列，App里选「按来源排序」即为按上传时间排序。" +
                "关闭：按网站的分区顺序排列。更改后需下拉刷新漫画。"
            setDefaultValue(true)
            screen.addPreference(this)
        }

        ListPreference(screen.context).run {
            key = MalTitles.PREF_KEY_TITLE_LANGUAGE
            title = "标题语言（方便MAL追踪）"
            entries = TitleLanguage.entries.map { it.label }.toTypedArray()
            entryValues = TitleLanguage.entries.map { it.name }.toTypedArray()
            setDefaultValue(TitleLanguage.CHINESE.name)
            summary = titleLanguageSummary(malTitles.titleLanguage)
            setOnPreferenceChangeListener { _, value ->
                summary = titleLanguageSummary(TitleLanguage.entries.first { it.name == value })
                true
            }
            screen.addPreference(this)
        }
    }

    private fun titleLanguageSummary(language: TitleLanguage): String = when (language) {
        TitleLanguage.CHINESE -> language.label
        TitleLanguage.CHINESE_WITH_ID ->
            "${language.label}\n打开漫画页面时查找MAL条目，在简介顶部显示MAL标题和「id:12345」。" +
                "把「id:12345」粘贴到MAL追踪的搜索框即可精确匹配。标题不完全相同的条目会注明「可能不准确」。"
        else ->
            "${language.label}\n打开漫画页面时改名为MAL上的标题，MAL追踪可直接搜索到；只在标题完全匹配时改名，否则保留中文标题。" +
                "已收藏的漫画需在App设置→高级里开启「Update library manga titles to match source」，再下拉刷新该漫画。"
    }

    private fun getShowR18(): Boolean = preferences.getBoolean(SHOW_R18_PREF, false)

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        LocaleFilter(),
        GenreFilter(),
        ReaderFilter(),
        PublishDateFilter(),
        FirstLetterFilter(),
        StatusFilter(),
    )

    companion object {
        private val YEAR_REGEX = Regex("""\d{4}""")
        private val CHAPTER_ID_REGEX = Regex("""/(\d+)\.html""")
        private const val UPLOAD_ORDER_PREF = "chapterUploadOrder"

        // Mihon keeps -2 as "no chapter number" instead of parsing one from the name.
        private const val UNNUMBERED_CHAPTER = -2f

        private const val SHOW_R18_PREF = "showR18Default"
        private const val SHOW_R18_PREF_TITLE = "显示R18作品"
        private const val SHOW_R18_PREF_SUMMARY = "请确认您的IP不在漫画柜的屏蔽列表内，例如中国大陆IP。需要重启软件以生效。\n开启后如需关闭，需要到Tachiyomi高级设置内清除Cookies后才能生效。"

        private const val MAINSITE_RATELIMIT_PREF = "mainSiteRatelimitPreference"
        private const val MAINSITE_RATELIMIT_PREF_TITLE = "主站每十秒连接数限制"
        private const val MAINSITE_RATELIMIT_PREF_SUMMARY = "此值影响更新书架时发起连接请求的数量。调低此值可能减小IP被屏蔽的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s"
        private const val MAINSITE_RATELIMIT_DEFAULT_VALUE = "10"

        private const val IMAGE_CDN_RATELIMIT_PREF = "imgCDNRatelimitPreference"
        private const val IMAGE_CDN_RATELIMIT_PREF_TITLE = "图片CDN每秒连接数限制"
        private const val IMAGE_CDN_RATELIMIT_PREF_SUMMARY = "此值影响加载图片时发起连接请求的数量。调低此值可能减小IP被屏蔽的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s"
        private const val IMAGE_CDN_RATELIMIT_DEFAULT_VALUE = "4"

        const val RANK_PREFIX = "rank_"

        private val ENTRIES_ARRAY = (1..10).map { i -> i.toString() }.toTypedArray()

        @Suppress("RegExpRedundantEscape")
        private val packedRegex = Regex("""window\[".*?"\](\(.*\)\s*\{[\s\S]+\}\s*\(.*\))""")

        @Suppress("RegExpRedundantEscape")
        private val blockCcArgRegex = Regex("""\{.*\}""")

        private val packedContentRegex = Regex("""['"]([0-9A-Za-z+/=]+)['"]\[['"].*?['"]]\(['"].*?['"]\)""")

        private val singleQuoteRegex = Regex("""\\'""")
    }
}
