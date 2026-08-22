package vn.nghetruyen.app.freesound

import kotlin.math.max
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

/**
 * Finds an already-downloaded/library asset that can satisfy a Mode-3 semantic requirement before
 * any Freesound request is made. The matcher deliberately works on the same SceneMusicTrackEntity
 * rows used by Mode 2, so MUSIC, AMBIENCE and SFX share one library and one set of descriptions.
 *
 * Structured Vietnamese descriptions (Sắc thái / Dùng / Tránh) receive first-class treatment while
 * ordinary English Freesound descriptions still work through lexical matching. The threshold is
 * intentionally conservative: a weak local match must fall through to Freesound instead of forcing
 * an inappropriate asset into the scene.
 */
internal object Mode3LibraryAssetMatcher {
    data class Match(
        val track: SceneMusicTrackEntity,
        val score: Double,
        val coverage: Double,
        val avoidCoverage: Double,
    )

    private data class Sections(
        val all: String,
        val shade: String,
        val use: String,
        val avoid: String,
        val structured: Boolean,
    )

    fun bestMatch(
        need: FreesoundAutoSearchNeed,
        tracks: List<SceneMusicTrackEntity>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Match? = tracks.asSequence()
        .filter { it.enabled && AudioAssetClassifier.classify(it) == need.kind }
        .mapNotNull { score(need, it, nowMillis) }
        .maxWithOrNull(compareBy<Match> { it.score }.thenBy { it.coverage })
        ?.takeIf(::isStrongEnough)

    fun strongMatch(
        need: FreesoundAutoSearchNeed,
        track: SceneMusicTrackEntity,
        nowMillis: Long = System.currentTimeMillis(),
    ): Match? = score(need, track, nowMillis)?.takeIf(::isStrongEnough)

    private fun score(
        need: FreesoundAutoSearchNeed,
        track: SceneMusicTrackEntity,
        nowMillis: Long,
    ): Match? {
        if (!track.enabled || AudioAssetClassifier.classify(track) != need.kind) return null
        val query = FreesoundAutoRequirementAggregator.normalizeQuery(need.query)
        val rawQueryTokens = FreesoundAutoRequirementAggregator.queryTokens(query)
        if (rawQueryTokens.isEmpty()) return null
        // The AI/network contract may add neutral search anchors such as "cinematic" or "music".
        // Those words help Freesound search but must not make a semantically correct local asset
        // fail merely because a hand-written description does not repeat the technical anchor.
        val meaningfulQueryTokens = rawQueryTokens.filterNot(LOCAL_QUERY_ANCHORS::contains).toSet()
        val queryTokens = meaningfulQueryTokens.ifEmpty { rawQueryTokens }

        val title = normalized(track.title)
        val sections = sections(track.tagsCsv)
        val titleCoverage = conceptCoverage(queryTokens, title)
        val allCoverage = conceptCoverage(queryTokens, sections.all)
        val shadeCoverage = conceptCoverage(queryTokens, sections.shade)
        val useCoverage = conceptCoverage(queryTokens, sections.use)
        val avoidCoverage = conceptCoverage(queryTokens, sections.avoid)
        val coverage = max(titleCoverage, max(allCoverage * 0.88, max(shadeCoverage * 0.96, useCoverage)))
        if (coverage <= 0.0) return null

        val phraseBonus = when {
            containsPhrase(title, query) -> 0.18
            containsPhrase(sections.use, query) -> 0.15
            containsPhrase(sections.shade, query) -> 0.12
            containsPhrase(sections.all, query) -> 0.08
            else -> 0.0
        }
        val structuredBonus = if (sections.structured) 0.07 else if (sections.all.length >= 24) 0.03 else 0.0
        val titleBonus = titleCoverage * 0.10
        val avoidPenalty = avoidCoverage * 0.58
        val repetitionPenalty = repetitionPenalty(track, nowMillis)
        val finalScore = (
            coverage * 0.78 + phraseBonus + structuredBonus + titleBonus - avoidPenalty - repetitionPenalty
            ).coerceIn(0.0, 1.0)

        return Match(track, finalScore, coverage, avoidCoverage)
    }

    private fun isStrongEnough(match: Match): Boolean =
        match.score >= MIN_SCORE &&
            match.coverage >= MIN_COVERAGE &&
            match.avoidCoverage < MAX_AVOID_COVERAGE

    private fun sections(raw: String): Sections {
        val value = raw.trim()
        val lower = value.lowercase()
        val shadeAt = lower.indexOf("sắc thái:")
        val useAt = lower.indexOf("dùng:")
        val avoidAt = lower.indexOf("tránh:")
        val structured = shadeAt >= 0 || useAt >= 0 || avoidAt >= 0
        if (!structured) {
            val normalized = normalized(value)
            return Sections(normalized, "", "", "", false)
        }

        fun slice(start: Int, markerLength: Int, endCandidates: List<Int>): String {
            if (start < 0) return ""
            val contentStart = start + markerLength
            val end = endCandidates.filter { it > contentStart }.minOrNull() ?: value.length
            return normalized(value.substring(contentStart, end))
        }

        val shade = slice(shadeAt, "sắc thái:".length, listOf(useAt, avoidAt))
        val use = slice(useAt, "dùng:".length, listOf(avoidAt))
        val avoid = slice(avoidAt, "tránh:".length, emptyList())
        return Sections(normalized(value), shade, use, avoid, true)
    }

    private fun conceptCoverage(queryTokens: Set<String>, text: String): Double {
        if (queryTokens.isEmpty() || text.isBlank()) return 0.0
        val matched = queryTokens.count { token -> aliases(token).any { alias -> containsPhrase(text, alias) } }
        return matched.toDouble() / queryTokens.size.toDouble()
    }

    private fun aliases(token: String): Set<String> {
        val normalizedToken = normalized(token)
        val group = CONCEPT_GROUPS.firstOrNull { normalizedToken in it }
        return if (group == null) setOf(normalizedToken) else group
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val cleanText = " ${normalized(text)} "
        val cleanPhrase = normalized(phrase)
        if (cleanPhrase.isBlank()) return false
        return " $cleanPhrase " in cleanText
    }

    private fun normalized(value: String): String = FreesoundAutoRequirementAggregator.normalizeQuery(value)

    private fun repetitionPenalty(track: SceneMusicTrackEntity, nowMillis: Long): Double {
        val playPenalty = track.playCount.coerceIn(0, 20) * 0.003
        val age = (nowMillis - track.lastPlayedAt).coerceAtLeast(0L)
        val recencyPenalty = when {
            track.lastPlayedAt <= 0L -> 0.0
            age < 30L * 60L * 1_000L -> 0.06
            age < 6L * 60L * 60L * 1_000L -> 0.03
            else -> 0.0
        }
        return (playPenalty + recencyPenalty).coerceAtMost(0.10)
    }

    private const val MIN_SCORE = 0.54
    private const val MIN_COVERAGE = 0.50
    private const val MAX_AVOID_COVERAGE = 0.50

    private val LOCAL_QUERY_ANCHORS = setOf(
        "music", "cinematic", "background", "audio", "sound", "effect", "ambience", "ambient",
    )

    /**
     * Small bilingual semantic bridge for the English Mode-3 search contract and Vietnamese
     * descriptions. It is intentionally made of broad acoustic/story concepts rather than titles
     * of individual tracks, so periodically re-standardizing descriptions keeps improving selection
     * without a schema migration.
     */
    private val CONCEPT_GROUPS: List<Set<String>> = listOf(
        setOf("sad", "melancholic", "sorrow", "grief", "buon", "u sau", "dau buon", "mat mat", "chia ly", "tuyet vong"),
        setOf("emotional", "emotion", "cam xuc", "tinh cam", "xuc dong"),
        setOf("romantic", "romance", "love", "tinh yeu", "lang man", "hai nguoi", "than mat"),
        setOf("tense", "tension", "suspense", "cang thang", "suc ep", "hoi hop"),
        setOf("dark", "ominous", "am u", "toi", "den toi", "nguy hiem"),
        setOf("mysterious", "mystery", "mystical", "bi an", "ky la", "kho hieu"),
        setOf("horror", "creepy", "eerie", "scary", "kinh di", "dang so", "ma quai", "am anh"),
        setOf("epic", "heroic", "majestic", "su thi", "hung trang", "hao hung", "hoanh trang"),
        setOf("dramatic", "drama", "kich tinh", "cao trao", "bien co"),
        setOf("uplifting", "hopeful", "inspiring", "hy vong", "nang len", "truyen cam hung", "hoi phuc"),
        setOf("happy", "joy", "cheerful", "vui", "vui ve", "hanh phuc", "an mung"),
        setOf("calm", "peaceful", "gentle", "quiet", "serene", "yen", "yen binh", "nhe", "thu gian"),
        setOf("nostalgic", "memory", "memories", "remember", "hoi tuong", "ky niem", "nho lai", "qua khu"),
        setOf("lonely", "solitude", "alone", "co don", "mot minh", "tach khoi"),
        setOf("farewell", "goodbye", "parting", "chia tay", "tu biet", "roi di"),
        setOf("loss", "lost", "death", "mat mat", "mat di", "tuong niem"),
        setOf("victory", "triumph", "win", "chien thang", "dai thang", "phan cong"),
        setOf("battle", "combat", "fight", "war", "chien dau", "giao chien", "dai chien", "chien tranh"),
        setOf("chase", "pursuit", "escape", "run", "truy duoi", "duoi bat", "chay tron", "truy bat"),
        setOf("adventure", "quest", "journey", "travel", "phieu luu", "hanh trinh", "len duong"),
        setOf("exploration", "explore", "discovery", "kham pha", "phat hien", "mo ra"),
        setOf("stealth", "sneaky", "covert", "spy", "len lut", "tham nhap", "theo doi", "do xet"),
        setOf("investigation", "investigate", "detective", "manh moi", "dieu tra", "suy luan", "giai ma"),
        setOf("crime", "criminal", "toi pham", "an", "am muu"),
        setOf("comedy", "funny", "quirky", "comic", "hai", "gay cuoi", "tinh nghich", "vuong ve"),
        setOf("daily", "casual", "ordinary", "everyday", "doi thuong", "sinh hoat", "thuong ngay"),
        setOf("family", "home", "gia dinh", "nha", "doan tu", "nguoi than"),
        setOf("ritual", "ceremony", "nghi le", "te le", "tho cung", "tin nguong"),
        setOf("royal", "imperial", "palace", "kingdom", "cung dinh", "hoang gia", "trieu dai", "quyen luc"),
        setOf("ancient", "old", "co xua", "co dai", "lau doi", "lich su"),
        setOf("temple", "shrine", "den", "chua", "tu vien", "noi tu hoc"),
        setOf("magic", "magical", "fantasy", "phep thuat", "ma thuat", "bi thuat", "tien hiep"),
        setOf("nature", "natural", "thien nhien", "canh vat"),
        setOf("forest", "woods", "rung", "khu rung"),
        setOf("rain", "mua", "mua roi"),
        setOf("storm", "thunderstorm", "bao", "giong bao"),
        setOf("wind", "gust", "gio", "gio thoi"),
        setOf("thunder", "thunderclap", "sam", "set"),
        setOf("water", "nuoc"),
        setOf("river", "stream", "song", "suoi"),
        setOf("ocean", "sea", "bien", "dai duong"),
        setOf("fire", "flame", "burn", "lua", "chay"),
        setOf("crowd", "walla", "dam dong", "nhieu nguoi"),
        setOf("city", "street", "urban", "thanh pho", "duong pho"),
        setOf("tavern", "inn", "bar", "quan", "quan tro", "quan an"),
        setOf("night", "dem", "ban dem"),
        setOf("piano", "duong cam"),
        setOf("strings", "string", "day", "dan day"),
        setOf("violin", "vi cam"),
        setOf("cello", "violoncello"),
        setOf("flute", "dizi", "sao"),
        setOf("guitar", "ghi ta"),
        setOf("orchestral", "orchestra", "dan nhac"),
        setOf("ambient", "atmosphere", "khong gian", "nen", "am nen"),
        setOf("drums", "drum", "percussion", "trong", "bo go"),
        setOf("choir", "choral", "hop xuong", "dong ca"),
        setOf("synth", "electronic", "dien tu"),
        setOf("footstep", "footsteps", "steps", "buoc chan"),
        setOf("door", "cua", "cua mo", "cua dong"),
        setOf("sword", "blade", "kiem", "dao"),
        setOf("hit", "strike", "impact", "va cham", "danh", "danh trung"),
        setOf("crash", "break", "shatter", "vo", "do vo", "va vo"),
        setOf("horse", "gallop", "ngua", "phi ngua"),
        setOf("bell", "ring", "chuong", "tieng chuong"),
        setOf("explosion", "boom", "blast", "no", "vu no"),
    ).map { group -> group.map(::normalized).filter(String::isNotBlank).toSet() }
}