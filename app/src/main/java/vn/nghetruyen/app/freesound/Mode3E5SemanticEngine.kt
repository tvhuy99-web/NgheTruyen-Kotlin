package vn.nghetruyen.app.freesound

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

internal object Mode3E5SemanticEngine {
    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFile: String,
    ) {
        val fraction: Double
            get() = if (totalBytes <= 0L) 0.0 else (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
    }

    data class Status(
        val installed: Boolean,
        val ready: Boolean,
        val prewarming: Boolean,
        val modelId: String,
        val packVersion: Int,
        val approximateBytes: Long,
        val backend: String,
        val lastError: String?,
    )

    private data class Runtime(
        val environment: OrtEnvironment,
        val session: OrtSession,
        val tokenizer: HuggingFaceTokenizer,
    )

    private data class Tokenized(
        val ids: LongArray,
        val mask: LongArray,
    )

    @Volatile private var applicationContext: Context? = null
    @Volatile private var runtime: Runtime? = null
    @Volatile private var lastError: String? = null
    @Volatile private var prewarming = false

    private val runtimeLock = Any()
    private val inferenceLock = Any()
    private val prewarmMutex = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prewarmScheduled = AtomicBoolean(false)
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val memoryLock = Any()
    private val memoryCache = object : LinkedHashMap<String, FloatArray>(MEMORY_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > MEMORY_CACHE_ENTRIES
    }

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun status(): Status = Status(
        installed = isInstalled(),
        ready = runtime != null,
        prewarming = prewarming,
        modelId = MODEL_ID,
        packVersion = PACK_VERSION,
        approximateBytes = APPROXIMATE_PACK_BYTES,
        backend = backendName(),
        lastError = lastError,
    )

    fun backendName(): String = when {
        runtime != null -> "MULTILINGUAL_E5_SMALL_INT8"
        isInstalled() -> "MULTILINGUAL_E5_SMALL_INT8_LOADING"
        else -> "DISABLED_NO_E5_MODEL"
    }

    fun isInstalled(): Boolean {
        val context = applicationContext ?: return false
        val directory = modelDirectory(context)
        val model = File(directory, MODEL_FILE)
        val tokenizer = File(directory, TOKENIZER_FILE)
        val manifest = File(directory, MANIFEST_FILE)
        if (!model.isFile || model.length() <= 0L || !tokenizer.isFile || tokenizer.length() <= 0L || !manifest.isFile) {
            return false
        }
        val properties = runCatching {
            Properties().apply { manifest.inputStream().buffered().use(::load) }
        }.getOrNull() ?: return false
        return properties.getProperty("modelId") == MODEL_ID &&
            properties.getProperty("packVersion") == PACK_VERSION.toString() &&
            properties.getProperty("modelSha256") == MODEL_SHA256 &&
            properties.getProperty("tokenizerSha256") == TOKENIZER_SHA256 &&
            properties.getProperty("verified") == "true"
    }

    suspend fun install(progress: (DownloadProgress) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val context = requireNotNull(applicationContext) { "Semantic engine chưa được khởi tạo." }
            val directory = modelDirectory(context).apply { mkdirs() }
            val model = File(directory, MODEL_FILE)
            val tokenizer = File(directory, TOKENIZER_FILE)
            downloadVerified(
                url = MODEL_URL,
                destination = model,
                expectedSha256 = MODEL_SHA256,
                baseCompletedBytes = 0L,
                overallTotalBytes = APPROXIMATE_PACK_BYTES,
                label = "Mô hình E5 INT8",
                progress = progress,
            )
            downloadVerified(
                url = TOKENIZER_URL,
                destination = tokenizer,
                expectedSha256 = TOKENIZER_SHA256,
                baseCompletedBytes = model.length(),
                overallTotalBytes = maxOf(APPROXIMATE_PACK_BYTES, model.length() + TOKENIZER_APPROXIMATE_BYTES),
                label = "Tokenizer Hugging Face",
                progress = progress,
            )
            writeManifest(directory)
            closeRuntime()
            synchronized(memoryLock) { memoryCache.clear() }
            lastError = null
        }.onFailure { error ->
            lastError = error.message ?: error.javaClass.simpleName
        }
    }

    fun deleteModel(): Boolean {
        closeRuntime()
        synchronized(memoryLock) { memoryCache.clear() }
        val context = applicationContext ?: return false
        val deletedModel = modelDirectory(context).deleteRecursively()
        embeddingDirectory(context).deleteRecursively()
        lastError = null
        return deletedModel
    }

    fun allPassagesCached(texts: Collection<String>): Boolean {
        if (runtime == null || texts.isEmpty()) return false
        return texts.asSequence()
            .map(::semanticText)
            .filter(String::isNotBlank)
            .distinct()
            .all(::hasPassageCache)
    }

    fun requestPrewarm(texts: Collection<String>) {
        if (texts.isEmpty() || !isInstalled()) return
        val normalized = texts.asSequence().map(::semanticText).filter(String::isNotBlank).distinct().toList()
        if (normalized.isEmpty()) return
        if (!prewarmScheduled.compareAndSet(false, true)) return
        backgroundScope.launch {
            try {
                prewarmPassages(normalized)
            } finally {
                prewarmScheduled.set(false)
            }
        }
    }

    suspend fun prewarmPassages(
        texts: Collection<String>,
        progress: ((done: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        prewarmMutex.withLock {
            val rt = ensureRuntime() ?: return@withLock 0
            val missing = texts.asSequence()
                .map(::semanticText)
                .filter(String::isNotBlank)
                .distinct()
                .filterNot(::hasPassageCache)
                .toList()
            if (missing.isEmpty()) return@withLock 0
            prewarming = true
            try {
                var completed = 0
                missing.chunked(PREWARM_BATCH_SIZE).forEach { chunk ->
                    val vectors = embedBatch(rt, chunk.map { "$PASSAGE_PREFIX$it" })
                    chunk.zip(vectors).forEach { (text, vector) ->
                        savePassage(text, vector)
                    }
                    completed += chunk.size
                    progress?.invoke(completed, missing.size)
                }
                completed
            } finally {
                prewarming = false
            }
        }
    }

    fun similarityOrNull(
        queryText: String,
        passageText: String,
        allowPassageInference: Boolean = false,
    ): Double? {
        val rt = runtime ?: return null
        val query = semanticText(queryText)
        val passage = semanticText(passageText)
        if (query.isBlank() || passage.isBlank()) return null
        val queryVector = loadOrEmbedQuery(rt, query) ?: return null
        val passageVector = loadPassage(passage) ?: if (allowPassageInference) {
            runCatching {
                embedBatch(rt, listOf("$PASSAGE_PREFIX$passage")).first().also { savePassage(passage, it) }
            }.getOrElse {
                lastError = it.message ?: it.javaClass.simpleName
                return null
            }
        } else return null
        return calibrate(dot(queryVector, passageVector))
    }

    private suspend fun ensureRuntime(): Runtime? = withContext(Dispatchers.IO) {
        runtime?.let { return@withContext it }
        synchronized(runtimeLock) {
            runtime?.let { return@synchronized it }
            if (!isInstalled()) return@synchronized null
            val context = applicationContext ?: return@synchronized null
            runCatching {
                val tokenizer = HuggingFaceTokenizer.newInstance(
                    File(modelDirectory(context), TOKENIZER_FILE).toPath(),
                )
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                }
                val session = environment.createSession(File(modelDirectory(context), MODEL_FILE).absolutePath, options)
                Runtime(environment, session, tokenizer).also {
                    runtime = it
                    lastError = null
                }
            }.getOrElse { error ->
                lastError = error.message ?: error.javaClass.simpleName
                null
            }
        }
    }

    private fun closeRuntime() {
        synchronized(runtimeLock) {
            val current = runtime
            runtime = null
            runCatching { current?.session?.close() }
            runCatching { current?.tokenizer?.close() }
        }
    }

    private fun loadOrEmbedQuery(rt: Runtime, text: String): FloatArray? {
        val key = "q:${sha256Text(text)}"
        synchronized(memoryLock) { memoryCache[key]?.let { return it } }
        return runCatching {
            embedBatch(rt, listOf("$QUERY_PREFIX$text")).first().also { vector ->
                synchronized(memoryLock) { memoryCache[key] = vector }
            }
        }.getOrElse { error ->
            lastError = error.message ?: error.javaClass.simpleName
            null
        }
    }

    private fun loadPassage(text: String): FloatArray? {
        val key = passageKey(text)
        synchronized(memoryLock) { memoryCache[key]?.let { return it } }
        val context = applicationContext ?: return null
        val file = passageFile(context, text)
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                check(input.readInt() == CACHE_MAGIC)
                check(input.readInt() == CACHE_VERSION)
                val dimensions = input.readInt()
                check(dimensions == EMBEDDING_DIMENSIONS)
                FloatArray(dimensions) { input.readFloat() }
            }.also { vector -> synchronized(memoryLock) { memoryCache[key] = vector } }
        }.getOrElse {
            file.delete()
            null
        }
    }

    private fun savePassage(text: String, vector: FloatArray) {
        if (vector.size != EMBEDDING_DIMENSIONS) return
        val context = applicationContext ?: return
        val destination = passageFile(context, text)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
            output.writeInt(CACHE_MAGIC)
            output.writeInt(CACHE_VERSION)
            output.writeInt(vector.size)
            vector.forEach(output::writeFloat)
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        synchronized(memoryLock) { memoryCache[passageKey(text)] = vector }
    }

    private fun hasPassageCache(text: String): Boolean {
        val normalized = semanticText(text)
        if (normalized.isBlank()) return false
        val key = passageKey(normalized)
        synchronized(memoryLock) { if (memoryCache.containsKey(key)) return true }
        val context = applicationContext ?: return false
        return passageFile(context, normalized).isFile
    }

    private fun embedBatch(rt: Runtime, prefixedTexts: List<String>): List<FloatArray> = synchronized(inferenceLock) {
        require(prefixedTexts.isNotEmpty())
        val tokenized = prefixedTexts.map { tokenize(rt.tokenizer, it) }
        val maxLength = tokenized.maxOf { it.ids.size }.coerceAtMost(MAX_TOKENS)
        val batchSize = tokenized.size
        val ids = LongArray(batchSize * maxLength) { PAD_TOKEN_ID }
        val masks = LongArray(batchSize * maxLength)
        tokenized.forEachIndexed { row, item ->
            val length = minOf(item.ids.size, maxLength)
            for (column in 0 until length) {
                ids[row * maxLength + column] = item.ids[column]
                masks[row * maxLength + column] = item.mask[column]
            }
        }
        val shape = longArrayOf(batchSize.toLong(), maxLength.toLong())
        OnnxTensor.createTensor(rt.environment, LongBuffer.wrap(ids), shape).use { inputIds ->
            OnnxTensor.createTensor(rt.environment, LongBuffer.wrap(masks), shape).use { attentionMask ->
                val inputs = linkedMapOf<String, OnnxTensor>(
                    "input_ids" to inputIds,
                    "attention_mask" to attentionMask,
                )
                val tokenTypes = if ("token_type_ids" in rt.session.inputNames) {
                    OnnxTensor.createTensor(rt.environment, LongBuffer.wrap(LongArray(ids.size)), shape)
                } else null
                if (tokenTypes != null) inputs["token_type_ids"] = tokenTypes
                try {
                    rt.session.run(inputs).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = result[0].value as Array<Array<FloatArray>>
                        List(batchSize) { row -> meanPoolAndNormalize(hidden[row], masks, row * maxLength, maxLength) }
                    }
                } finally {
                    tokenTypes?.close()
                }
            }
        }
    }

    private fun tokenize(tokenizer: HuggingFaceTokenizer, text: String): Tokenized {
        val encoding = tokenizer.encode(text)
        val rawIds = encoding.ids
        val rawMask = encoding.attentionMask
        if (rawIds.size <= MAX_TOKENS) return Tokenized(rawIds, rawMask)

        val ids = rawIds.copyOfRange(0, MAX_TOKENS)
        val mask = rawMask.copyOfRange(0, MAX_TOKENS)
        ids[MAX_TOKENS - 1] = rawIds.last()
        mask[MAX_TOKENS - 1] = rawMask.last()
        return Tokenized(ids, mask)
    }

    private fun meanPoolAndNormalize(
        tokens: Array<FloatArray>,
        flatMask: LongArray,
        maskOffset: Int,
        maxLength: Int,
    ): FloatArray {
        require(tokens.isNotEmpty())
        val dimensions = tokens.first().size
        val pooled = FloatArray(dimensions)
        var count = 0
        val tokenCount = minOf(tokens.size, maxLength)
        for (tokenIndex in 0 until tokenCount) {
            if (flatMask[maskOffset + tokenIndex] == 0L) continue
            val token = tokens[tokenIndex]
            for (dimension in 0 until dimensions) pooled[dimension] += token[dimension]
            count += 1
        }
        if (count > 0) {
            for (dimension in pooled.indices) pooled[dimension] /= count.toFloat()
        }
        var normSquared = 0.0
        pooled.forEach { value -> normSquared += value.toDouble() * value.toDouble() }
        val norm = sqrt(normSquared)
        if (norm > 0.0) {
            for (dimension in pooled.indices) pooled[dimension] = (pooled[dimension] / norm).toFloat()
        }
        return pooled
    }

    private fun dot(first: FloatArray, second: FloatArray): Double {
        if (first.size != second.size || first.isEmpty()) return 0.0
        var value = 0.0
        for (index in first.indices) value += first[index].toDouble() * second[index].toDouble()
        return value.coerceIn(-1.0, 1.0)
    }

    private fun calibrate(rawCosine: Double): Double =
        ((rawCosine - RAW_COSINE_FLOOR) / RAW_COSINE_RANGE).coerceIn(0.0, 1.0)

    private fun passageKey(text: String): String = "p:${sha256Text(semanticText(text))}"

    private fun passageFile(context: Context, text: String): File =
        File(embeddingDirectory(context), "${sha256Text("$PASSAGE_PREFIX${semanticText(text)}")}.e5")

    private fun semanticText(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun modelDirectory(context: Context): File =
        File(context.filesDir, "models/$MODEL_ID/v$PACK_VERSION")

    private fun embeddingDirectory(context: Context): File =
        File(context.filesDir, "semantic_embeddings/$MODEL_ID/v$PACK_VERSION")

    private fun writeManifest(directory: File) {
        val properties = Properties().apply {
            setProperty("modelId", MODEL_ID)
            setProperty("packVersion", PACK_VERSION.toString())
            setProperty("dimensions", EMBEDDING_DIMENSIONS.toString())
            setProperty("maxTokens", MAX_TOKENS.toString())
            setProperty("modelSha256", MODEL_SHA256)
            setProperty("tokenizerSha256", TOKENIZER_SHA256)
            setProperty("verified", "true")
        }
        val temporary = File(directory, "$MANIFEST_FILE.tmp")
        temporary.outputStream().buffered().use { properties.store(it, null) }
        val destination = File(directory, MANIFEST_FILE)
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun downloadVerified(
        url: String,
        destination: File,
        expectedSha256: String,
        baseCompletedBytes: Long,
        overallTotalBytes: Long,
        label: String,
        progress: (DownloadProgress) -> Unit,
    ) {
        if (destination.isFile && sha256File(destination) == expectedSha256) return
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        var existing = partial.takeIf(File::isFile)?.length() ?: 0L
        val requestBuilder = Request.Builder().url(url)
        if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            check(response.isSuccessful) { "Tải $label thất bại: HTTP ${response.code}" }
            val append = existing > 0L && response.code == 206
            if (!append) {
                existing = 0L
                if (partial.exists()) partial.delete()
            }
            val body = checkNotNull(response.body) { "Phản hồi tải $label không có dữ liệu." }
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var written = existing
            FileOutputStream(partial, append).use { output ->
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        progress(
                            DownloadProgress(
                                downloadedBytes = baseCompletedBytes + written,
                                totalBytes = overallTotalBytes,
                                currentFile = label,
                            ),
                        )
                    }
                    output.fd.sync()
                }
            }
        }
        val actualSha256 = sha256File(partial)
        check(actualSha256 == expectedSha256) {
            partial.delete()
            "Checksum $label không hợp lệ. Mong đợi $expectedSha256 nhưng nhận $actualSha256."
        }
        if (destination.exists()) destination.delete()
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = true)
            partial.delete()
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun sha256Text(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private const val MODEL_ID = "multilingual-e5-small"
    private const val PACK_VERSION = 2
    private const val MODEL_FILE = "model_int8.onnx"
    private const val TOKENIZER_FILE = "tokenizer.json"
    private const val MANIFEST_FILE = "model-pack.properties"
    private const val MODEL_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_int8.onnx?download=true"
    private const val TOKENIZER_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json?download=true"
    private const val MODEL_SHA256 = "4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c"
    private const val TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
    private const val APPROXIMATE_PACK_BYTES = 136_000_000L
    private const val TOKENIZER_APPROXIMATE_BYTES = 17_082_730L
    private const val EMBEDDING_DIMENSIONS = 384
    private const val MAX_TOKENS = 192
    private const val PREWARM_BATCH_SIZE = 4
    private const val MEMORY_CACHE_ENTRIES = 768
    private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
    private const val CACHE_MAGIC = 0x4535534D
    private const val CACHE_VERSION = 1
    private const val PAD_TOKEN_ID = 1L
    private const val QUERY_PREFIX = "query: "
    private const val PASSAGE_PREFIX = "passage: "
    private const val RAW_COSINE_FLOOR = 0.68
    private const val RAW_COSINE_RANGE = 0.24
}
