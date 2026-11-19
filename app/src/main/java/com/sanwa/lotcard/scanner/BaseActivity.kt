package com.sanwa.lotcard.scanner

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

abstract class BaseActivity : AppCompatActivity() {
    protected val prefs by lazy { Prefs(this) }
    protected val api by lazy { ApiClient(this) }

    /** Halaman berlatar gelap (kamera) memakai footer gelap. */
    protected open val darkFooter: Boolean = false

    /**
     * Semua halaman: isi layout + baris kredit di paling bawah
     * ("Created by Fachru - IT PTS", teksnya di res/values/strings.xml → footer_credit).
     */
    override fun setContentView(layoutResID: Int) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page = layoutInflater.inflate(layoutResID, root, false)
        root.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val density = resources.displayMetrics.density
        root.addView(TextView(this).apply {
            text = getString(R.string.footer_credit)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, (3 * density).toInt(), 0, (4 * density).toInt())
            setTextColor(if (darkFooter) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280"))
            setBackgroundColor(if (darkFooter) Color.BLACK else Color.parseColor("#F3F4F6"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        super.setContentView(root)
    }

    protected fun setupToolbar(title: String, back: Boolean) {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = title
        toolbar.setLogo(R.drawable.logo_badge) // logo perusahaan di bilah judul
        toolbar.titleMarginStart = (12 * resources.displayMetrics.density).toInt()
        supportActionBar?.setDisplayHomeAsUpEnabled(back)
        if (back) toolbar.setNavigationOnClickListener { finish() }
    }

    /** Sertifikat server belum dipercaya → tanya pengguna; kalau setuju, simpan & ulangi. */
    protected fun askTrustCertificate(cert: ApiResult.UntrustedCert, onTrusted: () -> Unit) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Percayai sertifikat server?")
            .setMessage(
                "Server ${prefs.labelFor(cert.baseUrl)} (${cert.baseUrl}) memakai sertifikat yang belum dikenal HP ini " +
                    "(self-signed).\n\nSidik jari SHA-256:\n${cert.fingerprint}\n\nBerlaku sampai: ${cert.validUntil}\n\n" +
                    "Percayai HANYA kalau ini benar server QR-IPCS perusahaan (sidik jari bisa dicocokkan oleh IT)."
            )
            .setPositiveButton("Percayai") { _, _ ->
                prefs.addPin(cert.fingerprint)
                onTrusted()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    protected fun showMessage(title: String, message: String) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    /** Token tidak berlaku lagi → hapus sesi & buka halaman login. */
    protected fun goToLogin(reason: String?) {
        prefs.clearSession()
        val i = Intent(this, LoginActivity::class.java)
        if (!reason.isNullOrBlank()) i.putExtra(LoginActivity.EXTRA_REASON, reason)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        finish()
    }
}
