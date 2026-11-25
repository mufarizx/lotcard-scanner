package com.sanwa.lotcard.scanner

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/** Alamat server lokal & publik + tes koneksi + sertifikat yang dipercaya. */
class SettingsActivity : BaseActivity() {
    override val sessionGuard = false

    private lateinit var localUrl: TextInputEditText
    private lateinit var publicUrl: TextInputEditText
    private lateinit var testResult: TextView
    private lateinit var pinInfo: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnTest: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupToolbar("Pengaturan server", back = true)

        localUrl = findViewById(R.id.localUrl)
        publicUrl = findViewById(R.id.publicUrl)
        testResult = findViewById(R.id.testResult)
        pinInfo = findViewById(R.id.pinInfo)
        progress = findViewById(R.id.progress)
        btnTest = findViewById(R.id.btnTest)

        localUrl.setText(prefs.localUrl)
        publicUrl.setText(prefs.publicUrl)
        findViewById<TextView>(R.id.versionInfo).text = "Versi aplikasi: " + versionName()

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            if (save()) {
                Toast.makeText(this, "Pengaturan disimpan.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        btnTest.setOnClickListener { if (save()) testAll() }
        findViewById<MaterialButton>(R.id.btnClearPins).setOnClickListener {
            prefs.pinnedFingerprints = emptySet()
            showPins()
            Toast.makeText(this, "Daftar trusted certificate dikosongkan.", Toast.LENGTH_SHORT).show()
        }
        showPins()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (e: Exception) {
        "-"
    }

    private fun save(): Boolean {
        val local = Prefs.normalizeUrl(localUrl.text?.toString().orEmpty())
        val pub = Prefs.normalizeUrl(publicUrl.text?.toString().orEmpty())
        if (local.isEmpty() && pub.isEmpty()) {
            showMessage("Pengaturan", "Isi minimal satu alamat server.")
            return false
        }
        prefs.localUrl = local
        prefs.publicUrl = pub
        prefs.lastGoodUrl = null
        prefs.settingsSaved = true
        localUrl.setText(local)
        publicUrl.setText(pub)
        return true
    }

    private fun showPins() {
        val pins = prefs.pinnedFingerprints
        pinInfo.text = if (pins.isEmpty()) {
            "Sertifikat yang dipercaya: belum ada."
        } else {
            "Sertifikat yang dipercaya (SHA-256):\n" + pins.joinToString("\n\n")
        }
    }

    /** Tes tiap alamat satu per satu (tidak memakai urutan otomatis). */
    private fun testAll() {
        val targets = listOf("Lokal" to prefs.localUrl, "Publik" to prefs.publicUrl).filter { it.second.isNotBlank() }
        btnTest.isEnabled = false
        progress.visibility = View.VISIBLE
        testResult.text = ""
        lifecycleScope.launch {
            val lines = mutableListOf<String>()
            for ((label, url) in targets) {
                var r = api.call("GET", "/api/mobile/ping", withToken = false, onlyBaseUrl = url)
                if (r is ApiResult.UntrustedCert) {
                    val trusted = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    val cert = r
                    runOnUiThread {
                        askTrustCertificateOrCancel(cert, trusted)
                    }
                    if (trusted.await()) {
                        r = api.call("GET", "/api/mobile/ping", withToken = false, onlyBaseUrl = url)
                    }
                }
                lines += when (r) {
                    is ApiResult.Response ->
                        if (r.ok && r.json.optString("app") == "lot-card") "✓ $label ($url): tersambung ke server produksi."
                        else "✗ $label ($url): server menjawab, tapi bukan server produksi Sanwa (kode ${r.code}). Periksa alamat/port."
                    is ApiResult.UntrustedCert -> "✗ $label ($url): sertifikat belum dipercaya."
                    is ApiResult.NetworkError -> "✗ $label ($url): tidak tersambung."
                }
                testResult.text = lines.joinToString("\n\n")
            }
            if (targets.isNotEmpty()) prefs.lastGoodUrl = null
            showPins()
            progress.visibility = View.GONE
            btnTest.isEnabled = true
        }
    }

    private fun askTrustCertificateOrCancel(cert: ApiResult.UntrustedCert, result: kotlinx.coroutines.CompletableDeferred<Boolean>) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Percayai sertifikat server?")
            .setMessage(
                "Server ${cert.baseUrl} memakai sertifikat yang belum dikenal HP ini (self-signed).\n\n" +
                    "Sidik jari SHA-256:\n${cert.fingerprint}\n\nBerlaku sampai: ${cert.validUntil}\n\n" +
                    "Percayai HANYA kalau ini benar server produksi perusahaan."
            )
            .setPositiveButton("Percayai") { _, _ ->
                prefs.addPin(cert.fingerprint)
                result.complete(true)
            }
            .setNegativeButton("Batal") { _, _ -> result.complete(false) }
            .setOnCancelListener { result.complete(false) }
            .show()
    }
}
