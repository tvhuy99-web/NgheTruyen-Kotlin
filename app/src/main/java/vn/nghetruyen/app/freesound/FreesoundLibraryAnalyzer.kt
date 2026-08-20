package vn.nghetruyen.app.freesound

import java.text.Normalizer
import kotlin.math.max
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

data class FreesoundLibraryGap(
    val query: String,
    val coverageScore: Double,
)

data class FreesoundNearDuplicate(
    val firstTrackId: String,
    val secondTrackId: String,
    val score: Double,
    val reason: String,
)

object FreesoundLibraryAnalyzer {
    private val typeMarkerRegex = Regex(
        """(?i)(?:type\s*[:=]\s*(?:sfx[_-]?continuous|continuous|sfx|sound[_-]?effect|ambience|environment|music)|\[(?:continuous|sfx[_-]?continuous|sfx|ambience|environment|music)])""",
    )
    private val nonWordRegex = Regex("[^a-z0-9]+")
    private val stopWords = setOf(
        "a", "an", "and", "audio", "background", "effect", "effects", "for", "in", "of", "sound", "sounds", "the", "with",
        "am", "anh", "cai", "cac", "cho", "cua", "la", "mot", "nhung", "tieng", "trong", "va", "voi",
    )

    fun coverageTopics(kind: AudioAssetKind): List<String> = when (kind) {
        AudioAssetKind.MUSIC -> listOf(
            "fantasy music", "epic orchestral", "dark ambient", "battle music", "sad piano", "mystical music",
            "tension music", "peaceful background", "romantic music", "victory music", "mysterious music", "horror music",
            "ancient music", "meditation music", "adventure music", "emotional music", "tragic music", "royal music",
            "martial arts music", "ethereal music", "suspense music", "ceremonial music", "hopeful music", "melancholic music",
        )
        AudioAssetKind.AMBIENCE -> listOf(
            "rain ambience", "forest ambience", "thunderstorm", "tavern ambience", "cave ambience", "night ambience",
            "strong wind", "river ambience", "waterfall ambience", "ocean waves", "mountain wind", "battlefield ambience",
            "crowd ambience", "village ambience", "city ambience", "temple ambience", "palace ambience", "fireplace ambience",
            "snowstorm ambience", "swamp ambience", "desert wind", "underground ambience", "market ambience", "haunted ambience",
        )
        AudioAssetKind.SFX -> listOf(
            "sword clash", "magic spell", "footsteps", "thunder", "explosion", "fire", "door", "horse",
            "sword draw", "arrow shot", "bow draw", "punch impact", "body fall", "glass break", "wood break", "metal impact",
            "whoosh", "energy blast", "shield impact", "bell", "heartbeat", "breathing", "cloth movement", "water splash",
            "rock crumble", "chain", "monster roar", "dragon roar", "teleport", "magic shield",
        )
    }

    fun findMissingTopics(
        kind: AudioAssetKind,
        tracks: List<SceneMusicTrackEntity>,
        maxResults: Int = 30,
    ): List<FreesoundLibraryGap> {
        val relevant = tracks.filter { track ->
            runCatching { vn.nghetruyen.app.audio.AudioAssetClassifier.classify(track) == kind }.getOrDefault(false)
        }
        return coverageTopics(kind)
            .map { query -> FreesoundLibraryGap(query, coverageScore(query, relevant)) }
            .filter { it.coverageScore < COVERED_THRESHOLD }
            .sortedWith(compareBy<FreesoundLibraryGap> { it.coverageScore }.thenBy { it.query })
            .take(maxResults.coerceIn(1, 100))
    }

    fun coverageScore(query: String, tracks: List<SceneMusicTrackEntity>): Double {
        if (tracks.isEmpty()) return 0.0
        val queryNorm = normalize(query)
        val queryTokens = tokens(query)
        if (queryNorm.isBlank() || queryTokens.isEmpty()) return 0.0
        return tracks.maxOfOrNull { track ->
            val title = normalize(track.title)
            val description = normalize(description(track.tagsCsv))
            val combined = "$title $description".trim()
            if (combined.contains(queryNorm) || queryNorm.contains(title).takeIf { title.length >= 5 } == true) {
                1.0
            } else {
                val titleScore = tokenCoverage(queryTokens, tokens(track.title))
                val descriptionScore = tokenCoverage(queryTokens, tokens(description(track.tagsCsv)))
                max(titleScore, descriptionScore * 0.9)
            }
        } ?: 0.0
    }

    fun findNearDuplicates(
        tracks: List<SceneMusicTrackEntity>,
        maxResults: Int = 40,
    ): List<FreesoundNearDuplicate> {
        if (tracks.size < 2) return emptyList()
        val pairs = mutableListOf<FreesoundNearDuplicate>()
        for (firstIndex in 0 until tracks.lastIndex) {
            val first = tracks[firstIndex]
            for (secondIndex in firstIndex + 1 until tracks.size) {
                val second = tracks[secondIndex]
                val firstRemoteId = FreesoundImporter.soundIdFromManagedUri(first.uri)
                val secondRemoteId = FreesoundImporter.soundIdFromManagedUri(second.uri)
                if (firstRemoteId != null && firstRemoteId == secondRemoteId) {
                    pairs += FreesoundNearDuplicate(first.id, second.id, 1.0, "Cùng Freesound ID #$firstRemoteId")
                    continue
                }

                val firstTitle = normalize(first.title)
                val secondTitle = normalize(second.title)
                val exactTitle = firstTitle.isNotBlank() && firstTitle == secondTitle
                val titleScore = jaccard(tokens(first.title), tokens(second.title))
                val descriptionScore = jaccard(
                    tokens(description(first.tagsCsv)),
                    tokens(description(second.tagsCsv)),
                )
                val containsScore = if (
                    firstTitle.length >= 6 && secondTitle.length >= 6 &&
                    (firstTitle.contains(secondTitle) || secondTitle.contains(firstTitle))
                ) 0.9 else 0.0
                val score = if (exactTitle) 1.0 else max(containsScore, titleScore * 0.78 + descriptionScore * 0.22)
                if (score >= NEAR_DUPLICATE_THRESHOLD) {
                    val reason = when {
                        exactTitle -> "Tên giống nhau"
                        containsScore > 0.0 -> "Tên gần như bao hàm nhau"
                        descriptionScore >= 0.75 -> "Tên và mô tả rất gần nhau"
                        else -> "Tên có mức tương đồng cao"
                    }
                    pairs += FreesoundNearDuplicate(first.id, second.id, score.coerceIn(0.0, 1.0), reason)
                }
            }
        }
        return pairs
            .sortedWith(compareByDescending<FreesoundNearDuplicate> { it.score }.thenBy { it.firstTrackId }.thenBy { it.secondTrackId })
            .take(maxResults.coerceIn(1, 100))
    }

    fun description(tagsCsv: String): String = typeMarkerRegex.replace(tagsCsv, "")
        .trim()
        .trim(',', ';')
        .trim()

    internal fun normalize(value: String): String {
        val withoutMarks = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
        return withoutMarks.replace(nonWordRegex, " ").trim().replace(Regex("\\s+"), " ")
    }

    internal fun tokens(value: String): Set<String> = normalize(value)
        .split(' ')
        .asSequence()
        .map(String::trim)
        .filter { it.length >= 2 && it !in stopWords }
        .toSet()

    internal fun jaccard(first: Set<String>, second: Set<String>): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val intersection = first.intersect(second).size.toDouble()
        val union = first.union(second).size.toDouble()
        return if (union <= 0.0) 0.0 else intersection / union
    }

    private fun tokenCoverage(query: Set<String>, candidate: Set<String>): Double {
        if (query.isEmpty() || candidate.isEmpty()) return 0.0
        return query.intersect(candidate).size.toDouble() / query.size.toDouble()
    }

    private const val COVERED_THRESHOLD = 0.56
    private const val NEAR_DUPLICATE_THRESHOLD = 0.72
}
