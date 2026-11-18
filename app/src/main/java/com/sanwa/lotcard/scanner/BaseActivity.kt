package com.sanwa.lotcard.scanner

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

abstract class BaseActivity : AppCompatActivity() {
    protected val prefs by lazy { Prefs(this) }
    protected val api by lazy { ApiClient(this) }

    protected fun setupToolbar(title: String, back: Boolean) {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = title
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
                    "Percayai HANYA kalau ini benar server Lot-Card perusahaan (sidik jari bisa dicocokkan oleh IT)."
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
