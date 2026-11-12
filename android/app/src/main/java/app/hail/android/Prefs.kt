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

    /** Location and notifications are asked once, at the first ride request, not in setup. */
    var ridePermsAsked: Boolean
        get() = sp.getBoolean("ride_perms_asked", false)
        set(v) = sp.edit().putBoolean("ride_perms_asked", v).apply()

    var consentGiven: Boolean
        get() = sp.getBoolean("consent", false)
        set(v) = sp.edit().putBoolean("consent", v).apply()

    var backendUrl: String
        get() = sp.getString("backend_url", null)
            ?.takeUnless { it == "http://10.0.2.2:8000" } // old emulator default from early builds
            ?: BuildConfig.DEFAULT_BACKEND_URL
        set(v) = sp.edit().putString("backend_url", v.trimEnd('/')).apply()

    /** Ride apps the user switched on in Connected apps. Default: all three. */
    var enabledApps: Set<String>
        get() = sp.getStringSet("enabled_apps", null) ?: RideApps.packages.keys
        set(v) = sp.edit().putStringSet("enabled_apps", v).apply()

    /** Ride-type dropdown: null = "Any" (Hail asks per trip). */
    var rideCategory: String?
        get() = sp.getString("ride_category", null)
        set(v) = sp.edit().putString("ride_category", v).apply()

    /** Last known favourites (JSON array), so the buttons show instantly and offline. */
    var favoritesCache: String
        get() = sp.getString("favorites", "[]")!!
        set(v) = sp.edit().putString("favorites", v).apply()

    var welcomeSeen: Boolean
        get() = sp.getBoolean("welcome_seen", false)
        set(v) = sp.edit().putBoolean("welcome_seen", v).apply()

    var appsSetupDone: Boolean
        get() = sp.getBoolean("apps_setup_done", false)
        set(v) = sp.edit().putBoolean("apps_setup_done", v).apply()

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
