package vn.nghetruyen.app.sourceplatform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import vn.nghetruyen.source.runtime.SourceSecretKeyProvider
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidSourceSecretKeyProvider : SourceSecretKeyProvider {
    @Synchronized
    override fun keyFor(sourceId: String): SecretKey {
        val alias = "vn.nghetruyen.sourcepack.secret.${sourceId.replace(Regex("[^A-Za-z0-9_.-]"), "_")}".take(220)
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    companion object { private const val KEYSTORE = "AndroidKeyStore" }
}
