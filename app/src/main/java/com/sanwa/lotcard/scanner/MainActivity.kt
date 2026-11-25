package com.sanwa.lotcard.scanner

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Halaman utama: pilih fitur Lihat Data Plan / Input Perolehan / Input Manual (sesuai izin akun). */
class MainActivity : BaseActivity() {
    private lateinit var welcome: TextView
    private lateinit var cardView: MaterialCardView
    private lateinit var cardInput: MaterialCardView
    private lateinit var cardManual: MaterialCardView
    private lateinit var cardPrint: MaterialCardView
    private lateinit var cardDashboard: MaterialCardView
    private lateinit var noFeature: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (prefs.token.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        setupToolbar(getString(R.string.app_name), back = false)

        welcome = findViewById(R.id.welcome)
        cardView = findViewById(R.id.cardView)
        cardInput = findViewById(R.id.cardInput)
        cardManual = findViewById(R.id.cardManual)
        cardPrint = findViewById(R.id.cardPrint)
        cardDashboard = findViewById(R.id.cardDashboard)
        cardDashboard.setOnClickListener { startActivity(Intent(this, DashboardActivity::class.java)) }
        noFeature = findViewById(R.id.noFeature)

        cardView.setOnClickListener { openScanner(ScanActivity.MODE_VIEW) }
        cardInput.setOnClickListener { openScanner(ScanActivity.MODE_INPUT) }
        cardManual.setOnClickListener { openScanner(ScanActivity.MODE_MANUAL) }
        cardPrint.setOnClickListener { openScanner(ScanActivity.MODE_PRINT) }
        refreshMenu()
        verifySession()
    }

    override fun onResume() {
        super.onResume()
        if (::welcome.isInitialized) refreshMenu()
    }

    private fun refreshMenu() {
        welcome.text = "Halo, ${prefs.userName}\nNIK ${prefs.userNik}"
        cardView.visibility = if (prefs.canView) View.VISIBLE else View.GONE
        cardInput.visibility = if (prefs.canInput) View.VISIBLE else View.GONE
        cardManual.visibility = if (prefs.canManual) View.VISIBLE else View.GONE
        cardPrint.visibility = if (prefs.canPrint) View.VISIBLE else View.GONE
        cardDashboard.visibility = if (prefs.canDashboard) View.VISIBLE else View.GONE
        noFeature.visibility = if (!prefs.canView && !prefs.canInput && !prefs.canManual && !prefs.canDashboard) View.VISIBLE else View.GONE
    }

    private fun openScanner(mode: String) {
        startActivity(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, mode))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        R.id.menu_logout -> {
            confirmLogout(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** Cek diam-diam: login masih berlaku? fitur (izin) berubah? */
    private fun verifySession() {
        lifecycleScope.launch {
            val r = api.call("GET", "/api/mobile/me")
            if (r is ApiResult.Response) {
                if (r.code == 401) {
                    goToLogin(r.message)
                } else if (r.ok) {
                    prefs.saveUser(r.json.optJSONObject("user"))
                    refreshMenu()
                }
            }
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Keluar?")
            .setMessage("Anda harus login lagi untuk memakai aplikasi ini.")
            .setPositiveButton("Keluar") { _, _ ->
                lifecycleScope.launch {
                    api.call("POST", "/api/mobile/logout") // abaikan kalau server tidak terjangkau
                    Toast.makeText(this@MainActivity, "Sudah keluar.", Toast.LENGTH_SHORT).show()
                    goToLogin(null)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
