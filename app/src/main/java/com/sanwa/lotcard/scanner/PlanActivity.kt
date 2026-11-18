package com.sanwa.lotcard.scanner

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Menampilkan data Plan hasil scan (bentuk data: lihat MobilePlanPresenter.php di server). */
class PlanActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var scroll: ScrollView
    private var code: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan)
        code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        setupToolbar(code.ifBlank { "Data Plan" }, back = true)

        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        scroll = findViewById(R.id.scroll)
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener { load() }
        findViewById<MaterialButton>(R.id.btnScanAgain).setOnClickListener { finish() }
        load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.plan_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_reload) { load(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        lifecycleScope.launch {
            val r = api.call("GET", "/api/mobile/plans/" + Uri.encode(code))
            progress.visibility = View.GONE
            when (r) {
                is ApiResult.Response -> when {
                    r.ok -> render(r.json, r.baseUrl)
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

    // ── Tampilan ──────────────────────────────────────────────────────
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun colorOf(name: String?): Int = when (name) {
        "green" -> Color.parseColor("#16A34A")
        "red" -> Color.parseColor("#DC2626")
        "amber" -> Color.parseColor("#D97706")
        "blue" -> Color.parseColor("#1A6FC4")
        "gray" -> Color.parseColor("#6B7280")
        else -> Color.parseColor("#111827")
    }

    /** org.json mengembalikan "null" untuk nilai null — ini tidak. */
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun text(s: String, sizeSp: Float = 14f, color: Int = Color.parseColor("#111827"), bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun badge(textValue: String, colorName: String?): TextView = text(textValue, 12f, Color.WHITE, true).apply {
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(colorOf(colorName))
        }
    }

    private fun card(): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(this).apply {
            radius = dp(10).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(inner)
        content.addView(card)
        return card to inner
    }

    private fun render(json: JSONObject, baseUrl: String) {
        content.removeAllViews()
        errorBox.visibility = View.GONE

        val plan = json.optJSONObject("plan") ?: JSONObject()
        supportActionBar?.title = plan.str("lot_id") ?: code

        // Kepala: nomor lot, produk, status, progres.
        val (_, head) = card()
        head.addView(text(plan.str("lot_id") ?: "—", 24f, Color.parseColor("#1A6FC4"), true))
        head.addView(text(plan.str("product_name") ?: "—", 18f, bold = true))
        head.addView(text(plan.str("product_code") ?: "", 13f, Color.parseColor("#6B7280")))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(badge(plan.str("status_label") ?: "—", plan.str("status_color")))
        row.addView(text("  Progres ${plan.optInt("progress_percent")}%", 13f, Color.parseColor("#374151")))
        head.addView(row)
        val bar = LinearProgressIndicator(this)
        bar.max = 100
        bar.setProgressCompat(plan.optInt("progress_percent").coerceIn(0, 100), false)
        head.addView(bar.apply {
            trackCornerRadius = dp(4)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        })
        json.str("highlight")?.let { h ->
            head.addView(text("Discan: $h", 14f, Color.parseColor("#92400E"), true).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundColor(Color.parseColor("#FEF3C7"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) }
            })
        }

        val sections = json.optJSONArray("sections") ?: JSONArray()
        for (i in 0 until sections.length()) {
            val s = sections.optJSONObject(i) ?: continue
            renderSection(s)
        }

        content.addView(text(
            "Data per ${json.str("generated_at") ?: "-"} · server ${prefs.labelFor(baseUrl)}",
            11f, Color.parseColor("#9CA3AF")
        ).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(12)) })
        scroll.scrollTo(0, 0)
    }

    private fun renderSection(s: JSONObject) {
        val (_, box) = card()
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text(s.str("title") ?: "", 16f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        s.optJSONObject("badge")?.let { b -> titleRow.addView(badge(b.str("text") ?: "", b.str("color"))) }
        box.addView(titleRow)

        val items = s.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            line.addView(text(item.str("label") ?: "", 13f, Color.parseColor("#6B7280")).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f)
            })
            line.addView(text(item.str("value") ?: "—", 14f, colorOf(item.str("color")), true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f)
                setTextIsSelectable(true)
            })
            box.addView(line)
            if (i < items.length() - 1) box.addView(divider())
        }

        val tables = s.optJSONArray("tables") ?: JSONArray()
        for (i in 0 until tables.length()) {
            val t = tables.optJSONObject(i) ?: continue
            renderTable(box, t)
        }

        s.str("note")?.let { n ->
            box.addView(text(n, 13f, Color.parseColor("#6B7280")).apply {
                setTypeface(typeface, Typeface.ITALIC)
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#F3F4F6"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun renderTable(box: LinearLayout, t: JSONObject) {
        t.str("title")?.let { title ->
            box.addView(text(title, 13f, Color.parseColor("#374151"), true).apply { setPadding(0, dp(12), 0, dp(4)) })
        }
        val table = TableLayout(this).apply { setPadding(0, dp(6), 0, 0) }
        val highlight = if (t.has("highlight_row") && !t.isNull("highlight_row")) t.optInt("highlight_row", -1) else -1

        fun cell(v: String, header: Boolean, bg: Int): TextView = text(
            v, if (header) 12f else 13f,
            if (header) Color.parseColor("#374151") else Color.parseColor("#111827"),
            header
        ).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(bg)
            maxWidth = dp(220)
        }

        val cols = t.optJSONArray("columns") ?: JSONArray()
        val headRow = TableRow(this)
        for (c in 0 until cols.length()) headRow.addView(cell(cols.optString(c), true, Color.parseColor("#F3F4F6")))
        table.addView(headRow)

        val rows = t.optJSONArray("rows") ?: JSONArray()
        for (r in 0 until rows.length()) {
            val arr = rows.optJSONArray(r) ?: continue
            val bg = when {
                r == highlight -> Color.parseColor("#FEF3C7")
                r % 2 == 1 -> Color.parseColor("#FAFAFA")
                else -> Color.WHITE
            }
            val tr = TableRow(this)
            for (c in 0 until arr.length()) tr.addView(cell(if (arr.isNull(c)) "—" else arr.optString(c), false, bg))
            table.addView(tr)
        }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(table)
        }
        box.addView(hs)
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
