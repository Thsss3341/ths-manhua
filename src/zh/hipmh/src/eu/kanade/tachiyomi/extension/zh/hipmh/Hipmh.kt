package eu.kanade.tachiyomi.extension.zh.hipmh

import android.content.SharedPreferences
import android.util.Base64
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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.time.Instant

/**
 * 嬉皮漫画 (m.hipmh.com). Its catalogue mostly comes from official platforms such as 快看漫画 and
 * 腾讯动漫, without site watermarks. Its images match those of the qTcms mirrors (gugu5, yueman,
 * mh160), missing panels included, so it is a cleaner copy rather than a more complete one.
 * Everything comes from the site's JSON API; the page image list is obfuscated and decoded by
 * [ImageListDecoder].
 *
 * Manga ids ("mid") look like `bTo3MDU1-zhe-yi-shi-wo-yao-dang-zhi-zun-7048`: base64url("m:7055")
 * followed by the slug. The API only accepts the base64 part.
 */
@Source
abstract class Hipmh :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(::retryOnNetworkError)

    // Connections to the site are sometimes reset mid-request, which fails a manga in a
    // library update. Requests here are all GETs, so retrying them is safe. A failed page image
    // is retried on the other image line straight away, since both lines serve the same files.
    private fun retryOnNetworkError(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var attempt = 1
        while (true) {
            val otherLine = otherImageLine(request)
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || otherLine == null || attempt >= MAX_ATTEMPTS) return response
                response.close()
                request = otherLine
            } catch (e: IOException) {
                if (request.method != "GET" || chain.call().isCanceled() || attempt >= MAX_ATTEMPTS) throw e
                if (otherLine != null) request = otherLine else Thread.sleep(RETRY_DELAY_MS * attempt)
            }
            attempt++
        }
    }

    private fun otherImageLine(request: Request): Request? {
        val host = OTHER_IMAGE_HOST[request.url.host] ?: return null
        return request.newBuilder().url(request.url.newBuilder().host(host).build()).build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchMangaList(page, FilterList(SortFilter(SORT_POPULAR)))

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchMangaList(page, FilterList(SortFilter(SORT_UPDATED)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return fetchMangaList(page, filters)

        val url = "$API_URL/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", PAGE_SIZE.toString())
            .build()
        val result = fetchApi<SearchResultDto>(url)
        val mangas = result.data.map { mangaOf(it.id, it.title, it.cover) }
        return MangasPage(mangas, hasNextPage = result.page < result.totalPages)
    }

    private suspend fun fetchMangaList(page: Int, filters: FilterList): MangasPage {
        val url = "$API_URL/v1/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", filters.firstInstanceOrNull<SortFilter>()?.selected ?: "popular")
            filters.firstInstanceOrNull<CategoryFilter>()?.selected?.let { addQueryParameter("category", it) }
            filters.firstInstanceOrNull<StatusFilter>()?.selected?.let { addQueryParameter("status", it) }
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", PAGE_SIZE.toString())
        }.build()
        val result = fetchApi<MangaListDto>(url)
        val mangas = result.items.map { mangaOf(it.mid, it.title, it.cover) }
        return MangasPage(mangas, hasNextPage = result.page < result.totalPages)
    }

    private fun mangaOf(mid: String, title: String, cover: String?) = SManga.create().apply {
        url = "/works/$mid"
        this.title = title
        thumbnail_url = coverUrl(cover)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.dropWhile { it in LOCALE_PREFIXES }
        if (segments.size < 2 || segments[0] != "works") return null
        val mangaUrl = "/works/${segments[1]}"
        return fetchDetails(mangaUrl).apply { this.url = mangaUrl }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(
        manga = if (fetchDetails) fetchDetails(manga.url) else manga,
        chapters = if (fetchChapters) fetchChapters(manga.url) else chapters,
    )

    private suspend fun fetchDetails(mangaUrl: String): SManga {
        val url = "$API_URL/v1/manga".toHttpUrl().newBuilder()
            .addQueryParameter("mid", apiMangaId(mangaUrl))
            .build()
        val detail = fetchApi<MangaDetailDto>(url)
        return SManga.create().apply {
            title = detail.title
            thumbnail_url = coverUrl(detail.cover)
            author = detail.authors.joinToString { it.name }.takeIf { it.isNotEmpty() }
            genre = (detail.categories + detail.genres + detail.tags)
                .map { it.name }
                .distinct()
                .joinToString()
                .takeIf { it.isNotEmpty() }
            status = when (detail.status) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            val altTitles = detail.altTitles.filter { it != detail.title }
            description = listOfNotNull(
                detail.description?.trim()?.takeIf { it.isNotEmpty() },
                altTitles.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "别名：\n"),
            ).joinToString("\n\n").takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun fetchChapters(mangaUrl: String): List<SChapter> {
        val mid = apiMangaId(mangaUrl)
        val chapters = mutableListOf<ChapterDto>()
        var page = 1
        do {
            val url = "$API_URL/v1/manga/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("mid", mid)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", CHAPTER_PAGE_SIZE.toString())
                .addQueryParameter("order", "desc")
                .build()
            val result = fetchApi<ChapterListDto>(url)
            chapters += result.items
        } while (page++ < result.totalPages && result.items.isNotEmpty())

        val listed = chapters.distinctBy { it.hid }.map { chapter ->
            SChapter.create().apply {
                url = "/chapter/${chapter.hid}"
                name = chapterName(chapter.title, chapter.number)
                date_upload = Instant.tryParse(chapter.createdAt)
            }
        }
        val newest = chapters.firstOrNull() ?: return listed
        val newer = runCatching { fetchNewerChapters(newest.hid, chapters.mapTo(HashSet()) { it.hid }) }.getOrDefault(emptyList())
        return newer + listed
    }

    // The API caches chapter lists server-side for many hours, so a list can lack the newest
    // chapters (e.g. it still ends at 第568话 when 第570话 is out). Chapter data is fresh and links to
    // the next chapter, so follow those links from the newest listed chapter. Returns newest first.
    private suspend fun fetchNewerChapters(newestListedHid: String, known: MutableSet<String>): List<SChapter> {
        val newer = mutableListOf<SChapter>()
        var current = fetchChapterData(newestListedHid)
        repeat(MAX_NEWER_CHAPTERS) {
            val nextHid = current.nextHid?.takeIf { it.isNotEmpty() && known.add(it) } ?: return newer.asReversed()
            current = fetchChapterData(nextHid)
            newer += SChapter.create().apply {
                url = "/chapter/$nextHid"
                name = chapterName(current.title, current.number)
            }
        }
        return newer.asReversed()
    }

    private fun chapterName(title: String?, number: Float?): String = title?.trim()?.takeIf { it.isNotEmpty() }
        ?: "第${number?.toString()?.removeSuffix(".0") ?: "?"}话"

    private suspend fun fetchChapterData(hid: String): ChapterDataDto {
        val url = "$API_URL/v2/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("hid", apiChapterId(hid))
            .build()
        return fetchApi<ChapterDataDto>(url)
    }

    override fun getChapterUrl(chapter: SChapter): String = READER_URL + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val data = fetchChapterData(chapter.url.substringAfterLast('/'))
        // The reader offers two image lines and switches to a separate host on each for chapters
        // served from "line 9".
        val line = ImageLine.entries.firstOrNull { it.name == preferences.getString(PREF_IMAGE_LINE, null) } ?: ImageLine.CLOUDFLARE
        val imageHost = if (data.line == 9) line.line9Host else line.host
        return ImageListDecoder.decode(data).mapIndexed { index, path ->
            val imageUrl = when {
                path.startsWith("http://") || path.startsWith("https://") -> path
                path.startsWith("//") -> "https:$path"
                else -> "https://$imageHost/" + path.removePrefix("/")
            }
            Page(index, imageUrl = imageUrl)
        }
    }

    private suspend inline fun <reified T> fetchApi(url: HttpUrl): T {
        val response = client.get(url).parseAs<ResponseDto<T>>()
        if (response.code != 200) throw Exception("嬉皮漫画 API: ${response.code} ${response.message}")
        return response.data ?: throw Exception("嬉皮漫画 API: ${response.message.ifEmpty { "empty response" }}")
    }

    private fun coverUrl(path: String?): String? = when {
        path.isNullOrEmpty() -> null
        path.startsWith("http") -> path
        else -> COVER_HOST + "/" + path.removePrefix("/")
    }

    // "/works/bTo3MDU1-zhe-yi-shi..." -> "bTo3MDU1". Base64 of "m:<digits>" never contains '-'.
    private fun apiMangaId(mangaUrl: String): String = mangaUrl.substringAfterLast('/').substringBefore('-')

    // The site links chapters by base64url("m:<manga>-c:<chapter>") + "-" + suffix, while the API
    // expects base64url("c:<chapter>") + "-" + suffix.
    private fun apiChapterId(hid: String): String {
        val head = hid.substringBefore('-')
        val suffix = hid.substringAfter('-', "")
        val decoded = String(base64UrlDecode(head), Charsets.UTF_8)
        val chapterPart = decoded.substringAfter('-', "")
        if (!decoded.startsWith("m:") || !chapterPart.startsWith("c:")) return hid
        val encoded = base64UrlEncode(chapterPart.toByteArray(Charsets.UTF_8))
        return if (suffix.isEmpty()) encoded else "$encoded-$suffix"
    }

    private fun base64UrlDecode(value: String): ByteArray = Base64.decode(value, BASE64_URL_FLAGS)

    private fun base64UrlEncode(value: ByteArray): String = Base64.encodeToString(value, BASE64_URL_FLAGS)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).run {
            key = PREF_IMAGE_LINE
            title = "图片线路"
            entries = ImageLine.entries.map { it.label }.toTypedArray()
            entryValues = ImageLine.entries.map { it.name }.toTypedArray()
            summary = "%s\n图片加载失败时会自动改用另一条线路。"
            setDefaultValue(ImageLine.CLOUDFLARE.name)
            screen.addPreference(this)
        }
    }

    // The reader's "线路2" and "线路1". Line 1 (Tencent EdgeOne) is the site's default but often
    // stalls for about 10 s and then resets the connection.
    private enum class ImageLine(val label: String, val host: String, val line9Host: String) {
        CLOUDFLARE("线路2（Cloudflare，推荐）", "hip-cf-1.s3imgs.top", "hip-cf-s1.s3imgs.top"),
        TENCENT("线路1（腾讯云）", "hip-tx-1.s3imgs.top", "hip-tx-s1.s3imgs.top"),
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        CategoryFilter(),
        StatusFilter(),
    )

    companion object {
        private const val API_URL = "https://hipapi1.s3file.top"
        private const val READER_URL = "https://reader.hipmh.top"
        private const val COVER_HOST = "https://cover.s3imgs.top"
        private const val PREF_IMAGE_LINE = "imageLine"
        private val OTHER_IMAGE_HOST = mapOf(
            ImageLine.CLOUDFLARE.host to ImageLine.TENCENT.host,
            ImageLine.TENCENT.host to ImageLine.CLOUDFLARE.host,
            ImageLine.CLOUDFLARE.line9Host to ImageLine.TENCENT.line9Host,
            ImageLine.TENCENT.line9Host to ImageLine.CLOUDFLARE.line9Host,
        )
        private const val PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 50
        private const val MAX_NEWER_CHAPTERS = 20
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
        private val LOCALE_PREFIXES = setOf("en", "ja", "ko")
        private const val BASE64_URL_FLAGS =
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    }
}
