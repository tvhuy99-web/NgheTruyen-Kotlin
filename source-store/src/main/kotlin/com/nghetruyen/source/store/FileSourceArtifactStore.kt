package com.nghetruyen.source.store

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties







class FileSourceArtifactStore(private val root: Path) : SourceArtifactRegistry, SourceArtifactArchive {
    private val blobs = root.resolve("blobs")
    private val archiveIndex = root.resolve("archive")
    private val descriptors = root.resolve("descriptors")
    private val identities = root.resolve("identities")

    init {
        Files.createDirectories(blobs)
        Files.createDirectories(archiveIndex)
        Files.createDirectories(descriptors)
        Files.createDirectories(identities)
    }

    @Synchronized
    override fun stage(descriptor: SourceArtifactDescriptor, originalBytes: ByteArray) {
        require(SourceArtifactLifecycle.sha256(originalBytes) == descriptor.sha256) { "SOURCE_ARTIFACT_STAGE_HASH_MISMATCH" }
        val blob = blobPath(descriptor.sha256)
        Files.createDirectories(blob.parent)
        if (Files.exists(blob)) {
            require(SourceArtifactLifecycle.sha256(Files.readAllBytes(blob)) == descriptor.sha256) { "SOURCE_ARTIFACT_BLOB_CORRUPT" }
        } else {
            atomicWrite(blob, originalBytes, replace = false)
        }
        val pointer = propertiesOf(
            "artifactId" to descriptor.artifactId,
            "sha256" to descriptor.sha256,
        )
        val indexPath = archivePath(descriptor.artifactId)
        if (Files.exists(indexPath)) {
            val existing = readProperties(indexPath)
            require(existing.getProperty("artifactId") == descriptor.artifactId) { "SOURCE_ARTIFACT_ARCHIVE_COLLISION" }
            require(existing.getProperty("sha256") == descriptor.sha256) { "SOURCE_ARTIFACT_ARCHIVE_IMMUTABLE" }
        } else {
            atomicWrite(indexPath, encode(pointer), replace = false)
        }
    }

    @Synchronized
    override fun contains(artifactId: String): Boolean {
        val digest = sha256(artifactId) ?: return false
        val blob = blobPath(digest)
        return Files.isRegularFile(blob) && runCatching {
            SourceArtifactLifecycle.sha256(Files.readAllBytes(blob)) == digest
        }.getOrDefault(false)
    }

    @Synchronized
    override fun sha256(artifactId: String): String? {
        val index = archivePath(artifactId)
        if (!Files.isRegularFile(index)) return null
        val properties = readProperties(index)
        if (properties.getProperty("artifactId") != artifactId) return null
        return properties.getProperty("sha256")?.takeIf { it.matches(SHA256) }
    }

    @Synchronized
    override fun active(identity: SourceArtifactIdentity): SourceArtifactDescriptor? =
        state(identity).getProperty("active")?.takeIf(String::isNotBlank)?.let(::descriptor)
            ?.takeIf { it.identity == identity && it.state == SourceArtifactState.ACTIVE }

    @Synchronized
    fun disabled(identity: SourceArtifactIdentity): SourceArtifactDescriptor? =
        state(identity).getProperty("disabled")?.takeIf(String::isNotBlank)?.let(::descriptor)
            ?.takeIf { it.identity == identity && it.state == SourceArtifactState.DISABLED }

    @Synchronized
    override fun previousKnownGood(identity: SourceArtifactIdentity): SourceArtifactDescriptor? =
        state(identity).getProperty("previous")?.takeIf(String::isNotBlank)?.let(::descriptor)
            ?.takeIf { it.identity == identity && it.state == SourceArtifactState.PREVIOUS_KNOWN_GOOD }

     
    @Synchronized
    fun activeArtifacts(ecosystem: SourceEcosystem? = null): List<SourceArtifactDescriptor> =
        installedArtifacts(ecosystem).filter { it.state == SourceArtifactState.ACTIVE }

     
    @Synchronized
    fun installedArtifacts(ecosystem: SourceEcosystem? = null): List<SourceArtifactDescriptor> {
        if (!Files.isDirectory(identities)) return emptyList()
        val paths = Files.list(identities).use { stream -> stream.filter(Files::isRegularFile).toList() }
        return paths.mapNotNull { path ->
            runCatching {
                val pointer = readProperties(path)
                val artifactId = pointer.getProperty("active")?.takeIf(String::isNotBlank)
                    ?: pointer.getProperty("disabled")?.takeIf(String::isNotBlank)
                    ?: return@runCatching null
                val value = descriptor(artifactId) ?: return@runCatching null
                require(value.state in setOf(SourceArtifactState.ACTIVE, SourceArtifactState.DISABLED)) {
                    "SOURCE_INSTALLED_POINTER_STATE_INVALID:$artifactId"
                }
                require(pointer.getProperty("ecosystem") == value.identity.ecosystem.name) { "SOURCE_INSTALLED_POINTER_IDENTITY_MISMATCH" }
                require(pointer.getProperty("repositoryId") == value.identity.repositoryId) { "SOURCE_INSTALLED_POINTER_IDENTITY_MISMATCH" }
                require(pointer.getProperty("remoteIdentity") == value.identity.remoteIdentity) { "SOURCE_INSTALLED_POINTER_IDENTITY_MISMATCH" }
                value.takeIf { ecosystem == null || it.identity.ecosystem == ecosystem }
            }.getOrNull()
        }.sortedBy { it.identity.canonicalKey() }
    }

    @Synchronized
    override fun commit(transition: SourceArtifactTransition) {
        transition.afterActive?.let(::writeDescriptor)
        transition.previousKnownGood?.let(::writeDescriptor)
        transition.quarantined?.let(::writeDescriptor)

        val current = state(transition.identity)
        val next = Properties().apply { putAll(current) }
        val after = transition.afterActive
        when (after?.state) {
            SourceArtifactState.ACTIVE -> {
                next.setProperty("active", after.artifactId)
                next.remove("disabled")
            }
            SourceArtifactState.DISABLED -> {
                next.setProperty("disabled", after.artifactId)
                next.remove("active")
            }
            else -> if (transition.beforeActive != null) next.remove("active")
        }
        transition.previousKnownGood?.let { next.setProperty("previous", it.artifactId) }

        val rollback = transition.beforeActive?.state == SourceArtifactState.ACTIVE &&
            after?.state == SourceArtifactState.ACTIVE &&
            transition.beforeActive.artifactId != after.artifactId &&
            transition.quarantined?.artifactId == transition.beforeActive.artifactId &&
            transition.previousKnownGood == null
        if (rollback) next.remove("previous")

        next.setProperty("ecosystem", transition.identity.ecosystem.name)
        next.setProperty("repositoryId", transition.identity.repositoryId)
        next.setProperty("remoteIdentity", transition.identity.remoteIdentity)
        atomicWrite(identityPath(transition.identity), encode(next), replace = true)
    }

     
    @Synchronized
    fun uninstall(identity: SourceArtifactIdentity): Boolean = Files.deleteIfExists(identityPath(identity))

    @Synchronized
    fun descriptor(artifactId: String): SourceArtifactDescriptor? {
        val path = descriptorPath(artifactId)
        if (!Files.isRegularFile(path)) return null
        val p = readProperties(path)
        if (p.getProperty("artifactId") != artifactId) return null
        return runCatching { decodeDescriptor(p) }.getOrNull()
    }

    @Synchronized
    fun originalBytes(artifactId: String): ByteArray? {
        val digest = sha256(artifactId) ?: return null
        val path = blobPath(digest)
        if (!Files.isRegularFile(path)) return null
        val bytes = Files.readAllBytes(path)
        return bytes.takeIf { SourceArtifactLifecycle.sha256(it) == digest }
    }

    private fun writeDescriptor(value: SourceArtifactDescriptor) {
        require(contains(value.artifactId)) { "SOURCE_ARTIFACT_BYTES_NOT_STAGED:${value.artifactId}" }
        val path = descriptorPath(value.artifactId)
        val encoded = encodeDescriptor(value)
        atomicWrite(path, encoded, replace = true)
    }

    private fun state(identity: SourceArtifactIdentity): Properties {
        val path = identityPath(identity)
        if (!Files.isRegularFile(path)) return Properties()
        val p = readProperties(path)
        require(p.getProperty("ecosystem") == identity.ecosystem.name) { "SOURCE_IDENTITY_STATE_MISMATCH" }
        require(p.getProperty("repositoryId") == identity.repositoryId) { "SOURCE_IDENTITY_STATE_MISMATCH" }
        require(p.getProperty("remoteIdentity") == identity.remoteIdentity) { "SOURCE_IDENTITY_STATE_MISMATCH" }
        return p
    }

    private fun encodeDescriptor(value: SourceArtifactDescriptor): ByteArray = encode(propertiesOf(
        "artifactId" to value.artifactId,
        "ecosystem" to value.identity.ecosystem.name,
        "repositoryId" to value.identity.repositoryId,
        "remoteIdentity" to value.identity.remoteIdentity,
        "version" to value.version.orEmpty(),
        "sha256" to value.sha256,
        "profile" to value.compatibilityProfile?.id.orEmpty(),
        "state" to value.state.name,
        "trust" to value.trust.name,
        "installedAtEpochMs" to value.installedAtEpochMs.toString(),
        "activatedAtEpochMs" to value.activatedAtEpochMs?.toString().orEmpty(),
        "previousKnownGoodArtifactId" to value.previousKnownGoodArtifactId.orEmpty(),
    ))

    private fun decodeDescriptor(p: Properties): SourceArtifactDescriptor {
        val ecosystem = SourceEcosystem.valueOf(p.required("ecosystem"))
        val identity = SourceArtifactIdentity(
            ecosystem = ecosystem,
            repositoryId = p.required("repositoryId"),
            remoteIdentity = p.required("remoteIdentity"),
        )
        return SourceArtifactDescriptor(
            artifactId = p.required("artifactId"),
            identity = identity,
            version = p.getProperty("version")?.takeIf(String::isNotBlank),
            sha256 = p.required("sha256"),
            compatibilityProfile = p.getProperty("profile")?.takeIf(String::isNotBlank)?.let {
                SourceCompatibilityProfile(ecosystem, it)
            },
            state = SourceArtifactState.valueOf(p.required("state")),
            trust = SourceTrustState.valueOf(p.required("trust")),
            installedAtEpochMs = p.required("installedAtEpochMs").toLong(),
            activatedAtEpochMs = p.getProperty("activatedAtEpochMs")?.takeIf(String::isNotBlank)?.toLong(),
            previousKnownGoodArtifactId = p.getProperty("previousKnownGoodArtifactId")?.takeIf(String::isNotBlank),
        )
    }

    private fun archivePath(artifactId: String): Path = archiveIndex.resolve(hashKey(artifactId) + ".properties")
    private fun descriptorPath(artifactId: String): Path = descriptors.resolve(hashKey(artifactId) + ".properties")
    private fun identityPath(identity: SourceArtifactIdentity): Path = identities.resolve(hashKey(identity.canonicalKey()) + ".properties")
    private fun blobPath(digest: String): Path = blobs.resolve(digest.take(2)).resolve("$digest.bin")
    private fun hashKey(value: String): String = SourceArtifactLifecycle.sha256(value.toByteArray(Charsets.UTF_8))

    private fun readProperties(path: Path): Properties = Properties().apply {
        ByteArrayInputStream(Files.readAllBytes(path)).use(::load)
    }

    private fun encode(properties: Properties): ByteArray = ByteArrayOutputStream().use { output ->
        properties.store(output, null)
        output.toByteArray()
    }

    private fun propertiesOf(vararg values: Pair<String, String>): Properties = Properties().apply {
        values.forEach { (key, value) -> setProperty(key, value) }
    }

    private fun Properties.required(key: String): String = getProperty(key)?.takeIf(String::isNotBlank)
        ?: error("SOURCE_ARTIFACT_METADATA_REQUIRED:$key")

    private fun atomicWrite(path: Path, bytes: ByteArray, replace: Boolean) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else arrayOf(StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(temp, path, *options)
            } catch (_: AtomicMoveNotSupportedException) {
                val fallback = if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
                Files.move(temp, path, *fallback)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
