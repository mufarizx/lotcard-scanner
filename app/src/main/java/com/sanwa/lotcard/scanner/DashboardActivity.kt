package com.sanwa.lotcard.scanner

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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
import java.util.Calendar
import java.util.Locale

/**
 * Menu DASHBOARD (25 Nov 2026) — isi sama dengan Dashboard web Admin
 * (data dari GET /api/mobile/dashboard, lihat MobileDashboardPresenter.php).
 */
class DashboardActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var scroll: ScrollView

    // Filter (tetap selama halaman terbuka).
    private var period = "today"
    private var dateFrom = ""
    private var dateTo = ""
    private var productId: String? = null
    private var chartIndex = 0
    private var data: JSONObject? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val liveDurations = mutableListOf<Pair<TextView, Long>>()
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for ((tv, start) in liveDurations) tv.text = duration(((now - start) / 1000).coerceAtLeast(0))
            ticker.postDelayed(this, 1000)
        }
    }

    private val green = Color.parseColor("#1A7A4A")
    private val red = Color.parseColor("#B91C1C")
    private val amber = Color.parseColor("#D97706")
    private val textMain = Color.parseColor("#111827")
    private val textSm = Color.parseColor("#6B7280")
    private val blue = Color.parseColor("#1A6FC4")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        setupToolbar("Dashboard", back = true)
        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        scroll = findViewById(R.id.scroll)
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.plan_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_reload) { load(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Ambil data ────────────────────────────────────────────────────
    private fun load() {
        progress.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        val q = StringBuilder("/api/mobile/dashboard?period=").append(period)
        if (period == "custom") q.append("&date_from=").append(dateFrom).append("&date_to=").append(dateTo)
        productId?.let { q.append("&product_id=").append(it) }
        lifecycleScope.launch {
            val r = api.call("GET", q.toString())
            progress.visibility = View.GONE
            when (r) {
                is ApiResult.Response -> when {
                    r.ok -> { data = r.json; render(r.json) }
                    r.code == 401 -> goToLogin(r.message)
                    else -> showError(r.message)
                }
                is ApiResult.UntrustedCert -> askTrustCertificate(r) { load() }
                is ApiResult.NetworkError -> showError(r.message)
            }
        }
    }

    private fun showError(msg: String) {
        if (data != null) { showMessage("Belum berhasil", msg); return }
        content.removeAllViews()
        errorText.text = msg
        errorBox.visibility = View.VISIBLE
    }

    // ── Gambar ────────────────────────────────────────────────────────
    private fun render(j: JSONObject) {
        content.removeAllViews()
        liveDurations.clear()
        period = j.optString("period", period)
        dateFrom = j.optString("date_from")
        dateTo = j.optString("date_to")
        productId = if (j.isNull("product_id")) null else j.optString("product_id")

        renderFilters(j)
        renderStats(j.optJSONObject("stats") ?: JSONObject())
        renderStages(j.optJSONArray("stages") ?: JSONArray())
        renderMaintenance(j.optJSONArray("maintenance") ?: JSONArray())
        renderChart(j.optJSONArray("charts") ?: JSONArray())
        renderNg(j.optJSONArray("ng_ranking") ?: JSONArray())
        renderBreakdown(j.optJSONArray("products_breakdown") ?: JSONArray())
        renderOperators(j.optJSONArray("operator_perf") ?: JSONArray())
        renderPlans(j.optJSONArray("recent_plans") ?: JSONArray())
    }

    private fun renderFilters(j: JSONObject) {
        val box = card()
        val periods = j.optJSONObject("periods") ?: JSONObject()
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val keys = listOf("today", "this_week", "this_month", "this_year").filter { periods.has(it) } + "custom"
        for (k in keys) {
            val label = if (k == "custom") "Custom" else periods.optString(k)
            val b = chip(label, k == period)
            b.setOnClickListener {
                if (k == "custom") pickRange() else { period = k; load() }
            }
            chips.addView(b)
        }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        box.addView(label("Periode: " + showDate(dateFrom) + if (dateFrom != dateTo) " – " + showDate(dateTo) else "", 12f, textSm).apply { setPadding(0, dp(6), 0, dp(6)) })

        val products = j.optJSONArray("products") ?: JSONArray()
        val pName = j.optString("product_name").takeIf { !j.isNull("product_name") && it.isNotBlank() }
        val pBtn = outlined(if (pName != null) "Produk: $pName" else "Semua Produk ▾")
        pBtn.setOnClickListener {
            val opts = mutableListOf(PickOption("", "Semua Produk"))
            for (i in 0 until products.length()) {
                val p = products.optJSONObject(i) ?: continue
                opts += PickOption(p.optString("id"), p.optString("name") + "  ·  " + p.optString("code"))
            }
            PickerDialog.show(this, "Filter produk", opts) { o -> filterProduct(o.value.ifBlank { null }) }
        }
        if (pName != null) {
            val clear = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "✕ Semua"; isAllCaps = false; textSize = 13f
            }
            clear.setOnClickListener { filterProduct(null) }
            box.addView(row(weighted(pBtn, 1f, dp(6)), clear))
        } else {
            box.addView(pBtn)
        }
    }

    private fun filterProduct(id: String?) {
        productId = id
        chartIndex = 0
        load()
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun pickRange() {
        val today = Calendar.getInstance()
        fun pick(title: String, cur: String, after: String?, done: (String) -> Unit) {
            val c = Calendar.getInstance()
            if (cur.length == 10) c.set(cur.substring(0, 4).toInt(), cur.substring(5, 7).toInt() - 1, cur.substring(8, 10).toInt())
            val dlg = DatePickerDialog(this, { _, y, m, d -> done(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)) },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            dlg.setTitle(title)
            if (after != null && after.length == 10) {
                val a = Calendar.getInstance()
                a.set(after.substring(0, 4).toInt(), after.substring(5, 7).toInt() - 1, after.substring(8, 10).toInt(), 0, 0, 0)
                dlg.datePicker.minDate = a.timeInMillis
            }
            dlg.show()
        }
        val curFrom = dateFrom.ifBlank { String.format(Locale.US, "%04d-%02d-%02d", today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)) }
        pick("Tanggal awal", curFrom, null) { from ->
            pick("Tanggal akhir", if (dateTo >= from) dateTo else from, from) { to ->
                period = "custom"; dateFrom = from; dateTo = to
                load()
            }
        }
    }

    private fun renderStats(s: JSONObject) {
        val done = s.optInt("done_lots")
        val rate = s.optDouble("defect_rate", 0.0)
        val g1 = row(
            weighted(statTile("Total Target (Qty)", fmt(s.optInt("total_qty_plan")), textMain,
                "${s.optInt("total_plans")} plan · $done selesai", textSm), 1f, dp(8)),
            weighted(statTile("Final OK", if (done > 0) fmt(s.optInt("total_ok")) else "—", green,
                if (done > 0) "pcs approved" else "belum ada plan selesai", textSm), 1f),
        )
        val g2 = row(
            weighted(statTile("Final NG", if (done > 0) fmt(s.optInt("total_ng")) else "—", red,
                if (done > 0) "${num(rate)}% final defect rate" else "belum ada plan selesai", if (rate > 5) red else textSm), 1f, dp(8)),
            weighted(statTile("Final Defect Rate", if (done > 0) "${num(rate)}%" else "—", if (rate > 5) red else green,
                if (done > 0) "dari total produksi" else "belum ada plan selesai", textSm), 1f),
        )
        content.addView(g1)
        content.addView(g2.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
        content.addView(space(10))
    }

    private fun renderStages(stages: JSONArray) {
        content.addView(label("OK / NG PER STAGE", 12f, textSm, true).apply { setPadding(dp(2), 0, 0, dp(6)) })
        for (i in 0 until stages.length()) {
            val s = stages.optJSONObject(i) ?: continue
            val ok = s.optInt("ok"); val ng = s.optInt("ng")
            val box = card()
            box.addView(label(s.optString("label"), 14f, textMain, true))
            box.addView(row(
                label(fmt(ok), 22f, green, true), label("  pcs   |   ", 12f, textSm),
                label(fmt(ng), 22f, red, true), label("  pcs", 12f, textSm),
            ))
            val total = ok + ng
            val barRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
                background = rounded(Color.parseColor("#F3F4F6"), dp(4).toFloat())
                clipToOutline = true
                if (total > 0) {
                    addView(View(context).apply { setBackgroundColor(green); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ok.toFloat()) })
                    addView(View(context).apply { setBackgroundColor(red); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ng.toFloat()) })
                }
            }
            box.addView(row(label(num(s.optDouble("rate", 0.0)) + "%  ", 13f, red, true), barRow).apply { setPadding(0, dp(4), 0, 0) })
        }
    }

    private fun renderMaintenance(list: JSONArray) {
        if (list.length() == 0) return
        val box = card(Color.parseColor("#FFFBEB"))
        box.addView(label("🛠  Ongoing Maintenance (${list.length()})", 15f, Color.parseColor("#92400E"), true))
        for (i in 0 until list.length()) {
            val m = list.optJSONObject(i) ?: continue
            val dur = label("menghitung…", 15f, Color.parseColor("#92600A"), true)
            box.addView(row(
                weighted(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(m.optString("line_name"), 14f, textMain, true))
                    addView(label(m.optString("machine_id"), 11f, textSm).apply { typeface = Typeface.MONOSPACE })
                }, 1f),
                dur,
            ).apply { setPadding(0, dp(8), 0, 0) })
            parseIso(m.optString("started_at"))?.let { liveDurations += dur to it }
        }
    }

    private fun renderChart(charts: JSONArray) {
        if (charts.length() == 0) return
        if (chartIndex >= charts.length()) chartIndex = 0
        val box = card()
        val title = label("", 15f, textMain, true)
        box.addView(title)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(6)) }
        box.addView(legend)
        val chart = ChartView(this)
        chart.minWidthPx = resources.displayMetrics.widthPixels - dp(52)
        val hs = HorizontalScrollView(this).apply { addView(chart) }
        box.addView(hs)
        val note = label("", 11f, textSm)
        box.addView(note)

        fun show(i: Int) {
            chartIndex = i
            val c = charts.optJSONObject(i) ?: return
            title.text = c.optString("title")
            for (k in 0 until tabs.childCount) styleChip(tabs.getChildAt(k) as MaterialButton, k == i)
            val bar = c.optString("mode") == "product"
            val pts = c.optJSONArray("points") ?: JSONArray()
            val list = (0 until pts.length()).mapNotNull { pts.optJSONObject(it) }
            chart.barMode = bar
            chart.points = list.map { ChartView.Point(it.optString("label"), it.optInt("ok"), it.optInt("ng"), if (it.isNull("plan")) null else it.optInt("plan")) }
            chart.onPointClick = if (bar) ({ idx: Int ->
                list.getOrNull(idx)?.let { p -> if (!p.isNull("product_id")) filterProduct(p.optString("product_id")) }
                Unit
            }) else null
            legend.removeAllViews()
            if (bar) legend.addView(legendItem("Plan", amber))
            legend.addView(legendItem("OK", green))
            legend.addView(legendItem("NG", red))
            note.text = if (bar) "Hanya produk yang punya Plan/OK/NG. Ketuk batang untuk memfilter produk itu. Geser ke samping bila banyak." else "Geser ke samping bila banyak tanggal."
            hs.post { hs.scrollTo(0, 0) }
        }
        for (i in 0 until charts.length()) {
            val b = chip(charts.optJSONObject(i)?.optString("label").orEmpty(), false)
            b.setOnClickListener { show(i) }
            tabs.addView(b)
        }
        show(chartIndex)
    }

    private fun renderNg(list: JSONArray) {
        val box = card()
        box.addView(label("Top NG Categories", 15f, textMain, true).apply { setPadding(0, 0, 0, dp(6)) })
        if (list.length() == 0) { box.addView(label("Tidak ada data NG.", 12f, textSm)); return }
        for (i in 0 until list.length()) {
            val n = list.optJSONObject(i) ?: continue
            box.addView(row(
                weighted(label(n.optString("code") + "  " + n.optString("name"), 12f, textMain), 1f),
                label(fmt(n.optInt("qty")), 12f, red, true),
            ).apply { setPadding(0, dp(6), 0, dp(2)) })
            val pct = n.optInt("pct").coerceIn(0, 100)
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
                background = rounded(Color.parseColor("#FEE2E2"), dp(3).toFloat())
                clipToOutline = true
                addView(View(context).apply { setBackgroundColor(Color.parseColor("#EF4444")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct.toFloat().coerceAtLeast(0.001f)) })
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - pct).toFloat().coerceAtLeast(0.001f)) })
            })
        }
    }

    private fun renderBreakdown(list: JSONArray) {
        if (list.length() == 0) return
        val box = card()
        box.addView(label("Production per Product", 15f, textMain, true).apply { setPadding(0, 0, 0, dp(6)) })
        val rows = mutableListOf(listOf("Produk", "Plan", "OK", "NG", "Defect"))
        val ids = mutableListOf<String?>(null)
        val colors = mutableListOf<List<Int?>>(listOf(null, null, null, null, null))
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val rate = p.optDouble("defect_rate", 0.0)
            rows += listOf(p.optString("product_name") + "\n" + p.optString("product_code"), p.optInt("plans_count").toString(),
                fmt(p.optInt("ok")), fmt(p.optInt("ng")), num(rate) + "%")
            colors += listOf(null, null, green, red, if (rate > 5) red else textSm)
            ids += p.optString("product_id")
        }
        box.addView(table(rows, colors) { r -> ids.getOrNull(r)?.let { filterProduct(it) } })
        box.addView(label("Ketuk baris untuk memfilter seluruh dashboard dengan produk itu.", 11f, textSm).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun renderOperators(stages: JSONArray) {
        val box = card()
        box.addView(label("Operator Performance", 15f, textMain, true).apply { setPadding(0, 0, 0, dp(6)) })
        if (stages.length() == 0) { box.addView(label("Tidak ada data operator.", 12f, textSm)); return }
        for (i in 0 until stages.length()) {
            val st = stages.optJSONObject(i) ?: continue
            box.addView(label(st.optString("label").uppercase(Locale.ROOT), 11f, textSm, true).apply { setPadding(0, dp(8), 0, dp(4)) })
            val list = st.optJSONArray("rows") ?: JSONArray()
            if (list.length() == 0) { box.addView(label("Tidak ada data operator untuk stage ini.", 12f, textSm)); continue }
            val rows = mutableListOf(listOf("No", "Nama Operator", "Target", "Second", "Actual", "Second", "%"))
            val colors = mutableListOf<List<Int?>>(List(7) { null })
            for (k in 0 until list.length()) {
                val o = list.optJSONObject(k) ?: continue
                val pct = o.optDouble("takt_percentage", 0.0)
                val name = o.optString("name") + if (!o.isNull("machine") && o.optString("machine").isNotBlank()) "\n" + o.optString("machine") else ""
                rows += listOf((k + 1).toString(), name, fmt(o.optInt("target")), num2(o.optDouble("second_target", 0.0)),
                    fmt(o.optInt("actual")), num2(o.optDouble("second_actual", 0.0)), num(pct) + "%")
                colors += listOf(null, null, null, null, null, null, if (pct >= 100) green else amber)
            }
            box.addView(table(rows, colors, null))
        }
    }

    private fun renderPlans(list: JSONArray) {
        val box = card()
        box.addView(label("Recent Plans", 15f, textMain, true).apply { setPadding(0, 0, 0, dp(6)) })
        if (list.length() == 0) { box.addView(label("Tidak ada Plan di periode ini.", 12f, textSm)); return }
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val people = p.optJSONArray("people") ?: JSONArray()
            val who = (0 until people.length()).joinToString("  ·  ") { people.optString(it) }.ifBlank { "—" }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(row(
                    weighted(label(p.optString("lot_id"), 14f, blue, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }, 1f),
                    badge(p.optString("status_label"), p.optString("status_color")),
                ))
                addView(label(p.optString("product"), 13f, textMain))
                addView(label(who, 11f, textSm))
            }
            if (prefs.canView) {
                item.isClickable = true
                item.setOnClickListener {
                    startActivity(Intent(this, PlanActivity::class.java).putExtra(PlanActivity.EXTRA_CODE, p.optString("lot_id")))
                }
            }
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(Color.parseColor("#E5E7EB")); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1) })
            box.addView(item)
        }
        if (prefs.canView) box.addView(label("Ketuk Plan untuk melihat datanya.", 11f, textSm).apply { setPadding(0, dp(4), 0, 0) })
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

    private fun statTile(title: String, value: String, valueColor: Int, sub: String, subColor: Int): View {
        val c = MaterialCardView(this).apply { radius = dp(10).toFloat(); cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE); strokeWidth = 0 }
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(label(title, 12f, textSm, true))
            addView(label(value, 22f, valueColor, true))
            addView(label(sub, 11f, subColor))
        })
        return c
    }

    private fun chip(text: String, selected: Boolean): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text; isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            styleChip(this, selected)
        }

    private fun styleChip(b: MaterialButton, selected: Boolean) {
        b.setBackgroundColor(if (selected) blue else Color.WHITE)
        b.setTextColor(if (selected) Color.WHITE else textSm)
    }

    private fun outlined(text: String): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text; isAllCaps = false; textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

    private fun legendItem(text: String, color: Int): View = row(
        View(this).apply { setBackgroundColor(color); layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(4) } },
        label(text, 12f, textSm).apply { setPadding(0, 0, dp(12), 0) },
    )

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

    /** Tabel sederhana (baris 0 = judul); lebar kolom mengikuti isi, digeser ke samping bila lebar. */
    private fun table(rows: List<List<String>>, colors: List<List<Int?>>, onRow: ((Int) -> Unit)?): View {
        val cols = rows.first().size
        val grid = android.widget.TableLayout(this).apply { isStretchAllColumns = false; setColumnShrinkable(1, false) }
        rows.forEachIndexed { r, cells ->
            val tr = android.widget.TableRow(this).apply {
                setPadding(0, dp(5), 0, dp(5))
                if (r == 0) setBackgroundColor(Color.parseColor("#F3F4F6"))
                if (r > 0 && onRow != null) { isClickable = true; setOnClickListener { onRow(r) } }
            }
            for (c in 0 until cols) {
                val color = colors.getOrNull(r)?.getOrNull(c) ?: if (r == 0) textSm else textMain
                tr.addView(label(cells.getOrElse(c) { "" }, if (r == 0) 11f else 12f, color, r == 0 || colors.getOrNull(r)?.getOrNull(c) != null).apply {
                    setPadding(dp(6), 0, dp(10), 0)
                })
            }
            grid.addView(tr)
            if (r > 0) grid.addView(View(this).apply { setBackgroundColor(Color.parseColor("#F3F4F6")); layoutParams = android.widget.TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1) })
        }
        return HorizontalScrollView(this).apply { addView(grid) }
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

    private fun space(h: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun fmt(n: Int): String = String.format(Locale("in", "ID"), "%,d", n)
    private fun num(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    private fun num2(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun showDate(iso: String): String =
        if (iso.length == 10) "${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}" else iso

    private fun parseIso(s: String): Long? = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) { null }

    private fun duration(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return when { h > 0 -> "${h}j ${m}m ${s}d"; m > 0 -> "${m}m ${s}d"; else -> "${s}d" }
    }
}
