package app.hail.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App settings. The backend device token is encrypted with an Android Keystore key. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hail", Context.MODE_PRIVATE)

    var consentGiven: Boolean
        get() = sp.getBoolean("consent", false)
        set(v) = sp.edit().putBoolean("consent", v).apply()

    var backendUrl: String
        get() = sp.getString("backend_url", BuildConfig.DEFAULT_BACKEND_URL)!!
        set(v) = sp.edit().putString("backend_url", v.trimEnd('/')).apply()

    /** Debug builds only: answer CHECK_FARES with canned fares to test the loop end to end. */
    var simulateFares: Boolean
        get() = BuildConfig.DEBUG && sp.getBoolean("simulate", false)
        set(v) = sp.edit().putBoolean("simulate", v).apply()

    var deviceToken: String?
        get() = sp.getString("token", null)?.let { decrypt(it) }
        set(v) = sp.edit().putString("token", v?.let { encrypt(it) }).apply()

    /** Local wipe: settings, token and the Keystore key. */
    fun wipe() {
        sp.edit().clear().apply()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String? = runCatching {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()

    companion object {
        private const val KEY_ALIAS = "hail_token_key"
    }
}
