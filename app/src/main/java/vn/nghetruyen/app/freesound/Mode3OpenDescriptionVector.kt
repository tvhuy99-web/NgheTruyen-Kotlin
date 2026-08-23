package vn.nghetruyen.app.freesound

import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/**
 * Lightweight, model-free description fingerprint for Mode 3 local-library matching.
 *
 * This intentionally has no audio concept dictionary. It represents whatever words and phrases
 * are present in the description, including unseen objects/actions, and compares the complete
 * description with cosine similarity. Word n-grams preserve context while character n-grams make
 * Vietnamese spelling/diacritic variants and inflected forms less brittle.
 */
internal object Mode3OpenDescriptionVector {
    internal data class Fingerprint(
        val values: FloatArray,
        val norm: Double,
        val tokenCount: Int,
    ) {
        val hasContent: Boolean get() = tokenCount > 0 && norm > 0.0
    }

    fun build(text: String): Fingerprint {
        val normalized = normalize(text)
        if (normalized.isBlank()) return EMPTY
        val tokens = normalized.split(' ')
            .asSequence()
            .map(String::trim)
            .filter { it.length >= 2 && it !in FUNCTION_WORDS }
            .take(MAX_TOKENS)
            .toList()
        if (tokens.isEmpty()) return EMPTY

        val values = FloatArray(DIMENSIONS)
        tokens.forEach { token ->
            add(values, "w:$token", WORD_WEIGHT)
            addCharacterFeatures(values, token)
        }
        if (tokens.size >= 2) {
            for (index in 0 until tokens.lastIndex) {
                add(values, "b:${tokens[index]}_${tokens[index + 1]}", BIGRAM_WEIGHT)
            }
        }
        if (tokens.size >= 3) {
            for (index in 0 until tokens.size - 2) {
                add(values, "t:${tokens[index]}_${tokens[index + 1]}_${tokens[index + 2]}", TRIGRAM_WEIGHT)
            }
        }
        if (tokens.size >= 2) {
            tokens.windowed(size = CONTEXT_WINDOW, step = 1, partialWindows = true).forEach { window ->
                for (left in window.indices) {
                    for (right in left + 1 until window.size) {
                        val first = window[left]
                        val second = window[right]
                        val pair = if (first <= second) "$first|$second" else "$second|$first"
                        add(values, "c:$pair", CONTEXT_PAIR_WEIGHT)
                    }
                }
            }
        }

        var normSquared = 0.0
        values.forEach { value -> normSquared += value.toDouble() * value.toDouble() }
        val norm = sqrt(normSquared)
        return if (norm <= 0.0) EMPTY else Fingerprint(values, norm, tokens.size)
    }

    fun cosine(first: Fingerprint, second: Fingerprint): Double {
        if (!first.hasContent || !second.hasContent) return 0.0
        var dot = 0.0
        for (index in 0 until DIMENSIONS) {
            dot += first.values[index].toDouble() * second.values[index].toDouble()
        }
        return (dot / (first.norm * second.norm)).coerceIn(0.0, 1.0)
    }

    private fun addCharacterFeatures(values: FloatArray, token: String) {
        if (token.length < 3) return
        val padded = "^$token$"
        for (size in CHAR_GRAM_MIN..CHAR_GRAM_MAX) {
            if (padded.length < size) continue
            val weight = if (size == 3) CHAR_TRIGRAM_WEIGHT else CHAR_FOURGRAM_WEIGHT
            for (index in 0..padded.length - size) {
                add(values, "g$size:${padded.substring(index, index + size)}", weight)
            }
        }
    }

    private fun add(values: FloatArray, feature: String, weight: Float) {
        val hash = stableHash(feature)
        val index = (hash and Int.MAX_VALUE) % DIMENSIONS
        values[index] += weight
    }

    private fun stableHash(value: String): Int {
        var hash = FNV_OFFSET_BASIS
        value.forEach { char ->
            hash = hash xor char.code
            hash *= FNV_PRIME
        }
        return hash
    }

    private fun normalize(value: String): String {
        val lower = value.lowercase(Locale.ROOT).replace('đ', 'd')
        val folded = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
        return folded
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(MULTI_SPACE, " ")
    }

    private const val DIMENSIONS = 384
    private const val MAX_TOKENS = 96
    private const val CONTEXT_WINDOW = 4
    private const val CHAR_GRAM_MIN = 3
    private const val CHAR_GRAM_MAX = 4
    private const val WORD_WEIGHT = 1.00f
    private const val BIGRAM_WEIGHT = 1.35f
    private const val TRIGRAM_WEIGHT = 1.55f
    private const val CONTEXT_PAIR_WEIGHT = 0.34f
    private const val CHAR_TRIGRAM_WEIGHT = 0.24f
    private const val CHAR_FOURGRAM_WEIGHT = 0.18f
    private const val FNV_OFFSET_BASIS = -0x7ee3623b
    private const val FNV_PRIME = 0x01000193

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val MULTI_SPACE = Regex("\\s+")

    private val FUNCTION_WORDS = setOf(
        "va", "hoac", "nhung", "cua", "cho", "trong", "ngoai", "khi", "sau", "truoc", "voi", "den",
        "dang", "duoc", "khong", "mot", "nhung", "cac", "nay", "do", "thi", "ma", "theo", "rat", "hoi",
        "canh", "tieng", "am", "thanh", "hieu", "ung", "sac", "thai", "dung", "tranh", "phu", "hop",
        "tao", "co", "la", "nen", "nghe", "keo", "dai", "ngan", "lien", "tuc",
        "and", "or", "the", "with", "for", "from", "into", "this", "that", "sound", "audio",
    )

    private val EMPTY = Fingerprint(FloatArray(DIMENSIONS), 0.0, 0)
}
