package vn.nghetruyen.source.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm
import vn.nghetruyen.source.packagekit.SourceTrustKey
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class SourceRepositoryVerifierTest {
    @Test fun verifiesCanonicalPayloadEvenWhenInputFieldsAreReordered() {
        val now = System.currentTimeMillis()
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val packageFields = linkedMapOf<String, JsonValue>(
            "sourceId" to JsonValue.Str("vn.nghetruyen.sources.remote"),
            "name" to JsonValue.Str("Remote"),
            "version" to JsonValue.Str("1.2.0"),
            "description" to JsonValue.Str("Fixture"),
            "packageUrl" to JsonValue.Str("https://downloads.example.org/remote.ntsource"),
            "packageSha256" to JsonValue.Str("a".repeat(64)),
            "packageBytes" to JsonValue.Num(4096.0, "4096"),
            "minAppVersion" to JsonValue.Str("1.3.0"),
            "maxAppVersion" to JsonValue.Str("2.0.0"),
            "adult" to JsonValue.Bool(false),
            "changelog" to JsonValue.Str("Network runtime"),
        )
        val canonicalFields = linkedMapOf<String, JsonValue>(
            "schemaVersion" to JsonValue.Num(1.0, "1"),
            "repositoryId" to JsonValue.Str("vn.nghetruyen.repositories.test"),
            "name" to JsonValue.Str("Test Repository"),
            "generatedAtEpochMs" to JsonValue.Num(now.toDouble(), now.toString()),
            "expiresAtEpochMs" to JsonValue.Num((now + 86_400_000L).toDouble(), (now + 86_400_000L).toString()),
            "signerKeyId" to JsonValue.Str("test-key"),
            "signatureAlgorithm" to JsonValue.Str("ECDSA_P256_SHA256"),
            "packages" to JsonValue.Arr(listOf(JsonValue.Obj(packageFields))),
        )
        val canonical = JsonCodec.stringify(JsonValue.Obj(canonicalFields)).toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(canonical)
            sign()
        }
        val reordered = linkedMapOf<String, JsonValue>(
            "signature" to JsonValue.Str(Base64.getEncoder().encodeToString(signature)),
            "packages" to JsonValue.Arr(listOf(JsonValue.Obj(LinkedHashMap(packageFields.entries.reversed().associate { it.toPair() })))),
            "signatureAlgorithm" to canonicalFields.getValue("signatureAlgorithm"),
            "signerKeyId" to canonicalFields.getValue("signerKeyId"),
            "expiresAtEpochMs" to canonicalFields.getValue("expiresAtEpochMs"),
            "generatedAtEpochMs" to canonicalFields.getValue("generatedAtEpochMs"),
            "name" to canonicalFields.getValue("name"),
            "repositoryId" to canonicalFields.getValue("repositoryId"),
            "schemaVersion" to canonicalFields.getValue("schemaVersion"),
        )
        val trust = SourceTrustKey("test-key", SourceSignatureAlgorithm.ECDSA_P256_SHA256, keyPair.public.encoded)
        val raw = JsonCodec.stringify(JsonValue.Obj(reordered)).toByteArray()
        val result = SourceRepositoryVerifier(clockMs = { now }).verify(raw, listOf(trust))
        assertTrue(result is SourcePlatformResult.Success)
        val repository = (result as SourcePlatformResult.Success).value.index
        assertEquals(SemanticVersion(1, 2, 0), repository.packages.single().version)

        val tampered = raw.toString(Charsets.UTF_8).replace("Test Repository", "Tampered").toByteArray()
        val rejected = SourceRepositoryVerifier(clockMs = { now }).verify(tampered, listOf(trust))
        assertTrue(rejected is SourcePlatformResult.Failure)
        assertEquals(SourceErrorCode.REPOSITORY_SIGNATURE_INVALID, (rejected as SourcePlatformResult.Failure).error.code)
    }
}
