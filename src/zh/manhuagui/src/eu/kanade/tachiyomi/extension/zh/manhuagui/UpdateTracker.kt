package eu.kanade.tachiyomi.extension.zh.manhuagui

import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneId

/**
 * Lets a library update skip manga that haven't changed. 漫画柜 blocks IPs that open too many manga
 * pages, and a library update used to open the page of every manga in the library.
 *
 * The site's update list (/list/update_pN.html) shows the most recently updated manga first, 42 a
 * page, each with the day it was updated: about 4 to 5 pages a day. Reading it back to the day a
 * manga was last checked tells whether that manga changed since, so only changed manga need their
 * page opened.
 */
internal class UpdateTracker(
    private val preferences: SharedPreferences,
    private val fetchUpdateListPage: suspend (Int) -> Document,
) {
    private val mutex = Mutex()
    private var listReadAt = 0L
    private var nextPage = 1
    private val updatedOn = HashMap<String, LocalDate>()

    // Every manga updated after this day is in [updatedOn]; null before the first page is read.
    private var fullyListedAfter: LocalDate? = null

    fun today(): LocalDate = LocalDate.now(SITE_ZONE)

    fun lastChecked(comicId: String): LocalDate? = preferences.getString(KEY_PREFIX + comicId, null)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun markChecked(comicId: String, day: LocalDate) {
        preferences.edit().putString(KEY_PREFIX + comicId, day.toString()).apply()
    }

    /**
     * Whether the update list shows no update of the manga on [since] or later. False when it
     * can't tell, for example when [since] is further back than the list is read.
     */
    suspend fun unchangedSince(comicId: String, since: LocalDate): Boolean = mutex.withLock {
        if (since < today().minusDays(MAX_DAYS_BACK)) return false
        val now = System.currentTimeMillis()
        if (now - listReadAt > LIST_TTL_MS) {
            listReadAt = now
            nextPage = 1
            updatedOn.clear()
            fullyListedAfter = null
        }
        while (fullyListedAfter.let { it == null || it >= since }) {
            if (nextPage > MAX_PAGES) return false
            val entries = parse(fetchUpdateListPage(nextPage++))
            if (entries.isEmpty()) return false
            entries.forEach { (id, day) -> updatedOn.merge(id, day) { a, b -> maxOf(a, b) } }
            // Each page also carries a pinned manga with an old date, so take the median day as
            // where the page ends rather than the oldest one.
            fullyListedAfter = entries.map { it.second }.sorted()[entries.size / 2]
        }
        updatedOn[comicId].let { it == null || it < since }
    }

    private fun parse(document: Document): List<Pair<String, LocalDate>> = document.select("#contList > li").mapNotNull { item ->
        val id = item.selectFirst("a.bcover")?.attr("href")?.let { COMIC_ID_REGEX.find(it) }?.groupValues?.get(1)
        val day = item.selectFirst("span.updateon")?.text()?.let { DATE_REGEX.find(it) }?.value
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (id != null && day != null) id to day else null
    }

    companion object {
        private const val KEY_PREFIX = "lastChecked:"
        private val SITE_ZONE = ZoneId.of("Asia/Shanghai")

        // About two weeks of the list; a manga last checked before that has its page opened.
        private const val MAX_DAYS_BACK = 14L
        private const val MAX_PAGES = 80

        // One library update reads the list once.
        private const val LIST_TTL_MS = 30 * 60 * 1000L

        private val COMIC_ID_REGEX = Regex("""/comic/(\d+)/""")
        private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
