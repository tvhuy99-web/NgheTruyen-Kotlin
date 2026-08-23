from pathlib import Path

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    return text.replace(old, new, 1)


# 1) Use matched DJL Java/native versions on Android.
path = 'app/build.gradle.kts'
text = read(path)
text = replace_once(
    text,
    '    implementation("ai.djl.huggingface:tokenizers:0.36.0")\n    runtimeOnly("ai.djl.android:tokenizer-native:0.33.0")',
    '    implementation("ai.djl.huggingface:tokenizers:0.33.0")\n    runtimeOnly("ai.djl.android:tokenizer-native:0.33.0")',
    'aligned DJL tokenizer runtime',
)
write(path, text)

# 2) Replace JavaCPP SentencePiece with Hugging Face tokenizer.json.
path = 'app/src/main/java/vn/nghetruyen/app/freesound/Mode3E5SemanticEngine.kt'
text = read(path)
text = replace_once(
    text,
    'import ai.onnxruntime.OnnxTensor\nimport ai.onnxruntime.OrtEnvironment\nimport ai.onnxruntime.OrtSession\n',
    'import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer\nimport ai.onnxruntime.OnnxTensor\nimport ai.onnxruntime.OrtEnvironment\nimport ai.onnxruntime.OrtSession\n',
    'E5 tokenizer import',
)
text = replace_once(
    text,
    'import org.bytedeco.sentencepiece.IntVector\nimport org.bytedeco.sentencepiece.SentencePieceProcessor\n',
    '',
    'remove JavaCPP imports',
)
text = replace_once(
    text,
    '        val tokenizer: SentencePieceProcessor,',
    '        val tokenizer: HuggingFaceTokenizer,',
    'runtime tokenizer type',
)
text = replace_once(
    text,
    '        else -> "DESCRIPTION_VECTOR_OPEN_VOCABULARY_FALLBACK"',
    '        else -> "DISABLED_NO_E5_MODEL"',
    'no lightweight backend',
)
old_runtime = '''            runCatching {\n                val tokenizer = SentencePieceProcessor()\n                val tokenizerStatus = tokenizer.Load(File(modelDirectory(context), TOKENIZER_FILE).absolutePath)\n                check(tokenizerStatus.ok()) { "Không mở được tokenizer E5: ${tokenizerStatus.ToString()}" }\n                val environment = OrtEnvironment.getEnvironment()\n                val options = OrtSession.SessionOptions()\n                val session = environment.createSession(File(modelDirectory(context), MODEL_FILE).absolutePath, options)\n                Runtime(environment, session, tokenizer).also {\n                    runtime = it\n                    lastError = null\n                }\n            }.getOrElse { error ->'''
new_runtime = '''            runCatching {\n                val tokenizer = HuggingFaceTokenizer.newInstance(\n                    File(modelDirectory(context), TOKENIZER_FILE).toPath(),\n                )\n                val environment = OrtEnvironment.getEnvironment()\n                val options = OrtSession.SessionOptions().apply {\n                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)\n                }\n                val session = environment.createSession(File(modelDirectory(context), MODEL_FILE).absolutePath, options)\n                Runtime(environment, session, tokenizer).also {\n                    runtime = it\n                    lastError = null\n                }\n            }.getOrElse { error ->'''
text = replace_once(text, old_runtime, new_runtime, 'HuggingFace tokenizer runtime')
old_tokenize = '''    private fun tokenize(processor: SentencePieceProcessor, text: String): Tokenized {\n        val raw: IntVector = processor.EncodeAsIds(text)\n        raw.use {\n            val available = minOf(raw.size().toInt(), MAX_TOKENS - 2)\n            val ids = LongArray(available + 2)\n            val mask = LongArray(available + 2) { 1L }\n            ids[0] = BOS_TOKEN_ID\n            for (index in 0 until available) {\n                val sentencePieceId = raw[index.toLong()]\n                ids[index + 1] = if (sentencePieceId == 0) UNKNOWN_TOKEN_ID else sentencePieceId.toLong() + TOKEN_ID_OFFSET\n            }\n            ids[ids.lastIndex] = EOS_TOKEN_ID\n            return Tokenized(ids, mask)\n        }\n    }'''
new_tokenize = '''    private fun tokenize(tokenizer: HuggingFaceTokenizer, text: String): Tokenized {\n        val encoding = tokenizer.encode(text)\n        val rawIds = encoding.ids\n        val rawMask = encoding.attentionMask\n        if (rawIds.size <= MAX_TOKENS) return Tokenized(rawIds, rawMask)\n\n        val ids = rawIds.copyOfRange(0, MAX_TOKENS)\n        val mask = rawMask.copyOfRange(0, MAX_TOKENS)\n        ids[MAX_TOKENS - 1] = rawIds.last()\n        mask[MAX_TOKENS - 1] = rawMask.last()\n        return Tokenized(ids, mask)\n    }'''
text = replace_once(text, old_tokenize, new_tokenize, 'HuggingFace encode path')
text = replace_once(text, 'label = "Tokenizer SentencePiece",', 'label = "Tokenizer Hugging Face",', 'tokenizer download label')
text = replace_once(text, 'private const val PACK_VERSION = 1', 'private const val PACK_VERSION = 2', 'model pack version')
text = replace_once(text, 'private const val TOKENIZER_FILE = "sentencepiece.bpe.model"', 'private const val TOKENIZER_FILE = "tokenizer.json"', 'tokenizer filename')
text = replace_once(
    text,
    'private const val TOKENIZER_URL = "https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/sentencepiece.bpe.model?download=true"',
    'private const val TOKENIZER_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json?download=true"',
    'tokenizer URL',
)
text = replace_once(
    text,
    'private const val TOKENIZER_SHA256 = "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865"',
    'private const val TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"',
    'tokenizer checksum',
)
text = replace_once(text, 'private const val APPROXIMATE_PACK_BYTES = 124_000_000L', 'private const val APPROXIMATE_PACK_BYTES = 136_000_000L', 'pack size')
text = replace_once(text, 'private const val TOKENIZER_APPROXIMATE_BYTES = 5_100_000L', 'private const val TOKENIZER_APPROXIMATE_BYTES = 17_082_730L', 'tokenizer size')
for obsolete in [
    '    private const val PAD_TOKEN_ID = 1L\n',
    '    private const val BOS_TOKEN_ID = 0L\n',
    '    private const val EOS_TOKEN_ID = 2L\n',
    '    private const val UNKNOWN_TOKEN_ID = 3L\n',
    '    private const val TOKEN_ID_OFFSET = 1L\n',
]:
    text = replace_once(text, obsolete, '', f'remove {obsolete.strip()}')
write(path, text)

# 3) Make E5 the only local semantic matcher. No lexical/hash fallback.
path = 'app/src/main/java/vn/nghetruyen/app/freesound/Mode3LibraryAssetMatcher.kt'
text = read(path)
text = replace_once(
    text,
    '''    private data class LocalText(\n        val text: String,\n        val fingerprint: Mode3OpenDescriptionVector.Fingerprint,\n    ) {\n        val isPresent: Boolean get() = text.isNotBlank()\n    }''',
    '''    private data class LocalText(\n        val text: String,\n    ) {\n        val isPresent: Boolean get() = text.isNotBlank()\n    }''',
    'remove lightweight fingerprints',
)
text = replace_once(
    text,
    '''    private data class NeedProfile(\n        val kind: AudioAssetKind,\n        val queryTokens: Set<String>,\n        val coreToken: String?,\n        val eventToken: String?,\n        val hints: List<LocalHint>,\n    ) {''',
    '''    private data class NeedProfile(\n        val kind: AudioAssetKind,\n        val queryTokens: Set<String>,\n        val coreToken: String?,\n        val eventToken: String?,\n        val queryText: LocalText,\n        val hints: List<LocalHint>,\n    ) {''',
    'query semantic text',
)
text = replace_once(
    text,
    '''        val semanticPassages = eligible.asSequence()\n            .flatMap { it.sections.semanticPassages() }\n            .distinct()\n            .toList()\n        val useE5 = semanticPassages.isNotEmpty() && Mode3E5SemanticEngine.allPassagesCached(semanticPassages)\n        if (!useE5 && semanticPassages.isNotEmpty()) Mode3E5SemanticEngine.requestPrewarm(semanticPassages)\n\n        val ranked = eligible.asSequence()\n            .mapNotNull { indexed -> score(profile, indexed, indexed.track, nowMillis, useE5) }''',
    '''        val semanticPassages = eligible.asSequence()\n            .flatMap { it.sections.semanticPassages() }\n            .distinct()\n            .toList()\n        val e5Ready = Mode3E5SemanticEngine.status().ready\n        val fullyIndexed = e5Ready && semanticPassages.isNotEmpty() &&\n            Mode3E5SemanticEngine.allPassagesCached(semanticPassages)\n        if (!fullyIndexed) {\n            if (semanticPassages.isNotEmpty()) Mode3E5SemanticEngine.requestPrewarm(semanticPassages)\n            val evaluation = Evaluation(\n                accepted = null,\n                topCandidates = emptyList(),\n                indexedTracks = eligible.size,\n                candidateTracks = 0,\n                elapsedMs = (System.nanoTime() - started) / 1_000_000L,\n                indexCacheHit = eligible.isNotEmpty() && cacheHits == eligible.size,\n            )\n            emitDiagnostics(need, evaluation)\n            return evaluation\n        }\n\n        val ranked = eligible.asSequence()\n            .mapNotNull { indexed -> score(profile, indexed, indexed.track, nowMillis) }''',
    'E5-only evaluate gate',
)
text = replace_once(
    text,
    '''        val passages = indexed.sections.semanticPassages().toList()\n        val useE5 = passages.isNotEmpty() && Mode3E5SemanticEngine.allPassagesCached(passages)\n        if (!useE5) Mode3E5SemanticEngine.requestPrewarm(passages)\n        return score(profile, indexed, track, nowMillis, useE5)?.takeIf(Match::accepted)''',
    '''        val passages = indexed.sections.semanticPassages().toList()\n        val ready = Mode3E5SemanticEngine.status().ready && passages.isNotEmpty() &&\n            Mode3E5SemanticEngine.allPassagesCached(passages)\n        if (!ready) {\n            Mode3E5SemanticEngine.requestPrewarm(passages)\n            return null\n        }\n        return score(profile, indexed, track, nowMillis)?.takeIf(Match::accepted)''',
    'E5-only strong match',
)
text = replace_once(
    text,
    '''        return NeedProfile(\n            kind = need.kind,\n            queryTokens = queryTokens,\n            coreToken = coreToken,\n            eventToken = eventToken,\n            hints = hints,\n        )''',
    '''        return NeedProfile(\n            kind = need.kind,\n            queryTokens = queryTokens,\n            coreToken = coreToken,\n            eventToken = eventToken,\n            queryText = localText(need.query),\n            hints = hints,\n        )''',
    'NeedProfile query text',
)
text = replace_once(
    text,
    '''        currentTrack: SceneMusicTrackEntity,\n        nowMillis: Long,\n        useE5: Boolean,\n    ): Match? {''',
    '''        currentTrack: SceneMusicTrackEntity,\n        nowMillis: Long,\n    ): Match? {''',
    'score signature',
)
text = text.replace(', useE5)', ')')
old_score = '''        val hintAware = profile.hintAware\n        val useScore = if (hintAware) average(profile.hints.map { hint ->\n            val direct = localSimilarity(hint.use, indexed.sections.use)\n            val shadeToUse = localSimilarity(hint.shade, indexed.sections.use) * 0.35\n            val useToShade = localSimilarity(hint.use, indexed.sections.shade) * 0.45\n            val positiveFallback = localSimilarity(hint.positive, indexed.sections.positive) * 0.55\n            max(max(direct, shadeToUse), max(useToShade, positiveFallback))\n        }) else 0.0\n        val shadeScore = if (hintAware) average(profile.hints.map { hint ->\n            val expectedShade = if (hint.shade.isPresent) hint.shade else hint.use\n            max(\n                localSimilarity(expectedShade, indexed.sections.shade),\n                localSimilarity(hint.use, indexed.sections.shade) * 0.45,\n            )\n        }) else 0.0\n        val allScore = if (hintAware) average(profile.hints.map { hint ->\n            localSimilarity(hint.positive, indexed.sections.positive)\n        }) else 0.0\n\n        val candidateAvoidConflict = if (hintAware && indexed.sections.avoid.isPresent) {\n            average(profile.hints.map { hint ->\n                max(\n                    localSimilarity(hint.positive, indexed.sections.avoid),\n                    max(\n                        localSimilarity(hint.use, indexed.sections.avoid),\n                        localSimilarity(hint.shade, indexed.sections.avoid) * 0.80,\n                    ),\n                )\n            })\n        } else 0.0\n        val needAvoidConflict = if (hintAware) {\n            average(profile.hints.map { hint ->\n                if (!hint.avoid.isPresent) 0.0 else max(\n                    localSimilarity(hint.avoid, indexed.sections.positive),\n                    max(\n                        localSimilarity(hint.avoid, indexed.sections.use),\n                        localSimilarity(hint.avoid, indexed.sections.shade) * 0.80,\n                    ),\n                )\n            })\n        } else 0.0\n        val avoidCoverage = max(candidateAvoidConflict, needAvoidConflict)\n\n        val contextScore = max(useScore, max(shadeScore * 0.92, allScore * 0.86))\n        val semanticMetadata = indexed.sections.structured &&\n            (indexed.sections.use.isPresent || indexed.sections.shade.isPresent)\n        val metadataQuality = when {\n            semanticMetadata -> "STRUCTURED"\n            indexed.sections.structured -> "PARTIAL"\n            else -> "RAW"\n        }\n\n        if (hintAware && contextScore <= 0.0 && allScore <= 0.0) return null\n        if (!hintAware && queryCoverage <= 0.0 && coreCoverage <= 0.0) return null\n\n        val repetitionPenalty = repetitionPenalty(currentTrack, nowMillis)\n        val structuredDescriptionScore = (\n            useScore * LOCAL_DESCRIPTION_USE_WEIGHT +\n                shadeScore * LOCAL_DESCRIPTION_SHADE_WEIGHT +\n                allScore * LOCAL_DESCRIPTION_WHOLE_WEIGHT -\n                avoidCoverage * LOCAL_DESCRIPTION_AVOID_PENALTY -\n                repetitionPenalty\n        ).coerceIn(0.0, 1.0)\n        val rawDescriptionScore = (\n            allScore * LOCAL_RAW_DESCRIPTION_WEIGHT +\n                queryCoverage * LOCAL_RAW_QUERY_FALLBACK_WEIGHT -\n                avoidCoverage * LOCAL_RAW_AVOID_PENALTY -\n                repetitionPenalty\n        ).coerceIn(0.0, 1.0)\n        val queryOnlyFallback = (\n            queryCoverage * 0.90 + coreCoverage * 0.10 - repetitionPenalty * 0.50\n        ).coerceIn(0.0, 1.0)\n\n        val selectionScore = when {\n            hintAware && semanticMetadata -> structuredDescriptionScore\n            hintAware -> rawDescriptionScore\n            else -> queryOnlyFallback\n        }'''
new_score = '''        val hintAware = profile.hintAware\n        val querySemanticScore = max(\n            localSimilarity(profile.queryText, indexed.sections.positive),\n            localSimilarity(profile.queryText, indexed.sections.all) * 0.96,\n        )\n        val useScore = if (hintAware) average(profile.hints.map { hint ->\n            val direct = localSimilarity(hint.use, indexed.sections.use)\n            val shadeToUse = localSimilarity(hint.shade, indexed.sections.use) * 0.32\n            val useToShade = localSimilarity(hint.use, indexed.sections.shade) * 0.42\n            val positiveBridge = localSimilarity(hint.positive, indexed.sections.positive) * 0.52\n            max(max(direct, shadeToUse), max(useToShade, positiveBridge))\n        }) else querySemanticScore\n        val shadeScore = if (hintAware) average(profile.hints.map { hint ->\n            val expectedShade = if (hint.shade.isPresent) hint.shade else hint.use\n            max(\n                localSimilarity(expectedShade, indexed.sections.shade),\n                localSimilarity(hint.use, indexed.sections.shade) * 0.42,\n            )\n        }) else querySemanticScore\n        val allScore = if (hintAware) average(profile.hints.map { hint ->\n            localSimilarity(hint.positive, indexed.sections.positive)\n        }) else querySemanticScore\n\n        val candidateAvoidConflict = if (indexed.sections.avoid.isPresent) {\n            if (hintAware) {\n                average(profile.hints.map { hint ->\n                    max(\n                        localSimilarity(hint.positive, indexed.sections.avoid),\n                        max(\n                            localSimilarity(hint.use, indexed.sections.avoid),\n                            localSimilarity(hint.shade, indexed.sections.avoid) * 0.78,\n                        ),\n                    )\n                })\n            } else {\n                localSimilarity(profile.queryText, indexed.sections.avoid)\n            }\n        } else 0.0\n        val needAvoidConflict = if (hintAware) {\n            average(profile.hints.map { hint ->\n                if (!hint.avoid.isPresent) 0.0 else max(\n                    localSimilarity(hint.avoid, indexed.sections.positive),\n                    max(\n                        localSimilarity(hint.avoid, indexed.sections.use),\n                        localSimilarity(hint.avoid, indexed.sections.shade) * 0.78,\n                    ),\n                )\n            })\n        } else 0.0\n        val avoidCoverage = max(candidateAvoidConflict, needAvoidConflict)\n\n        val contextScore = if (hintAware) {\n            max(useScore, max(shadeScore * 0.93, allScore * 0.88))\n        } else querySemanticScore\n        val semanticMetadata = indexed.sections.structured &&\n            (indexed.sections.use.isPresent || indexed.sections.shade.isPresent)\n        val metadataQuality = when {\n            semanticMetadata -> "STRUCTURED"\n            indexed.sections.structured -> "PARTIAL"\n            else -> "RAW"\n        }\n\n        if (contextScore <= 0.0) return null\n\n        val repetitionPenalty = repetitionPenalty(currentTrack, nowMillis)\n        val structuredDescriptionScore = (\n            useScore * LOCAL_DESCRIPTION_USE_WEIGHT +\n                shadeScore * LOCAL_DESCRIPTION_SHADE_WEIGHT +\n                allScore * LOCAL_DESCRIPTION_WHOLE_WEIGHT -\n                avoidCoverage * LOCAL_DESCRIPTION_AVOID_PENALTY -\n                repetitionPenalty\n        ).coerceIn(0.0, 1.0)\n        val unstructuredHintScore = (\n            allScore - avoidCoverage * LOCAL_DESCRIPTION_AVOID_PENALTY - repetitionPenalty\n        ).coerceIn(0.0, 1.0)\n        val queryDescriptionScore = (\n            querySemanticScore - avoidCoverage * LOCAL_DESCRIPTION_AVOID_PENALTY - repetitionPenalty\n        ).coerceIn(0.0, 1.0)\n\n        val selectionScore = when {\n            hintAware && semanticMetadata -> structuredDescriptionScore\n            hintAware -> unstructuredHintScore\n            else -> queryDescriptionScore\n        }'''
text = replace_once(text, old_score, new_score, 'E5-only local scoring')
text = replace_once(
    text,
    '''    private fun localSimilarity(first: LocalText, second: LocalText, useE5: Boolean): Double {\n        if (!first.isPresent || !second.isPresent) return 0.0\n        if (useE5) {\n            Mode3E5SemanticEngine.similarityOrNull(first.text, second.text)?.let { return it }\n        }\n        return Mode3OpenDescriptionVector.cosine(first.fingerprint, second.fingerprint)\n    }\n\n    private fun localText(value: String): LocalText {\n        val text = semanticText(value)\n        return LocalText(text, Mode3OpenDescriptionVector.build(text))\n    }''',
    '''    private fun localSimilarity(first: LocalText, second: LocalText): Double {\n        if (!first.isPresent || !second.isPresent) return 0.0\n        return Mode3E5SemanticEngine.similarityOrNull(first.text, second.text) ?: 0.0\n    }\n\n    private fun localText(value: String): LocalText = LocalText(semanticText(value))''',
    'remove local vector fallback',
)
text = replace_once(text, '    private const val DECISIVE_MIN_CONTEXT_SCORE = 0.48', '    private const val DECISIVE_MIN_CONTEXT_SCORE = 0.60', 'decisive context')
text = replace_once(text, '    private const val DECISIVE_SEMANTIC_SCORE = 0.68', '    private const val DECISIVE_SEMANTIC_SCORE = 0.64', 'decisive semantic')
text = replace_once(text, '    private const val DECISIVE_SELECTION_FIT = 0.68', '    private const val DECISIVE_SELECTION_FIT = 0.66', 'decisive selection')
text = replace_once(text, '    private const val MIN_LOCAL_SELECTION_FIT_MUSIC = 0.36', '    private const val MIN_LOCAL_SELECTION_FIT_MUSIC = 0.44', 'music E5 threshold')
text = replace_once(text, '    private const val MIN_LOCAL_SELECTION_FIT_AMBIENCE = 0.38', '    private const val MIN_LOCAL_SELECTION_FIT_AMBIENCE = 0.48', 'ambience E5 threshold')
text = replace_once(text, '    private const val MIN_LOCAL_SELECTION_FIT_SFX = 0.40', '    private const val MIN_LOCAL_SELECTION_FIT_SFX = 0.52', 'SFX E5 threshold')
text = replace_once(text, '    private const val LOCAL_DESCRIPTION_USE_WEIGHT = 0.50', '    private const val LOCAL_DESCRIPTION_USE_WEIGHT = 0.58', 'use weight')
text = replace_once(text, '    private const val LOCAL_DESCRIPTION_SHADE_WEIGHT = 0.28', '    private const val LOCAL_DESCRIPTION_SHADE_WEIGHT = 0.27', 'shade weight')
text = replace_once(text, '    private const val LOCAL_DESCRIPTION_WHOLE_WEIGHT = 0.22', '    private const val LOCAL_DESCRIPTION_WHOLE_WEIGHT = 0.15', 'whole weight')
text = replace_once(text, '    private const val LOCAL_DESCRIPTION_AVOID_PENALTY = 0.68', '    private const val LOCAL_DESCRIPTION_AVOID_PENALTY = 0.72', 'avoid penalty')
text = replace_once(text, '    private const val LOCAL_RAW_DESCRIPTION_WEIGHT = 0.88\n    private const val LOCAL_RAW_QUERY_FALLBACK_WEIGHT = 0.12\n    private const val LOCAL_RAW_AVOID_PENALTY = 0.60\n', '', 'remove raw fallback weights')
text = replace_once(text, '    private const val REMOTE_LEGACY_WEIGHT = 0.38', '    private const val REMOTE_LEGACY_WEIGHT = 0.30', 'remote legacy weight')
text = replace_once(text, '    private const val REMOTE_E5_WEIGHT = 0.62', '    private const val REMOTE_E5_WEIGHT = 0.70', 'remote E5 weight')
text = replace_once(text, '    private const val REMOTE_E5_MIN_FIT = 0.50', '    private const val REMOTE_E5_MIN_FIT = 0.48', 'remote E5 threshold')
text = replace_once(text, '    private val EMPTY_LOCAL_TEXT = LocalText("", Mode3OpenDescriptionVector.build(""))', '    private val EMPTY_LOCAL_TEXT = LocalText("")', 'empty local text')
write(path, text)

# 4) User-facing copy: E5 is optional but there is no semantic fallback.
path = 'app/src/main/java/vn/nghetruyen/app/MainActivity.kt'
text = read(path)
text = text.replace('Multilingual E5 Small INT8, khoảng 124 MB.', 'Multilingual E5 Small INT8, khoảng 136 MB.')
text = text.replace('Lỗi không xác định. Matcher nhẹ vẫn tiếp tục hoạt động.', 'Lỗi không xác định. Tìm kiếm ngữ nghĩa sẽ không hoạt động cho đến khi mô hình được tải thành công.')
write(path, text)

path = 'app/src/main/java/vn/nghetruyen/app/ui/components/FreesoundSettingsCard.kt'
text = read(path)
text = text.replace('else -> "Trạng thái: Chưa tải • khoảng 124 MB"', 'else -> "Trạng thái: Chưa tải • khoảng 136 MB"')
text = text.replace(
    '"Mô hình được lưu riêng khỏi APK và dùng lại qua các lần cập nhật ứng dụng. " +\n                    "Nếu chưa có mô hình, Mode 3 tự dùng matcher nhẹ dự phòng."',
    '"Mô hình được lưu riêng khỏi APK và dùng lại qua các lần cập nhật ứng dụng. " +\n                    "Nếu chưa tải mô hình, tìm kiếm ngữ nghĩa cục bộ sẽ không hoạt động."',
)
write(path, text)

# 5) Remove the obsolete lightweight matcher implementation entirely.
obsolete = ROOT / 'app/src/main/java/vn/nghetruyen/app/freesound/Mode3OpenDescriptionVector.kt'
if obsolete.exists():
    obsolete.unlink()

# Guardrails: no hidden lightweight fallback remains in production Mode 3 code.
matcher = read('app/src/main/java/vn/nghetruyen/app/freesound/Mode3LibraryAssetMatcher.kt')
engine = read('app/src/main/java/vn/nghetruyen/app/freesound/Mode3E5SemanticEngine.kt')
if 'Mode3OpenDescriptionVector' in matcher:
    raise SystemExit('lightweight matcher reference still present')
if 'DESCRIPTION_VECTOR_OPEN_VOCABULARY_FALLBACK' in engine:
    raise SystemExit('fallback backend still present')
if 'SentencePieceProcessor' in engine or 'org.bytedeco' in engine:
    raise SystemExit('old SentencePiece runtime still present')
