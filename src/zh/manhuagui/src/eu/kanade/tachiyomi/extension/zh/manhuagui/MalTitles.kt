package eu.kanade.tachiyomi.extension.zh.manhuagui

import android.content.SharedPreferences
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.text.Normalizer
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** How a manga is named, so MyAnimeList's tracker search can find it. */
enum class TitleLanguage(val label: String) {
    CHINESE("中文（漫画柜原标题）"),
    CHINESE_WITH_ID("中文，简介里加上MAL ID"),
    ROMAJI("罗马音（MAL标题）"),
    ENGLISH("英文（没有英文名时用罗马音）"),
}

/** What 漫画柜 says about a manga, in Simplified Chinese. */
class MangaInfo(
    val title: String,
    /** The subtitle and aliases, often the original or an alternative Chinese title. */
    val otherNames: List<String>,
    /** 出品年代. */
    val year: Int?,
    val authors: List<String>,
)

/**
 * The names MyAnimeList knows a manga by. [exact] is false when the match rests on a similar title,
 * the year and the authors rather than on identical titles.
 */
@Serializable
class MalTitle(
    val malId: Int,
    val romaji: String? = null,
    val english: String? = null,
    val exact: Boolean = true,
)

/**
 * Finds the MyAnimeList entry of a 漫画柜 manga, the same way as the ths-anime Anime1 extension:
 * Bangumi (bgm.tv) maps a Chinese title to the original one (间谍过家家 → SPY×FAMILY), and AniList,
 * searched with that, returns the MAL ID and the romaji/English titles MAL uses.
 *
 * Exact matches come first: a Bangumi title equal to one of 漫画柜's names, or an AniList title
 * equal to one of them (the subtitle is often the original title). Failing that, a *likely* match
 * is a Bangumi or AniList entry from the same year with a similar title or the same author. Likely
 * matches are marked in the description and never rename the manga.
 *
 * Results are cached in the source's preferences: exact matches for good, likely matches and
 * misses for a week. Failed requests aren't cached.
 */
class MalTitles(baseClient: OkHttpClient, private val preferences: SharedPreferences) {

    // AniList currently allows 30 requests a minute; Bangumi asks clients to go easy too.
    private val client = baseClient.newBuilder()
        .rateLimit(permits = 1, period = 2.seconds) { it.host == ANILIST_HOST }
        .rateLimit(permits = 2) { it.host == BANGUMI_HOST }
        .build()

    private val headers = Headers.headersOf("User-Agent", USER_AGENT, "Accept", "application/json")

    val titleLanguage: TitleLanguage
        get() = preferences.getString(PREF_KEY_TITLE_LANGUAGE, null)
            ?.let { saved -> TitleLanguage.entries.firstOrNull { it.name == saved } }
            ?: TitleLanguage.CHINESE

    val isEnabled: Boolean get() = titleLanguage != TitleLanguage.CHINESE

    /**
     * Renames [manga] and adds a MAL line to its description according to [titleLanguage].
     * [manga] must hold 漫画柜's own title and description; [info] describes it in Simplified
     * Chinese, which Bangumi needs. Lookup failures are ignored.
     */
    suspend fun apply(manga: SManga, info: MangaInfo) {
        val language = titleLanguage
        if (language == TitleLanguage.CHINESE) return
        val chineseTitle = manga.title
        val mal = runCatching { find(info) }.getOrNull() ?: return

        val renamed = when {
            !mal.exact -> null
            language == TitleLanguage.ROMAJI -> mal.romaji ?: mal.english
            language == TitleLanguage.ENGLISH -> mal.english ?: mal.romaji
            else -> null
        }
        if (renamed != null) manga.title = renamed
        // "id:12345" pasted into MAL's tracker search gives an exact match. It sits on a line of its
        // own, with nothing around it, so copying it doesn't pick up brackets or other text.
        val malLine = buildString {
            append("MAL：", mal.romaji ?: mal.english ?: chineseTitle)
            append("\nid:", mal.malId)
            if (!mal.exact) append("\n", LIKELY_NOTE)
            if (renamed != null) append("\n中文名：", chineseTitle)
        }
        manga.description = listOfNotNull(malLine, manga.description?.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
    }

    private suspend fun find(info: MangaInfo): MalTitle? {
        val cacheKey = CACHE_PREFIX + info.title.loose()
        if (cacheKey == CACHE_PREFIX) return null
        preferences.getString(cacheKey, null)
            ?.let { runCatching { it.parseAs<CacheEntry>() }.getOrNull() }
            ?.takeIf { it.title?.exact == true || System.currentTimeMillis() - it.checkedAt < RECHECK_AFTER_MS }
            ?.let { return it.title }

        val result = Lookup(info).run()
        preferences.edit()
            .putString(cacheKey, CacheEntry(result, System.currentTimeMillis()).toJsonString())
            .apply()
        return result
    }

    /** One lookup, remembering searches so each name is only sent once per site. */
    private inner class Lookup(private val info: MangaInfo) {
        private val candidates = info.candidates()
        private val keys = candidates.mapTo(HashSet()) { it.name.loose() }
        private val bangumiResults = HashMap<String, List<Subject>>()
        private val aniListResults = HashMap<String, List<Media>>()

        suspend fun run(): MalTitle? = exactViaBangumi() ?: exactViaAniList() ?: likelyViaBangumi() ?: likelyViaAniList()

        /** A Bangumi manga titled exactly like one of the names, then its original title on AniList. */
        private suspend fun exactViaBangumi(): MalTitle? {
            for (candidate in candidates.filter { it.name.isChinese() }) {
                val subject = bangumi(candidate.name).firstExactMatch(candidate.name) ?: continue
                val media = exactAniList(subject.name, keys + subject.name.loose() + subject.nameCn.loose()) ?: continue
                // A shortened name ("蝙蝠侠" from "蝙蝠侠：迪伦·道格") can belong to another work.
                if (candidate.shortened && !media.startDate.year.isNear(info.year, SHORTENED_YEAR_TOLERANCE)) continue
                return media.toMalTitle(exact = true)
            }
            return null
        }

        /** An AniList title equal to one of the names: original titles, or Chinese synonyms. */
        private suspend fun exactViaAniList(): MalTitle? {
            for (candidate in candidates) {
                val media = exactAniList(candidate.name, keys) ?: continue
                if (candidate.shortened && !media.startDate.year.isNear(info.year, SHORTENED_YEAR_TOLERANCE)) continue
                return media.toMalTitle(exact = true)
            }
            return null
        }

        /**
         * The Bangumi manga from the same year with the same author or the most similar title,
         * for titles translated differently (关于我转生后成为史莱姆的那件事 vs 关于我转生变成史莱姆这档事).
         */
        private suspend fun likelyViaBangumi(): MalTitle? {
            val subject = candidates.filter { it.name.isChinese() }
                .flatMap { candidate -> bangumi(candidate.name).map { candidate to it } }
                .filter { (_, subject) -> subject.year.isNear(info.year, 1) }
                .map { (candidate, subject) ->
                    Triple(subject.sharesAuthorWith(info.authors), subject.similarityTo(candidate.name), subject)
                }
                .filter { (sameAuthor, similarity, _) -> sameAuthor || similarity >= LIKELY_SIMILARITY }
                .maxWithOrNull(compareBy({ it.first }, { it.second }))
                ?.third
                ?: return null
            return exactAniList(subject.name, setOf(subject.name.loose(), subject.nameCn.loose()))
                ?.toMalTitle(exact = false)
        }

        /** An AniList entry from the same year whose title resembles or starts with one of the names. */
        private suspend fun likelyViaAniList(): MalTitle? {
            for (candidate in candidates) {
                val name = candidate.name.loose()
                val media = aniList(candidate.name).take(LIKELY_ANILIST_RESULTS).firstOrNull { media ->
                    media.idMal != null &&
                        media.startDate.year.isNear(info.year, 1) &&
                        media.allTitles().any { title ->
                            val other = title.loose()
                            similarity(name, other) >= LIKELY_ANILIST_SIMILARITY ||
                                (name.length >= MIN_PREFIX_LENGTH && other.startsWith(name))
                        }
                } ?: continue
                return media.toMalTitle(exact = false)
            }
            return null
        }

        private suspend fun exactAniList(name: String, names: Set<String>): Media? = aniList(name)
            .withIndex()
            .filter { (_, media) -> media.idMal != null }
            .mapNotNull { (index, media) -> media.matchRank(names - "")?.let { rank -> Triple(rank, index, media) } }
            .sortedWith(
                compareBy<Triple<Int, Int, Media>> { it.first }
                    .thenBy { it.third.format != "MANGA" }
                    .thenBy { it.second },
            )
            .firstOrNull()
            ?.third

        private suspend fun bangumi(name: String): List<Subject> = bangumiResults.getOrPut(name) {
            val body = buildJsonObject {
                put("keyword", name)
                putJsonObject("filter") { putJsonArray("type") { add(BANGUMI_TYPE_BOOK) } }
            }
            client.post("$BANGUMI_SEARCH_URL?limit=$BANGUMI_LIMIT", headers, body.toJsonRequestBody())
                .parseAs<BangumiResponse>()
                .data
                .filter { it.platform == BANGUMI_PLATFORM_MANGA }
        }

        private suspend fun aniList(name: String): List<Media> = aniListResults.getOrPut(name) {
            val body = buildJsonObject {
                put("query", ANILIST_QUERY)
                putJsonObject("variables") { put("search", name) }
            }
            client.post(ANILIST_URL, headers, body.toJsonRequestBody())
                .parseAs<AniListResponse>()
                .data?.page?.media.orEmpty()
                .filter { it.format != "NOVEL" }
        }
    }

    /** A name to search for. [shortened] names were cut from a longer title. */
    private class Candidate(val name: String, val shortened: Boolean)

    /**
     * The title, subtitle and aliases as they are, then the title and subtitle without what follows
     * a separator, and the Chinese part of mixed titles ("ONE PIECE航海王" is 航海王 on Bangumi).
     */
    private fun MangaInfo.candidates(): List<Candidate> {
        val whole = (listOf(title) + otherNames.take(MAX_OTHER_NAMES)).map { it.trim() }
        val shortened = whole.take(2).flatMap { name ->
            listOf(name.split(SUBTITLE_SEPARATOR).first().trim(), name.replace(LEADING_ASCII, "").trim())
        }
        val seen = HashSet<String>()
        return (whole.map { Candidate(it, shortened = false) } + shortened.map { Candidate(it, shortened = true) })
            .filter { it.name.loose().length >= 2 && seen.add(it.name.loose()) }
    }

    private fun List<Subject>.firstExactMatch(name: String): Subject? {
        // Strict first: "咒术回战" must pick 呪術廻戦, not the sequel 呪術廻戦≡.
        for (normalize in listOf<(String) -> String>({ it.strict() }, { it.loose() })) {
            val key = normalize(name)
            firstOrNull { key == normalize(it.nameCn) || key == normalize(it.name) }?.let { return it }
        }
        return null
    }

    /** 0 when the native title matches, 1 for the romaji or English title, 2 for a synonym. */
    private fun Media.matchRank(names: Set<String>): Int? = when {
        title.native.loose() in names -> 0
        title.romaji.loose() in names || title.english.loose() in names -> 1
        synonyms.any { it.loose() in names } -> 2
        else -> null
    }

    private fun Media.allTitles(): List<String> = listOfNotNull(title.native, title.romaji, title.english) + synonyms

    private fun Media.toMalTitle(exact: Boolean) = MalTitle(idMal!!, title.romaji, title.english, exact)

    private fun Subject.similarityTo(name: String): Double {
        val key = name.loose()
        return maxOf(similarity(key, nameCn.loose()), similarity(key, this.name.loose()))
    }

    /**
     * Whether one of [authors] (as 漫画柜 writes them, e.g. "川上泰树" or "赤坂明(赤坂アカ)") shares at least
     * half its characters with a Bangumi author: Chinese and Japanese spellings differ (伏濑/伏瀬).
     */
    private fun Subject.sharesAuthorWith(authors: List<String>): Boolean {
        val theirs = infobox.filter { it.key in BANGUMI_AUTHOR_KEYS }
            .flatMap { it.values() }
            .map { it.loose().toSet() }
            .filter { it.size >= 2 }
        return authors.asSequence()
            .map { it.replace(PARENTHESES, "").loose().toSet() }
            .filter { it.size >= 2 }
            .any { ours -> theirs.any { (ours intersect it).size * 2 >= maxOf(ours.size, it.size) } }
    }

    private fun InfoboxItem.values(): List<String> = when (val v = value) {
        is JsonPrimitive -> v.contentOrNull.orEmpty().split(AUTHOR_SEPARATOR)
        is JsonArray -> v.mapNotNull { (it as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull }
        else -> emptyList()
    }

    private fun Int?.isNear(year: Int?, tolerance: Int): Boolean = year == null || (this != null && abs(this - year) <= tolerance)

    // Han characters without kana: searched on Bangumi, whose titles are Chinese or Japanese.
    private fun String.isChinese(): Boolean = any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } && none { Character.UnicodeBlock.of(it) in KANA_BLOCKS }

    /** Dice coefficient over character pairs, 0..1. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val pairsA = a.pairs()
        val pairsB = b.pairs().toMutableList()
        val shared = pairsA.count { pairsB.remove(it) }
        return 2.0 * shared / (pairsA.size + b.pairs().size)
    }

    private fun String.pairs(): List<String> = if (length < 2) listOf(this) else windowed(2)

    // Case-, width- and whitespace-insensitive.
    private fun String?.strict(): String = Normalizer.normalize(this.orEmpty(), Normalizer.Form.NFKC)
        .lowercase()
        .replace(WHITESPACE, "")

    // Also ignores punctuation and symbols.
    private fun String?.loose(): String = strict().filter { it.isLetterOrDigit() }

    @Serializable
    private class CacheEntry(val title: MalTitle?, val checkedAt: Long)

    @Serializable
    private class BangumiResponse(val data: List<Subject> = emptyList())

    @Serializable
    private class Subject(
        val name: String = "",
        @SerialName("name_cn") val nameCn: String = "",
        val platform: String = "",
        /** "yyyy-MM-dd". */
        val date: String? = null,
        val infobox: List<InfoboxItem> = emptyList(),
    ) {
        val year: Int? get() = date?.take(4)?.toIntOrNull()
    }

    /** Values are either a string or a list of `{"v": "..."}` objects. */
    @Serializable
    private class InfoboxItem(val key: String, val value: JsonElement)

    @Serializable
    private class AniListResponse(val data: AniListData? = null)

    @Serializable
    private class AniListData(@SerialName("Page") val page: AniListPage? = null)

    @Serializable
    private class AniListPage(val media: List<Media> = emptyList())

    @Serializable
    private class Media(
        val idMal: Int? = null,
        val format: String? = null,
        val startDate: FuzzyDate = FuzzyDate(),
        val title: MediaTitle = MediaTitle(),
        val synonyms: List<String> = emptyList(),
    )

    @Serializable
    private class FuzzyDate(val year: Int? = null)

    @Serializable
    private class MediaTitle(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
    )

    companion object {
        const val PREF_KEY_TITLE_LANGUAGE = "titleLanguage"

        private const val LIKELY_NOTE = "⚠ 非精确匹配，可能不准确"
        private const val CACHE_PREFIX = "malTitle:"
        private const val RECHECK_AFTER_MS = 7 * 24 * 60 * 60 * 1000L
        private const val USER_AGENT = "Thsss3341/ths-manhua (https://github.com/Thsss3341/ths-manhua)"

        private const val MAX_OTHER_NAMES = 3
        private const val SHORTENED_YEAR_TOLERANCE = 2
        private const val LIKELY_SIMILARITY = 0.4
        private const val LIKELY_ANILIST_SIMILARITY = 0.5
        private const val LIKELY_ANILIST_RESULTS = 5
        private const val MIN_PREFIX_LENGTH = 4

        private const val BANGUMI_HOST = "api.bgm.tv"
        private const val BANGUMI_SEARCH_URL = "https://$BANGUMI_HOST/v0/search/subjects"
        private const val BANGUMI_TYPE_BOOK = 1
        private const val BANGUMI_PLATFORM_MANGA = "漫画"
        private const val BANGUMI_LIMIT = 10
        private val BANGUMI_AUTHOR_KEYS = setOf("作者", "作画", "原作", "脚本")

        private const val ANILIST_HOST = "graphql.anilist.co"
        private const val ANILIST_URL = "https://$ANILIST_HOST"
        private const val ANILIST_QUERY = """
            query (${'$'}search: String) {
              Page(perPage: 8) {
                media(search: ${'$'}search, type: MANGA) {
                  idMal
                  format
                  startDate { year }
                  title { romaji english native }
                  synonyms
                }
              }
            }
        """

        private val KANA_BLOCKS = setOf(Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA)
        private val SUBTITLE_SEPARATOR = Regex("""[~～：:（(【\[—]""")
        private val LEADING_ASCII = Regex("""^[\x00-\x7F]+""")
        private val PARENTHESES = Regex("""[（(].*?[)）]""")
        private val AUTHOR_SEPARATOR = Regex("""[、,，/]""")
        private val WHITESPACE = Regex("""\s+""")
    }
}
