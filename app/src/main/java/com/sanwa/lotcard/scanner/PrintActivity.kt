package com.sanwa.lotcard.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * CETAK QR (23 Nov 2026): scan QR Plan → kartu QR tiap stage (sama dengan
 * "Print Card" di web, label 30 × 20 mm) → cetak ke printer thermal
 * Bluetooth (Iware MP-581RL / ESC/POS 58 mm). Data: GET /api/mobile/label/{kode}.
 */
class PrintActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var btnPrinter: MaterialButton
    private var code: String = ""
    private var pendingAfterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_print)
        code = intent.getStringExtra(PlanActivity.EXTRA_CODE).orEmpty()
        setupToolbar("Cetak QR — $code", back = true)
        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        btnPrinter = findViewById(R.id.btnPrinter)
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener { load() }
        btnPrinter.setOnClickListener { withBluetooth { choosePrinter(null) } }
        findViewById<MaterialButton>(R.id.btnPrinterSettings).setOnClickListener { printerSettings() }
        refreshPrinterButton()
        load()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refreshPrinterButton() {
        btnPrinter.text = if (prefs.printerAddress.isBlank()) "Pilih printer" else "Printer: " + prefs.printerName.ifBlank { prefs.printerAddress }
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        content.removeAllViews()
        lifecycleScope.launch {
            val r = api.call("GET", "/api/mobile/label/" + Uri.encode(code))
            progress.visibility = View.GONE
            when (r) {
                is ApiResult.Response -> when {
                    r.ok -> render(r.json)
                    r.code == 401 -> goToLogin(r.message)
                    else -> showError(r.message)
                }
                is ApiResult.UntrustedCert -> {
                    showError("Sertifikat server belum dipercaya.")
                    askTrustCertificate(r) { load() }
                }
                is ApiResult.NetworkError -> showError(r.message)
            }
        }
    }

    private fun showError(msg: String) {
        content.removeAllViews()
        errorText.text = msg
        errorBox.visibility = View.VISIBLE
    }

    private fun label(s: String, size: Float, color: Int = Color.parseColor("#111827"), bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun render(json: JSONObject) {
        content.removeAllViews()
        val plan = json.optJSONObject("plan") ?: JSONObject()
        supportActionBar?.title = "Cetak QR — " + plan.optString("lot_id", code)
        content.addView(label(plan.optString("lot_id", code), 22f, Color.parseColor("#1A6FC4"), true))
        content.addView(label(plan.optString("product", "—"), 16f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })

        val stages = json.optJSONArray("stages") ?: JSONArray()
        if (stages.length() == 0) showError("Plan ini tidak punya stage untuk dicetak.")
        for (i in 0 until stages.length()) {
            val st = stages.optJSONObject(i) ?: continue
            val card = MaterialCardView(this).apply {
                radius = dp(10).toFloat()
                cardElevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(box)
            content.addView(card)
            box.addView(label(st.optString("label"), 16f, bold = true))

            val data = st.optJSONObject("card")
            if (!st.optBoolean("available") || data == null) {
                box.addView(label(st.optString("reason", "QR belum tersedia."), 13f, Color.parseColor("#6B7280")))
                continue
            }
            val bmp = LabelRenderer.renderCard(data)
            // Pratinjau (diperbesar tanpa dihaluskan supaya mirip hasil printer).
            val preview = ImageView(this).apply {
                setImageBitmap(Bitmap.createScaledBitmap(bmp, bmp.width * 2, bmp.height * 2, false))
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            box.addView(preview)

            val copies = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText("1")
                setSingleLine()
                hint = "Jumlah"
            }
            val print = MaterialButton(this).apply { text = "Cetak"; isAllCaps = false }
            print.setOnClickListener {
                val n = copies.text.toString().trim().toIntOrNull() ?: 1
                if (n < 1 || n > 50) {
                    showMessage("Jumlah", "Jumlah cetak 1 sampai 50.")
                    return@setOnClickListener
                }
                printBitmap(bmp, n)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(label("Jumlah:", 14f).apply { setPadding(0, 0, dp(8), 0) })
            row.addView(copies, LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(print, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            box.addView(row)
        }
    }

    // ── Bluetooth ─────────────────────────────────────────────────────
    /** Jalankan [action] setelah izin Bluetooth (Android 12+) diberikan. */
    private fun withBluetooth(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = action
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        val ad = BluetoothPrinter.adapter(this)
        if (ad == null) {
            showMessage("Bluetooth", "HP ini tidak punya Bluetooth.")
            return
        }
        if (!ad.isEnabled) {
            showMessage("Bluetooth mati", "Nyalakan Bluetooth HP terlebih dahulu, lalu coba lagi.")
            return
        }
        action()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            action?.let { withBluetooth(it) }
        } else {
            showMessage("Izin Bluetooth", "Aplikasi perlu izin \"Perangkat di sekitar\" untuk tersambung ke printer. Izinkan di Pengaturan HP → Aplikasi → Sanwa Production App → Izin.")
        }
    }

    private fun choosePrinter(then: (() -> Unit)?) {
        val devices = try { BluetoothPrinter.pairedDevices(this) } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            showMessage("Belum ada printer",
                "Belum ada perangkat Bluetooth yang di-pair.\n\n1. Nyalakan printer Iware MP-581RL.\n2. Buka Pengaturan HP → Bluetooth → pair printer (PIN biasanya 0000 atau 1234).\n3. Kembali ke sini dan pilih printernya.")
            return
        }
        val opts = devices.map { d ->
            val name = try { d.name } catch (e: SecurityException) { null } ?: d.address
            PickOption(d.address, "$name  (${d.address})")
        }
        PickerDialog.show(this, "Pilih printer (sudah di-pair)", opts) { p ->
            prefs.printerAddress = p.value
            prefs.printerName = p.label.substringBefore("  (")
            refreshPrinterButton()
            then?.invoke()
        }
    }

    private fun printBitmap(bmp: Bitmap, copies: Int): Unit = withBluetooth {
        if (prefs.printerAddress.isBlank()) {
            choosePrinter { printBitmap(bmp, copies) }
            return@withBluetooth
        }
        val data = if (prefs.printerTspl)
            LabelRenderer.tspl(bmp, prefs.printerOffsetMm, prefs.printerTopMm, prefs.printerGapMm, copies, prefs.printerInvert, prefs.printerRotate)
        else
            LabelRenderer.escPos(bmp, prefs.printerOffsetMm, prefs.printerTopMm, prefs.printerGapMm, prefs.printerUseGsFf, copies)
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val err = BluetoothPrinter.send(this@PrintActivity, prefs.printerAddress, data)
            progress.visibility = View.GONE
            if (err != null) showMessage("Gagal mencetak", err)
            else showMessage("Terkirim", "$copies label dikirim ke printer ${prefs.printerName}.")
        }
    }

    // ── Pengaturan printer (kalibrasi posisi label) ───────────────────
    private fun printerSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun num(labelText: String, value: Float): EditText {
            box.addView(label(labelText, 13f, Color.parseColor("#374151")).apply { setPadding(0, dp(8), 0, 0) })
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(fmt(value))
                setSingleLine()
            }.also { box.addView(it) }
        }
        box.addView(label("Label 30 × 20 mm, printer 58 mm (area cetak 48 mm).", 12f, Color.parseColor("#6B7280")))
        val tspl = CheckBox(this).apply {
            text = "Mode label TSPL (sama dengan RawBT \"TSPL\") — matikan hanya untuk printer mode struk (ESC/POS)"
            isChecked = prefs.printerTspl
        }
        box.addView(tspl)
        val offset = num("Geser ke kanan dari tepi kiri area cetak (mm, 0–18)", prefs.printerOffsetMm)
        val top = num("Geser ke bawah di awal label (mm, 0–10)", prefs.printerTopMm)
        val gap = num("Jarak antar label / gap (mm)", prefs.printerGapMm)
        val gsff = CheckBox(this).apply {
            text = "ESC/POS: cari awal label berikutnya (GS FF) — coba bila posisi label bergeser terus"
            isChecked = prefs.printerUseGsFf
        }
        val invert = CheckBox(this).apply {
            text = "TSPL: balik warna (centang bila label tercetak hitam penuh / warnanya terbalik)"
            isChecked = prefs.printerInvert
        }
        val rotate = CheckBox(this).apply {
            text = "TSPL: putar 180° (bila hasil terbalik)"
            isChecked = prefs.printerRotate
        }
        box.addView(invert)
        box.addView(rotate)
        box.addView(gsff)

        fun saveFields(): Boolean {
            val o = offset.text.toString().replace(',', '.').toFloatOrNull()
            val t = top.text.toString().replace(',', '.').toFloatOrNull()
            val g = gap.text.toString().replace(',', '.').toFloatOrNull()
            if (o == null || o < 0f || o > 18f || t == null || t < 0f || t > 10f || g == null || g < 0f || g > 50f) {
                showMessage("Pengaturan printer", "Isi angka: geser kanan 0–18 mm, geser bawah 0–10 mm, gap 0–50 mm.")
                return false
            }
            prefs.printerOffsetMm = o
            prefs.printerTopMm = t
            prefs.printerGapMm = g
            prefs.printerUseGsFf = gsff.isChecked
            prefs.printerTspl = tspl.isChecked
            prefs.printerInvert = invert.isChecked
            prefs.printerRotate = rotate.isChecked
            return true
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Atur printer")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Simpan") { _, _ -> saveFields() }
            .setNeutralButton("Tes cetak") { _, _ -> if (saveFields()) printBitmap(LabelRenderer.renderTestCard(), 1) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v).removeSuffix(".0")

    companion object {
        private const val REQ_BT = 41
    }
}
