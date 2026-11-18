package com.sanwa.lotcard.scanner

import android.content.Context
import android.content.SharedPreferences

/** Pengaturan & sesi login yang disimpan di HP. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("lotcard_scanner", Context.MODE_PRIVATE)

    var localUrl: String
        get() = sp.getString(KEY_LOCAL, DEFAULT_LOCAL) ?: DEFAULT_LOCAL
        set(v) = sp.edit().putString(KEY_LOCAL, normalizeUrl(v)).apply()

    var publicUrl: String
        get() = sp.getString(KEY_PUBLIC, "") ?: ""
        set(v) = sp.edit().putString(KEY_PUBLIC, normalizeUrl(v)).apply()

    var lastGoodUrl: String?
        get() = sp.getString(KEY_LAST_GOOD, null)
        set(v) = sp.edit().putString(KEY_LAST_GOOD, v).apply()

    var token: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    var userName: String
        get() = sp.getString(KEY_USER_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER_NAME, v).apply()

    var userNik: String
        get() = sp.getString(KEY_USER_NIK, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER_NIK, v).apply()

    var settingsSaved: Boolean
        get() = sp.getBoolean(KEY_SETTINGS_SAVED, false)
        set(v) = sp.edit().putBoolean(KEY_SETTINGS_SAVED, v).apply()

    /** Sidik jari SHA-256 sertifikat server yang sudah disetujui pengguna. */
    var pinnedFingerprints: Set<String>
        get() = HashSet(sp.getStringSet(KEY_PINS, emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet(KEY_PINS, HashSet(v)).apply()

    fun addPin(fingerprint: String) {
        pinnedFingerprints = pinnedFingerprints + fingerprint
    }

    /** Urutan alamat yang dicoba: yang terakhir berhasil dulu, lalu lokal, lalu publik. */
    fun baseUrls(): List<String> {
        val all = listOf(localUrl, publicUrl).map { normalizeUrl(it) }.filter { it.isNotEmpty() }.distinct()
        val last = lastGoodUrl
        return if (last != null && last in all) listOf(last) + all.filter { it != last } else all
    }

    fun labelFor(baseUrl: String): String = when (baseUrl) {
        normalizeUrl(localUrl) -> "Lokal"
        normalizeUrl(publicUrl) -> "Publik"
        else -> baseUrl
    }

    fun clearSession() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_USER_NAME).remove(KEY_USER_NIK).apply()
    }

    companion object {
        const val DEFAULT_LOCAL = "https://200.200.64.4:2443"
        private const val KEY_LOCAL = "local_url"
        private const val KEY_PUBLIC = "public_url"
        private const val KEY_LAST_GOOD = "last_good_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_NIK = "user_nik"
        private const val KEY_SETTINGS_SAVED = "settings_saved"
        private const val KEY_PINS = "pinned_fingerprints"

        /** "200.200.64.4:2443/" → "https://200.200.64.4:2443" */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return ""
            if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
                u = "https://$u"
            }
            return u.trimEnd('/')
        }
    }
}
