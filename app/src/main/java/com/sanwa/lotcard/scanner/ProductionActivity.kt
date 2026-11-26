package com.sanwa.lotcard.scanner

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Menu PRODUCTION (26 Nov 2026) — tabel Production Plan web, hanya lihat.
 * Data: GET /api/mobile/plans (filter, urutan, 50 per halaman & ringkasan
 * Lot Code sama dengan web). Ketuk Plan → Data Plan (PlanActivity).
 */
class ProductionActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var scroll: ScrollView

    // Filter (tetap selama halaman terbuka).
    private var fLotId = ""
    private var fProduct = ""
    private var fLotCode = ""
    private var fStatus = ""        // "" | pending | draft | winding | assembly | inspection | done
    private var fMonth = ""         // "" | 1..12
    private var fYear = ""          // "" = tahun berjalan (sama dengan web)

    private var page = 0
    private var lastPage = 1
    private var total = 0
    private var statuses = JSONArray()
    private var years = JSONArray()
    private var year = 0

    private var listBox: LinearLayout? = null
    private var moreBtn: MaterialButton? = null
    private var totalLabel: TextView? = null
    private var loading = false

    private val textMain = Color.parseColor("#111827")
    private val textSm = Color.parseColor("#6B7280")
    private val blue = Color.parseColor("#1A6FC4")
    private val months = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        setupToolbar("Production", back = true)
        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        scroll = findViewById(R.id.scroll)
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener { load(reset = true) }
        load(reset = true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.plan_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_reload) { load(reset = true); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Ambil data ────────────────────────────────────────────────────
    private fun load(reset: Boolean) {
        if (loading) return
        loading = true
        val nextPage = if (reset) 1 else page + 1
        if (reset) { progress.visibility = View.VISIBLE; errorBox.visibility = View.GONE }
        moreBtn?.apply { isEnabled = false; text = "Memuat…" }
        val q = StringBuilder("/api/mobile/plans?page=$nextPage")
        fun add(k: String, v: String) { if (v.isNotBlank()) q.append('&').append(k).append('=').append(android.net.Uri.encode(v)) }
        add("f_lot_id", fLotId); add("f_product", fProduct); add("f_lot_code", fLotCode)
        if (fStatus == "pending") add("status", "pending") else add("f_status", fStatus)
        add("f_month", fMonth); add("f_year", fYear)
        lifecycleScope.launch {
            val r = api.call("GET", q.toString())
            loading = false
            progress.visibility = View.GONE
            when (r) {
                is ApiResult.Response -> when {
                    r.ok -> if (reset) render(r.json) else append(r.json)
                    r.code == 401 -> goToLogin(r.message)
                    else -> showError(r.message, reset)
                }
                is ApiResult.UntrustedCert -> askTrustCertificate(r) { load(reset) }
                is ApiResult.NetworkError -> showError(r.message, reset)
            }
        }
    }

    private fun showError(msg: String, reset: Boolean) {
        updateMore()
        if (!reset || listBox != null) { showMessage("Belum berhasil", msg); return }
        content.removeAllViews()
        errorText.text = msg
        errorBox.visibility = View.VISIBLE
    }

    // ── Gambar ────────────────────────────────────────────────────────
    private fun render(j: JSONObject) {
        content.removeAllViews()
        statuses = j.optJSONArray("statuses") ?: JSONArray()
        years = j.optJSONArray("years") ?: JSONArray()
        year = j.optInt("year")
        renderFilters()

        j.optJSONObject("lot_summary")?.let { renderLotSummary(it) }

        totalLabel = label("", 12f, textSm, true).apply { setPadding(dp(2), dp(2), 0, dp(6)) }
        content.addView(totalLabel)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)
        moreBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false
            setOnClickListener { load(reset = false) }
        }
        content.addView(moreBtn)
        page = 0
        append(j)
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun append(j: JSONObject) {
        page = j.optInt("page", page + 1)
        lastPage = j.optInt("last_page", 1)
        total = j.optInt("total")
        val box = listBox ?: return
        val plans = j.optJSONArray("plans") ?: JSONArray()
        if (page == 1 && plans.length() == 0) {
            box.addView(label("Tidak ada Plan yang cocok dengan filter.", 13f, textSm).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24))
            })
        }
        for (i in 0 until plans.length()) plans.optJSONObject(i)?.let { box.addView(planCard(it)) }
        totalLabel?.text = "$total plans" + if (total > 0) " · tampil ${box.childCount.coerceAtMost(total)}" else ""
        updateMore()
    }

    private fun updateMore() {
        val b = moreBtn ?: return
        b.isEnabled = true
        val shown = listBox?.childCount ?: 0
        b.visibility = if (page < lastPage) View.VISIBLE else View.GONE
        b.text = "Muat lagi (${shown} dari ${total})"
    }

    private fun renderFilters() {
        val box = card()
        val lot = input("No. IPCS", fLotId, InputType.TYPE_CLASS_NUMBER)
        val lotCode = input("Lot Code", fLotCode, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).apply { // huruf besar/kecil apa adanya
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
        }
        val product = input("Produk (nama / kode)", fProduct, InputType.TYPE_CLASS_TEXT)
        box.addView(row(weighted(lot, 1.2f, dp(6)), weighted(lotCode, 1f)))
        box.addView(product)
        box.addView(label("Lot Code: sama persis, huruf besar/kecil dibedakan (s ≠ S).", 11f, textSm).apply { setPadding(dp(2), dp(2), 0, dp(4)) })

        val statusBtn = outlined(statusLabel())
        statusBtn.setOnClickListener {
            val opts = (0 until statuses.length()).mapNotNull { statuses.optJSONObject(it) }.map { PickOption(it.optString("value"), it.optString("label")) }
            PickerDialog.show(this, "Status", opts) { o -> fStatus = o.value; statusBtn.text = statusLabel() }
        }
        val monthBtn = outlined(if (fMonth.isBlank()) "Semua Bulan" else months[fMonth.toInt() - 1])
        monthBtn.setOnClickListener {
            val opts = listOf(PickOption("", "Semua Bulan")) + months.mapIndexed { i, m -> PickOption((i + 1).toString(), m) }
            PickerDialog.show(this, "Bulan", opts) { o -> fMonth = o.value; monthBtn.text = if (o.value.isBlank()) "Semua Bulan" else o.label }
        }
        val yearBtn = outlined((fYear.ifBlank { year.toString() }))
        yearBtn.setOnClickListener {
            val opts = (0 until years.length()).map { years.optInt(it).toString() }.map { PickOption(it, it) }
            PickerDialog.show(this, "Tahun", opts) { o -> fYear = o.value; yearBtn.text = o.label }
        }
        box.addView(statusBtn)
        box.addView(row(weighted(monthBtn, 1.4f, dp(6)), weighted(yearBtn, 1f)))

        val search = MaterialButton(this).apply { text = "Cari"; isAllCaps = false }
        val reset = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { text = "Reset"; isAllCaps = false }
        fun apply() {
            fLotId = lot.text.toString().trim()
            fLotCode = lotCode.text.toString().trim()
            fProduct = product.text.toString().trim()
            hideKeyboard()
            load(reset = true)
        }
        search.setOnClickListener { apply() }
        reset.setOnClickListener {
            fLotId = ""; fProduct = ""; fLotCode = ""; fStatus = ""; fMonth = ""; fYear = ""
            hideKeyboard()
            load(reset = true)
        }
        for (e in listOf(lot, lotCode, product)) {
            e.setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { apply(); true } else false
            }
        }
        box.addView(row(weighted(reset, 1f, dp(6)), weighted(search, 2f)).apply { setPadding(0, dp(4), 0, 0) })
    }

    private fun statusLabel(): String {
        for (i in 0 until statuses.length()) {
            val s = statuses.optJSONObject(i) ?: continue
            if (s.optString("value") == fStatus) return "Status: " + s.optString("label")
        }
        return "Status: Semua Status"
    }

    private fun renderLotSummary(s: JSONObject) {
        val box = card(Color.parseColor("#FFFBEB"))
        val amber = Color.parseColor("#92400E")
        box.addView(label("Lot Code " + s.optString("code"), 14f, amber, true))
        box.addView(label("QTY Plan : ${fmt(s.optInt("plans"))}   |   Qty : ${fmt(s.optInt("qty"))} pcs", 17f, amber, true).apply { setPadding(0, dp(4), 0, 0) })
        val plans = s.optInt("plans"); val done = s.optInt("done")
        if (done < plans) {
            box.addView(label("Qty = OK final dari ${fmt(done)} Plan yang sudah Done; ${fmt(plans - done)} Plan masih Inspection.", 12f, amber).apply { setPadding(0, dp(4), 0, 0) })
        }
    }

    private fun planCard(p: JSONObject): View {
        val c = MaterialCardView(this).apply {
            radius = dp(10).toFloat(); cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE); strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@ProductionActivity, PlanActivity::class.java).putExtra(PlanActivity.EXTRA_CODE, p.optString("lot_id")))
            }
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        c.addView(inner)
        inner.addView(row(
            weighted(label(p.optString("lot_id"), 16f, blue, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }, 1f),
            badge(p.optString("status_label"), p.optString("status_color")),
        ))
        inner.addView(label(p.optString("product_name"), 14f, textMain, true).apply { setPadding(0, dp(2), 0, 0) })
        if (p.optString("product_code").isNotBlank()) inner.addView(label(p.optString("product_code"), 11f, textSm).apply { typeface = Typeface.MONOSPACE })
        val facts = mutableListOf("Qty ${fmt(p.optInt("qty_plan"))}", p.optString("plan_date"))
        if (!p.isNull("winding_target") && p.optString("winding_target").isNotBlank()) facts += p.optString("winding_target")
        val codes = p.optJSONArray("lot_codes") ?: JSONArray()
        val factRow = row(weighted(label(facts.joinToString("  ·  "), 13f, textMain), 1f))
        if (codes.length() > 0) {
            factRow.addView(label("Lot " + (0 until codes.length()).joinToString(", ") { codes.optString(it) }, 12f, Color.parseColor("#92400E"), true).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = rounded(Color.parseColor("#FEF3C7"), dp(10).toFloat())
            })
        }
        inner.addView(factRow.apply { setPadding(0, dp(6), 0, 0) })
        val machines = p.optJSONArray("machines") ?: JSONArray()
        if (machines.length() > 0) inner.addView(label("Mesin: " + (0 until machines.length()).joinToString("  ·  ") { machines.optString(it) }, 11f, textSm).apply { setPadding(0, dp(4), 0, 0) })
        val ops = p.optJSONArray("operators") ?: JSONArray()
        if (ops.length() > 0) inner.addView(label("Operator: " + (0 until ops.length()).joinToString("  ·  ") { ops.optString(it) }, 11f, textSm))
        if (!p.isNull("created_by")) inner.addView(label("Dibuat: " + p.optString("created_by"), 11f, textSm))
        return c
    }

    // ── Helper tampilan ───────────────────────────────────────────────
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, sizeSp: Float = 14f, color: Int = textMain, bold: Boolean = false): TextView = TextView(this).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(bg: Int = Color.WHITE): LinearLayout {
        val c = MaterialCardView(this).apply {
            radius = dp(10).toFloat(); cardElevation = dp(1).toFloat(); setCardBackgroundColor(bg); strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        c.addView(inner)
        content.addView(c)
        return inner
    }

    private fun input(hint: String, value: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        textSize = 15f
    }

    private fun outlined(text: String): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text; isAllCaps = false; textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

    private fun badge(text: String, colorName: String): TextView {
        val (bg, fg) = when (colorName) {
            "green" -> "#DCFCE7" to "#166534"
            "blue" -> "#DBEAFE" to "#1E40AF"
            "amber", "yellow", "orange" -> "#FEF3C7" to "#92400E"
            "red" -> "#FEE2E2" to "#991B1B"
            "purple" -> "#EDE9FE" to "#5B21B6"
            else -> "#F3F4F6" to "#374151"
        }
        return label(text, 11f, Color.parseColor(fg), true).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(Color.parseColor(bg), dp(10).toFloat())
        }
    }

    private fun weighted(v: View, weight: Float, endMargin: Int = 0): View = v.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = endMargin }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        views.forEach { addView(it) }
    }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun fmt(n: Int): String = String.format(Locale("in", "ID"), "%,d", n)

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
    }
}
