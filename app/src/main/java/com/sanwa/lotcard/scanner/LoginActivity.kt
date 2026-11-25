package com.sanwa.lotcard.scanner

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.json.JSONObject

class LoginActivity : BaseActivity() {
    override val sessionGuard = false

    private lateinit var nik: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var errorText: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var serverInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        setupToolbar("Login", back = false)

        nik = findViewById(R.id.nikInput)
        password = findViewById(R.id.passwordInput)
        errorText = findViewById(R.id.errorText)
        btnLogin = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progress)
        serverInfo = findViewById(R.id.serverInfo)

        if (prefs.userNik.isNotEmpty()) nik.setText(prefs.userNik)
        intent.getStringExtra(EXTRA_REASON)?.let { showError(it) }

        btnLogin.setOnClickListener { doLogin() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false
        }
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Pertama kali dipakai: isi alamat server dulu.
        if (!prefs.settingsSaved && savedInstanceState == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val pub = prefs.publicUrl.ifBlank { "(belum diisi)" }
        serverInfo.text = "Server lokal: ${prefs.localUrl}\nServer publik: $pub"
    }

    private fun showError(msg: String?) {
        errorText.text = msg ?: ""
        errorText.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        btnLogin.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun doLogin() {
        val n = nik.text?.toString()?.trim().orEmpty()
        val p = password.text?.toString().orEmpty()
        if (n.isEmpty() || p.isEmpty()) {
            showError("NIK dan password wajib diisi.")
            return
        }
        showError(null)
        setBusy(true)
        val body = JSONObject()
            .put("nik", n)
            .put("password", p)
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        lifecycleScope.launch {
            val r = api.call("POST", "/api/mobile/login", body, withToken = false)
            setBusy(false)
            when (r) {
                is ApiResult.Response -> {
                    if (r.ok && r.json.optString("token").isNotBlank()) {
                        prefs.token = r.json.getString("token")
                        prefs.lastActiveAt = System.currentTimeMillis() // mulai hitung sesi
                        prefs.backgroundAt = 0L
                        prefs.saveUser(r.json.optJSONObject("user"), n)
                        password.setText("")
                        val i = Intent(this@LoginActivity, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(i)
                        finish()
                    } else {
                        showError(r.message)
                    }
                }
                is ApiResult.UntrustedCert -> askTrustCertificate(r) { doLogin() }
                is ApiResult.NetworkError -> showError(r.message)
            }
        }
    }

    companion object {
        const val EXTRA_REASON = "reason"
    }
}
