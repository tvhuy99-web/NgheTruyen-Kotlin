package vn.nghetruyen.app.freesound

import kotlin.math.max
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

/**
 * Finds an already-downloaded/library asset that can satisfy a Mode-3 requirement before any
 * Freesound request is made.
 *
 * The Freesound query remains the same short English query produced by the existing AI/network
 * contract. Local matching is richer: it may additionally use story text from the exact timeline
 * usage already chosen by AI. That context is selection evidence only; this matcher never changes
 * start/end ids, repeat count, cadence, looping, layer count or any other playback decision.
 */
internal object Mode3LibraryAssetMatcher {
    data class Match(
        val track: SceneMusicTrackEntity,
        val score: Double,
        /** Coverage of the original short Freesound query. */
        val coverage: Double,
        /** Strongest semantic match between story context and local metadata. */
        val contextScore: Double,
        /** Coverage of acoustic/object anchors that should not be lost during contextual matching. */
        val anchorCoverage: Double,
        val avoidCoverage: Double,
        val contextAware: Boolean,
        val anchorRequired: Boolean,
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
        .maxWithOrNull(
            compareBy<Match> { it.score }
                .thenBy { it.contextScore }
                .thenBy { it.coverage },
        )
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
        // Neutral network anchors are useful on Freesound but should not become mandatory semantic
        // concepts for a hand-written local description.
        val meaningfulQueryTokens = rawQueryTokens.filterNot(LOCAL_QUERY_ANCHORS::contains).toSet()
        val queryTokens = meaningfulQueryTokens.ifEmpty { rawQueryTokens }
        val hardAnchorTokens = queryTokens.filter { token -> isHardAnchor(need.kind, token) }.toSet()

        val title = normalized(track.title)
        val sections = sections(track.tagsCsv)
        val titleCoverage = conceptCoverage(queryTokens, title)
        val allCoverage = conceptCoverage(queryTokens, sections.all)
        val shadeCoverage = conceptCoverage(queryTokens, sections.shade)
        val useCoverage = conceptCoverage(queryTokens, sections.use)
        val queryAvoidCoverage = conceptCoverage(queryTokens, sections.avoid)
        val queryCoverage = max(titleCoverage, max(allCoverage * 0.88, max(shadeCoverage * 0.96, useCoverage)))

        val anchorCoverage = if (hardAnchorTokens.isEmpty()) 1.0 else max(
            conceptCoverage(hardAnchorTokens, title),
            max(
                conceptCoverage(hardAnchorTokens, sections.all),
                max(
                    conceptCoverage(hardAnchorTokens, sections.shade),
                    conceptCoverage(hardAnchorTokens, sections.use),
                ),
            ),
        )

        val localContext = normalized(
            need.usages.asSequence()
                .map(FreesoundAutoRequirement::localContext)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" "),
        )
        val contextAware = localContext.isNotBlank()
        val contextUse = if (contextAware) contextMatchScore(localContext, sections.use) else 0.0
        val contextShade = if (contextAware) contextMatchScore(localContext, sections.shade) else 0.0
        val contextAll = if (contextAware) contextMatchScore(localContext, sections.all) else 0.0
        val contextAvoid = if (contextAware) contextMatchScore(localContext, sections.avoid) else 0.0
        val contextScore = max(contextUse, max(contextShade * 0.84, contextAll * 0.66))
        val avoidCoverage = max(queryAvoidCoverage, contextAvoid)

        if (queryCoverage <= 0.0 && contextScore <= 0.0) return null

        val phraseBonus = when {
            containsPhrase(title, query) -> 0.18
            containsPhrase(sections.use, query) -> 0.15
            containsPhrase(sections.shade, query) -> 0.12
            containsPhrase(sections.all, query) -> 0.08
            else -> 0.0
        }
        val structuredBonus = if (sections.structured) 0.07 else if (sections.all.length >= 24) 0.03 else 0.0
        val titleBonus = titleCoverage * 0.10
        val repetitionPenalty = repetitionPenalty(track, nowMillis)
        val finalScore = if (!contextAware) {
            // Preserve the old scoring path for legacy/tests/callers that do not provide scene text.
            queryCoverage * 0.78 + phraseBonus + structuredBonus + titleBonus -
                queryAvoidCoverage * 0.58 - repetitionPenalty
        } else {
            // Query keeps acoustic intent; AI-selected scene text disambiguates semantic usage.
            queryCoverage * 0.37 +
                contextUse * 0.30 +
                contextShade * 0.14 +
                contextAll * 0.07 +
                anchorCoverage * 0.08 +
                phraseBonus * 0.45 +
                structuredBonus +
                titleBonus * 0.50 -
                avoidCoverage * 0.72 -
                repetitionPenalty
        }.coerceIn(0.0, 1.0)

        return Match(
            track = track,
            score = finalScore,
            coverage = queryCoverage,
            contextScore = contextScore,
            anchorCoverage = anchorCoverage,
            avoidCoverage = avoidCoverage,
            contextAware = contextAware,
            anchorRequired = hardAnchorTokens.isNotEmpty(),
        )
    }

    private fun isStrongEnough(match: Match): Boolean {
        if (!match.contextAware) {
            return match.score >= LEGACY_MIN_SCORE &&
                match.coverage >= LEGACY_MIN_COVERAGE &&
                match.avoidCoverage < MAX_AVOID_COVERAGE
        }
        if (match.anchorRequired && match.anchorCoverage < MIN_ANCHOR_COVERAGE) return false
        return match.score >= CONTEXT_MIN_SCORE &&
            (match.coverage >= CONTEXT_MIN_QUERY_COVERAGE || match.contextScore >= MIN_CONTEXT_SCORE) &&
            match.avoidCoverage < CONTEXT_MAX_AVOID_COVERAGE
    }

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

    /**
     * Context is intentionally scored as evidence, not as another search query. A long story region
     * contains many irrelevant words, so we reward several shared semantic/lexical signals rather
     * than divide by every word in the region.
     */
    private fun contextMatchScore(context: String, metadata: String): Double {
        if (context.isBlank() || metadata.isBlank()) return 0.0
        val contextConcepts = conceptIndexes(context)
        val metadataConcepts = conceptIndexes(metadata)
        val sharedConcepts = contextConcepts.count(metadataConcepts::contains)
        val conceptScore = when (sharedConcepts) {
            0 -> 0.0
            1 -> 0.42
            2 -> 0.64
            3 -> 0.80
            4 -> 0.91
            else -> 1.0
        }

        val sceneTokens = semanticTokens(context)
        val metadataTokens = semanticTokens(metadata)
        val lexicalShared = sceneTokens.count(metadataTokens::contains)
        val lexicalScore = (lexicalShared / 5.0).coerceIn(0.0, 1.0)

        val contextBigrams = bigrams(sceneTokens)
        val metadataBigrams = bigrams(metadataTokens)
        val sharedBigrams = contextBigrams.count(metadataBigrams::contains)
        val bigramScore = when (sharedBigrams) {
            0 -> 0.0
            1 -> 0.48
            2 -> 0.72
            3 -> 0.88
            else -> 1.0
        }
        return max(conceptScore, max(lexicalScore * 0.82, bigramScore))
    }

    private fun conceptIndexes(text: String): Set<Int> = CONCEPT_GROUPS.indices
        .asSequence()
        .filter { index -> CONCEPT_GROUPS[index].any { alias -> containsPhrase(text, alias) } }
        .toSet()

    private fun semanticTokens(text: String): List<String> = normalized(text)
        .split(' ')
        .asSequence()
        .map(String::trim)
        .filter { it.length >= 3 && it !in CONTEXT_STOPWORDS }
        .take(MAX_CONTEXT_TOKENS)
        .toList()

    private fun bigrams(tokens: List<String>): Set<String> {
        if (tokens.size < 2) return emptySet()
        return (0 until tokens.lastIndex).mapTo(linkedSetOf()) { index ->
            "${tokens[index]} ${tokens[index + 1]}"
        }
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

    private fun isHardAnchor(kind: AudioAssetKind, token: String): Boolean = when (kind) {
        AudioAssetKind.MUSIC -> token in MUSIC_HARD_ANCHORS
        AudioAssetKind.AMBIENCE -> token in AMBIENCE_HARD_ANCHORS
        AudioAssetKind.SFX -> token in SFX_HARD_ANCHORS
    }

    private const val LEGACY_MIN_SCORE = 0.54
    private const val LEGACY_MIN_COVERAGE = 0.50
    private const val CONTEXT_MIN_SCORE = 0.52
    private const val CONTEXT_MIN_QUERY_COVERAGE = 0.34
    private const val MIN_CONTEXT_SCORE = 0.56
    private const val MIN_ANCHOR_COVERAGE = 0.50
    private const val MAX_AVOID_COVERAGE = 0.50
    private const val CONTEXT_MAX_AVOID_COVERAGE = 0.48
    private const val MAX_CONTEXT_TOKENS = 420

    private val LOCAL_QUERY_ANCHORS = setOf(
        "music", "cinematic", "background", "audio", "sound", "effect", "ambience", "ambient",
    )

    private val MUSIC_HARD_ANCHORS = setOf(
        "piano", "strings", "string", "violin", "cello", "flute", "dizi", "guitar", "orchestral",
        "orchestra", "drums", "drum", "percussion", "choir", "choral", "synth", "electronic",
        "guzheng", "guqin", "erhu", "koto", "shamisen", "harp", "folk", "jazz", "rock", "acoustic",
        "classical",
    )
    private val AMBIENCE_HARD_ANCHORS = setOf(
        "forest", "woods", "rain", "storm", "thunderstorm", "wind", "gust", "thunder", "water",
        "river", "stream", "ocean", "sea", "fire", "flame", "crowd", "walla", "city", "street",
        "urban", "tavern", "inn", "night",
    )
    private val SFX_HARD_ANCHORS = setOf(
        "footstep", "footsteps", "steps", "door", "sword", "blade", "hit", "strike", "impact", "crash",
        "break", "shatter", "horse", "gallop", "bell", "ring", "explosion", "boom", "blast", "gust",
        "whoosh", "slash", "thud", "clash", "slam", "burst", "pulse", "snap", "drop", "knock", "creak",
        "splash", "shout", "bang", "click", "tear", "rip", "burn",
    )

    private val CONTEXT_STOPWORDS = setOf(
        "and", "the", "that", "this", "with", "from", "into", "for", "was", "were", "are", "but", "then",
        "cua", "cho", "nhung", "cac", "trong", "khi", "sau", "truoc", "voi", "den", "nay", "dang", "duoc",
        "khong", "roi", "lai", "vao", "tren", "duoi", "giua", "nhu", "thi", "ma", "theo", "mot",
    )

    /**
     * Bilingual bridge between the short English network query, Vietnamese story text and mixed
     * Vietnamese/English library metadata. Vietnamese aliases deliberately favor multi-word phrases
     * when accent stripping would otherwise turn common words into false semantic hits (for example
     * cửa/của, tối/tôi, mưa/mua, chuông/chương or nổ/nó).
     */
    private val CONCEPT_GROUPS: List<Set<String>> = listOf(
        setOf("sad", "melancholic", "sorrow", "grief", "buon", "u sau", "dau buon", "mat mat", "chia ly", "tuyet vong"),
        setOf("emotional", "emotion", "cam xuc", "tinh cam", "xuc dong"),
        setOf("romantic", "romance", "love", "tinh yeu", "lang man", "hai nguoi", "than mat"),
        setOf("relationship", "relationships", "moi quan he", "quan he"),
        setOf("conversation", "talk", "chat", "dialogue", "tro chuyen", "doi thoai", "noi chuyen"),
        setOf("wait", "waiting", "await", "cho doi", "doi cho", "mong doi"),
        setOf("tense", "tension", "suspense", "cang thang", "suc ep", "hoi hop"),
        setOf("danger", "dangerous", "threat", "risk", "nguy hiem", "de doa", "rui ro"),
        setOf("dark", "ominous", "am u", "den toi", "toi tam"),
        setOf("mysterious", "mystery", "mystical", "bi an", "ky la", "kho hieu"),
        setOf("horror", "creepy", "eerie", "scary", "kinh di", "dang so", "ma quai", "am anh"),
        setOf("epic", "heroic", "majestic", "su thi", "hung trang", "hao hung", "hoanh trang"),
        setOf("dramatic", "drama", "kich tinh", "cao trao", "bien co"),
        setOf("uplifting", "hopeful", "inspiring", "hy vong", "nang len", "truyen cam hung", "hoi phuc"),
        setOf("happy", "joy", "cheerful", "vui", "vui ve", "hanh phuc", "an mung"),
        setOf("calm", "peaceful", "gentle", "quiet", "serene", "yen binh", "nhe nhang", "thu gian", "tinh lang"),
        setOf("nostalgic", "memory", "memories", "remember", "hoi tuong", "ky niem", "nho lai", "qua khu"),
        setOf("lonely", "solitude", "alone", "co don", "mot minh", "tach khoi"),
        setOf("farewell", "goodbye", "parting", "chia tay", "tu biet", "roi di"),
        setOf("loss", "lost", "death", "mourning", "funeral", "mat mat", "mat di", "tuong niem", "tang le"),
        setOf("sacrifice", "sacrificing", "hy sinh", "danh doi"),
        setOf("victory", "triumph", "win", "chien thang", "dai thang", "phan cong"),
        setOf("battle", "combat", "fight", "war", "chien dau", "giao chien", "dai chien", "chien tranh"),
        setOf("duel", "one on one", "single combat", "dau tay doi", "song dau"),
        setOf("chase", "pursuit", "escape", "run", "truy duoi", "duoi bat", "chay tron", "truy bat"),
        setOf("adventure", "quest", "journey", "travel", "phieu luu", "hanh trinh", "len duong"),
        setOf("exploration", "explore", "discovery", "kham pha", "phat hien", "mo ra"),
        setOf("stealth", "sneaky", "covert", "spy", "len lut", "tham nhap", "theo doi", "do xet"),
        setOf("stalk", "stalking", "followed", "tailing", "bam theo", "bi theo doi"),
        setOf("investigation", "investigate", "detective", "manh moi", "dieu tra", "suy luan", "giai ma"),
        setOf("crime", "criminal", "toi pham", "vu an", "am muu"),
        setOf("comedy", "funny", "quirky", "comic", "hai huoc", "gay cuoi", "tinh nghich", "vuong ve"),
        setOf("daily", "casual", "ordinary", "everyday", "doi thuong", "sinh hoat", "thuong ngay"),
        setOf("family", "home", "gia dinh", "ve nha", "trong nha", "doan tu", "nguoi than"),
        setOf("ritual", "ceremony", "nghi le", "te le", "tho cung", "tin nguong"),
        setOf("royal", "imperial", "palace", "kingdom", "cung dinh", "hoang gia", "trieu dai", "quyen luc"),
        setOf("ancient", "old", "co xua", "co dai", "lau doi", "lich su"),
        setOf("temple", "shrine", "ngoi den", "den tho", "ngoi chua", "tu vien", "noi tu hoc"),
        setOf("magic", "magical", "fantasy", "phep thuat", "ma thuat", "bi thuat", "tien hiep"),
        setOf("nature", "natural", "thien nhien", "canh vat"),
        setOf("forest", "woods", "khu rung", "trong rung", "rung cay"),
        setOf("rain", "troi mua", "mua roi", "tieng mua"),
        setOf("storm", "thunderstorm", "con bao", "giong bao", "bao to"),
        setOf("wind", "gust", "gio thoi", "tieng gio", "con gio"),
        setOf("thunder", "thunderclap", "tieng sam", "sam set", "tieng set"),
        setOf("water", "nuoc chay", "tieng nuoc", "mat nuoc"),
        setOf("river", "stream", "dong song", "bo song", "con song", "dong suoi"),
        setOf("ocean", "sea", "bien ca", "dai duong", "bo bien", "song bien"),
        setOf("fire", "flame", "burn", "ngon lua", "lua chay", "dam chay"),
        setOf("crowd", "walla", "dam dong", "nhieu nguoi"),
        setOf("city", "street", "urban", "thanh pho", "duong pho"),
        setOf("tavern", "inn", "bar", "quan tro", "quan an", "tuu lau"),
        setOf("night", "ban dem", "dem khuya", "trong dem"),
        setOf("piano", "duong cam", "dan piano"),
        setOf("strings", "string", "dan day", "bo day"),
        setOf("violin", "vi cam", "dan violin"),
        setOf("cello", "violoncello", "dan cello"),
        setOf("flute", "dizi", "tieng sao", "sao truc", "thoi sao"),
        setOf("guitar", "ghi ta", "dan guitar"),
        setOf("orchestral", "orchestra", "dan nhac"),
        setOf("ambient", "atmosphere", "khong gian", "am nen", "lop nen"),
        setOf("drums", "drum", "percussion", "tieng trong", "trong tran", "trong chien", "bo go"),
        setOf("choir", "choral", "hop xuong", "dong ca"),
        setOf("synth", "electronic", "dien tu"),
        setOf("footstep", "footsteps", "steps", "buoc chan", "tieng buoc chan"),
        setOf("door", "mo cua", "dong cua", "cua go", "cua kim loai", "cua kinh"),
        setOf("sword", "blade", "thanh kiem", "rut kiem", "luoi kiem", "kiem chem", "kiem va"),
        setOf("hit", "strike", "impact", "va cham", "danh trung", "cu danh"),
        setOf("crash", "break", "shatter", "do vo", "vo tung", "va vo"),
        setOf("horse", "gallop", "ngua phi", "phi ngua", "tieng vo ngua"),
        setOf("bell", "ring", "tieng chuong", "chuong reo", "chuong vang"),
        setOf("explosion", "boom", "blast", "vu no", "tieng no", "bom no"),
    ).map { group -> group.map(::normalized).filter(String::isNotBlank).toSet() }
}
