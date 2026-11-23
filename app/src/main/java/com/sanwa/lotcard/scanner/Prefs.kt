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

    /** Fitur dari server (izin role): Lihat Data Plan / Input Perolehan. */
    var canView: Boolean
        get() = sp.getBoolean(KEY_CAN_VIEW, true)
        set(v) = sp.edit().putBoolean(KEY_CAN_VIEW, v).apply()

    var canInput: Boolean
        get() = sp.getBoolean(KEY_CAN_INPUT, false)
        set(v) = sp.edit().putBoolean(KEY_CAN_INPUT, v).apply()

    /** Input Manual Assembly/Inspection (metode 1 lembar). */
    var canManual: Boolean
        get() = sp.getBoolean(KEY_CAN_MANUAL, false)
        set(v) = sp.edit().putBoolean(KEY_CAN_MANUAL, v).apply()

    /** Cetak QR (printer thermal Bluetooth) — dari server, default ya. */
    var canPrint: Boolean
        get() = sp.getBoolean(KEY_CAN_PRINT, true)
        set(v) = sp.edit().putBoolean(KEY_CAN_PRINT, v).apply()

    // ── Printer label Bluetooth (Iware MP-581RL / ESC/POS 58 mm) ─────────
    /** Alamat MAC printer Bluetooth yang dipilih (sudah di-pair di Android). */
    var printerAddress: String
        get() = sp.getString(KEY_PRINTER_ADDR, "") ?: ""
        set(v) = sp.edit().putString(KEY_PRINTER_ADDR, v).apply()

    var printerName: String
        get() = sp.getString(KEY_PRINTER_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_PRINTER_NAME, v).apply()

    /** Jarak tepi kiri area cetak ke label (mm). Default 18 = label 30 mm rata kanan area cetak 48 mm (sama dengan cetak RawBT dari web). */
    var printerOffsetMm: Float
        get() = sp.getFloat(KEY_PRINTER_OFFSET, 18f)
        set(v) = sp.edit().putFloat(KEY_PRINTER_OFFSET, v).apply()

    /** Geser cetakan ke bawah (mm) di awal label — untuk kalibrasi posisi vertikal. */
    var printerTopMm: Float
        get() = sp.getFloat(KEY_PRINTER_TOP, 0f)
        set(v) = sp.edit().putFloat(KEY_PRINTER_TOP, v).apply()

    /** Jarak antar label (gap) dalam mm — dimajukan setelah tiap label. */
    var printerGapMm: Float
        get() = sp.getFloat(KEY_PRINTER_GAP, 2f)
        set(v) = sp.edit().putFloat(KEY_PRINTER_GAP, v).apply()

    /** true = kirim perintah "cari label berikutnya" (GS FF) setelah cetak, bukan maju sejauh gap. */
    var printerUseGsFf: Boolean
        get() = sp.getBoolean(KEY_PRINTER_GSFF, false)
        set(v) = sp.edit().putBoolean(KEY_PRINTER_GSFF, v).apply()

    /** Simpan data user dari jawaban login / me. */
    fun saveUser(user: org.json.JSONObject?, fallbackNik: String = "") {
        if (user == null) return
        userNik = user.optString("nik").ifBlank { fallbackNik.ifBlank { userNik } }
        userName = user.optString("name", userName)
        val f = user.optJSONObject("features")
        canView = f?.optBoolean("view", true) ?: true
        canInput = f?.optBoolean("input", false) ?: false
        canManual = f?.optBoolean("manual", false) ?: false
        canPrint = f?.optBoolean("print", true) ?: true
    }

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
        private const val KEY_CAN_VIEW = "can_view"
        private const val KEY_CAN_INPUT = "can_input"
        private const val KEY_CAN_MANUAL = "can_manual"
        private const val KEY_CAN_PRINT = "can_print"
        private const val KEY_PRINTER_ADDR = "printer_addr"
        private const val KEY_PRINTER_NAME = "printer_name"
        private const val KEY_PRINTER_OFFSET = "printer_offset_mm"
        private const val KEY_PRINTER_TOP = "printer_top_mm"
        private const val KEY_PRINTER_GAP = "printer_gap_mm"
        private const val KEY_PRINTER_GSFF = "printer_gsff"
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
