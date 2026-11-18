package com.sanwa.lotcard.scanner

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
 * INPUT PEROLEHAN operator (stage Auto), sama dengan form scan operator di web:
 * bagian 1 (operator utama) + Penerus Shift + bagian 2, NG per kategori
 * (+ posisi N Winding), Additional Material Winding, parameter Assembly, catatan.
 * Data form: GET /api/mobile/input/{kode}; simpan: POST /api/mobile/input/{kode}
 * (aturan server = AutoResultService, sama persis dengan web).
 */
class InputActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnSubmit: MaterialButton
    private var code: String = ""

    // ── Data form dari server ─────────────────────────────────────────
    private var form: JSONObject? = null
    private var stage = ""
    private var isWinding = false
    private var prevOk = 0
    private var qtyPlan = 0
    private var canPart1 = false
    private var canPart2 = false
    private var canChooseSuccessor = false
    private var isSuccessorUser = false
    private var savedSuccessorId = 0
    private var savedSuccessorName = ""
    private var part2Saved = false
    private var part1SavedQty = 0
    private var ngOptions: List<PickOption> = emptyList()
    private var positions: List<String> = emptyList()

    // ── Isian ─────────────────────────────────────────────────────────
    private var successorId = 0
    private var successorName = ""
    private val part1 = Part(1)
    private val part2 = Part(2)
    private var part2Card: View? = null
    private var part2Body: LinearLayout? = null
    private var successorButton: MaterialButton? = null
    private var totalLine: TextView? = null
    private var excessCard: View? = null
    private var excessLabel: TextView? = null
    private val excessDetails = mutableListOf<ExcessDetail>()
    private val lotCache = mutableMapOf<Int, List<PickOption>>()
    private val params = linkedMapOf<String, EditText>()
    private var notesInput: EditText? = null

    private inner class NgRow(val view: View, val qty: EditText, val catButton: MaterialButton, val posButton: MaterialButton?) {
        var categoryId: String = ""
        var position: String = ""
    }

    /** Satu bagian perolehan (1 = operator utama, 2 = Penerus Shift). */
    private inner class Part(val no: Int) {
        var editable = false
        var qtyInput: EditText? = null
        var qtyLabel: TextView? = null
        var qtyHint: TextView? = null
        var ngBox: LinearLayout? = null
        val ngRows = mutableListOf<NgRow>()
        var summary: TextView? = null
        var savedQtyOk = 0
        var savedQtyNg = 0

        fun qty(): Int = qtyInput?.text?.toString()?.trim()?.toIntOrNull() ?: 0
        fun ngTotal(): Int = if (editable) ngRows.sumOf { it.qty.text.toString().trim().toIntOrNull() ?: 0 } else savedQtyNg
    }

    private inner class ExcessDetail(val id: Int, val qtyPerPcs: Double, val unit: String, val neededLabel: TextView, val rowsBox: LinearLayout) {
        val rows = mutableListOf<LotRow>()
    }

    private inner class LotRow(val view: View, val lotButton: MaterialButton, val qty: EditText) {
        var lotNumber: String = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input)
        code = intent.getStringExtra(PlanActivity.EXTRA_CODE).orEmpty()
        setupToolbar("Input — $code", back = true)

        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        scroll = findViewById(R.id.scroll)
        btnSubmit = findViewById(R.id.btnSubmit)
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener { load() }
        btnSubmit.setOnClickListener { confirmAndSubmit() }
        load()
    }

    // ── Ambil data form ───────────────────────────────────────────────
    private fun load() {
        progress.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        btnSubmit.isEnabled = false
        content.removeAllViews()
        lifecycleScope.launch {
            val r = api.call("GET", "/api/mobile/input/" + Uri.encode(code))
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
        btnSubmit.isEnabled = false
    }

    // ── Helper tampilan ───────────────────────────────────────────────
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

    private fun label(s: String, sizeSp: Float = 14f, color: Int = Color.parseColor("#111827"), bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(parent: LinearLayout = content, bg: Int = Color.WHITE): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(this).apply {
            radius = dp(10).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(bg)
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(inner)
        parent.addView(card)
        return card to inner
    }

    private fun numberInput(value: String?, decimal: Boolean, hint: String = ""): EditText = EditText(this).apply {
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        setSingleLine()
        this.hint = hint
        setText(value ?: "")
        textSize = 16f
    }

    private fun pickButton(text: String): MaterialButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    private fun smallButton(text: String): MaterialButton = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
    }

    private fun afterChange(e: EditText, f: () -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = f()
        })
    }

    private fun fmt(n: Int): String = String.format(Locale("in", "ID"), "%,d", n)

    private fun fmtDec(d: Double): String {
        val s = String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')
        return s.replace('.', ',')
    }

    // ── Gambar form ───────────────────────────────────────────────────
    private fun render(json: JSONObject) {
        form = json
        content.removeAllViews()
        errorBox.visibility = View.GONE
        params.clear(); excessDetails.clear()
        part1.ngRows.clear(); part2.ngRows.clear()

        val plan = json.optJSONObject("plan") ?: JSONObject()
        val role = json.optJSONObject("role") ?: JSONObject()
        stage = json.optString("stage")
        isWinding = json.optBoolean("is_winding")
        prevOk = if (json.isNull("prev_ok")) 0 else json.optInt("prev_ok")
        qtyPlan = plan.optInt("qty_plan")
        canPart1 = role.optBoolean("can_part1")
        canPart2 = role.optBoolean("can_part2")
        canChooseSuccessor = role.optBoolean("can_choose_successor")
        isSuccessorUser = role.optBoolean("is_successor")
        val succ = json.optJSONObject("successor")
        savedSuccessorId = succ?.optInt("id") ?: 0
        savedSuccessorName = succ?.str("name") ?: ""
        successorId = savedSuccessorId
        successorName = savedSuccessorName
        val p1 = json.optJSONObject("part1") ?: JSONObject()
        val p2 = json.optJSONObject("part2") ?: JSONObject()
        part2Saved = p2.optBoolean("saved")
        part1SavedQty = p1.optInt("qty_input")

        val cats = json.optJSONArray("ng_categories") ?: JSONArray()
        ngOptions = (0 until cats.length()).mapNotNull { i ->
            cats.optJSONObject(i)?.let { c -> PickOption(c.optInt("id").toString(), "${c.optString("code")} — ${c.optString("name")}") }
        }
        val pos = json.optJSONArray("winding_positions") ?: JSONArray()
        positions = (0 until pos.length()).map { pos.optString(it) }

        supportActionBar?.title = "Input — " + (plan.str("lot_id") ?: code)

        // Kepala
        val (_, head) = card()
        head.addView(label(plan.str("lot_id") ?: "—", 22f, Color.parseColor("#1A6FC4"), true))
        head.addView(label(plan.str("product_name") ?: "—", 17f, bold = true))
        head.addView(label(plan.str("product_code") ?: "", 12f, Color.parseColor("#6B7280")))
        head.addView(label("Proses: " + json.optString("stage_label"), 15f, Color.parseColor("#166534"), true).apply { setPadding(0, dp(8), 0, 0) })
        val info = StringBuilder("Qty Plan: ${fmt(qtyPlan)} pcs")
        if (!isWinding) info.append(" · Qty OK proses sebelumnya: ${fmt(prevOk)} pcs")
        plan.str("winding_target")?.let { if (isWinding) info.append(" · Target: $it") }
        head.addView(label(info.toString(), 13f, Color.parseColor("#374151")))
        val res = json.optJSONObject("result") ?: JSONObject()
        val statusText = when (res.optString("status")) {
            "pending" -> "Sudah dikirim — menunggu approval Admin (masih bisa diubah)"
            "rejected" -> "Ditolak Admin — perbaiki lalu kirim ulang" + (res.str("reject_note")?.let { "\nCatatan: $it" } ?: "")
            else -> "Belum diinput"
        }
        head.addView(label(statusText, 13f, if (res.optString("status") == "rejected") Color.parseColor("#DC2626") else Color.parseColor("#6B7280")).apply {
            setPadding(0, dp(6), 0, 0)
        })

        // Bagian 1 — operator utama
        val primaryName = json.optJSONObject("primary_operator")?.str("name") ?: "—"
        val (_, b1) = card()
        b1.addView(label("Perolehan Operator Utama — $primaryName", 15f, bold = true))
        buildPart(b1, part1, editable = canPart1, p1)

        // Penerus Shift
        val (_, bs) = card()
        bs.addView(label("Penerus Shift", 15f, bold = true))
        if (canChooseSuccessor) {
            bs.addView(label("Isi jika Plan ini dilanjutkan operator lain di shift berikutnya. Perolehan penerus dicatat terpisah dan dijumlahkan.", 12f, Color.parseColor("#6B7280")))
            val ops = json.optJSONArray("operators") ?: JSONArray()
            val opOptions = mutableListOf(PickOption("", "— Tidak ada / dikerjakan 1 shift —"))
            for (i in 0 until ops.length()) {
                val o = ops.optJSONObject(i) ?: continue
                opOptions += PickOption(o.optInt("id").toString(), "${o.optString("name")} (${o.optString("number")})")
            }
            val btn = pickButton(successorButtonText())
            btn.setOnClickListener {
                PickerDialog.show(this, "Pilih Penerus Shift", opOptions) { pick ->
                    successorId = pick.value.toIntOrNull() ?: 0
                    successorName = if (successorId > 0) pick.label.substringBeforeLast(" (") else ""
                    btn.text = successorButtonText()
                    onSuccessorChanged()
                }
            }
            successorButton = btn
            bs.addView(btn)
        } else {
            bs.addView(label(if (savedSuccessorName.isBlank()) "—" else savedSuccessorName + (if (isSuccessorUser) " (Anda)" else ""), 14f, bold = true))
        }

        // Bagian 2 — Penerus Shift
        val (c2, b2) = card(bg = Color.parseColor("#FFFBEB"))
        part2Card = c2
        part2Body = b2
        rebuildPart2(p2)

        totalLine = label("", 14f, Color.parseColor("#166534"), true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#E5F5EC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        content.addView(totalLine)

        // Additional Material (Winding)
        json.optJSONObject("excess")?.let { buildExcess(it) }

        // Parameter Assembly
        json.optJSONObject("params")?.let { buildParams(it) }

        // Catatan
        val (_, bn) = card()
        bn.addView(label("Catatan tambahan", 14f, bold = true))
        notesInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            hint = "Catatan proses jika diperlukan…"
            setText(json.str("notes") ?: "")
        }
        bn.addView(notesInput)

        btnSubmit.text = if (isSuccessorUser && !canPart1) "Kirim Perolehan Saya" else "Kirim & Minta Persetujuan Admin"
        btnSubmit.isEnabled = canPart1 || canPart2
        onSuccessorChanged()
        scroll.scrollTo(0, 0)
    }

    private fun successorButtonText(): String =
        if (successorId > 0) "Penerus: $successorName" else "— Tidak ada / dikerjakan 1 shift —"

    /** Kotak qty + NG + ringkasan untuk satu bagian. */
    private fun buildPart(box: LinearLayout, part: Part, editable: Boolean, saved: JSONObject) {
        part.editable = editable
        part.ngRows.clear()
        part.savedQtyOk = saved.optInt("qty_ok")
        part.savedQtyNg = saved.optInt("qty_ng")
        if (!editable) {
            part.qtyInput = null
            val hasSaved = saved.optBoolean("saved")
            box.addView(label(
                if (hasSaved) "Qty ${fmt(saved.optInt("qty_input"))} pcs · OK ${fmt(saved.optInt("qty_ok"))} · NG ${fmt(saved.optInt("qty_ng"))}"
                else "Belum diisi.",
                14f, Color.parseColor("#374151")
            ).apply { setPadding(0, dp(6), 0, 0) })
            part.summary = null
            return
        }
        part.qtyLabel = label("", 13f, Color.parseColor("#166534"), true).apply { setPadding(0, dp(8), 0, 0) }
        box.addView(part.qtyLabel)
        val startQty = saved.optInt("qty_input")
        part.qtyInput = numberInput(if (isWinding && startQty == 0 && !saved.optBoolean("saved")) "" else startQty.toString(), false, "0")
        afterChange(part.qtyInput!!) { recalc() }
        box.addView(part.qtyInput)
        part.qtyHint = label("", 11f, Color.parseColor("#6B7280"))
        box.addView(part.qtyHint)

        box.addView(label("NG per kategori", 13f, Color.parseColor("#B91C1C"), true).apply { setPadding(0, dp(10), 0, dp(2)) })
        val ngBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        part.ngBox = ngBox
        box.addView(ngBox)
        val ng = saved.optJSONArray("ng") ?: JSONArray()
        for (i in 0 until ng.length()) {
            val n = ng.optJSONObject(i) ?: continue
            addNgRow(part, n.optInt("category_id").toString(), n.optInt("qty"), n.str("position") ?: "")
        }
        val add = smallButton("+ Tambah NG")
        add.setOnClickListener { addNgRow(part, "", 0, "") }
        box.addView(add)
        part.summary = label("", 14f, bold = true).apply { setPadding(0, dp(6), 0, 0) }
        box.addView(part.summary)
    }

    private fun addNgRow(part: Part, categoryId: String, qty: Int, position: String) {
        val box = part.ngBox ?: return
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val catBtn = pickButton("Pilih kategori NG")
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val posBtn = if (isWinding) pickButton("Posisi").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        } else null
        val qtyIn = numberInput(if (qty > 0) qty.toString() else "", false, "Qty").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val del = smallButton("Hapus").apply { setTextColor(Color.parseColor("#DC2626")) }
        val row = NgRow(wrap, qtyIn, catBtn, posBtn)
        row.categoryId = categoryId
        row.position = position
        catBtn.text = ngOptions.firstOrNull { it.value == categoryId }?.label ?: "Pilih kategori NG"
        catBtn.setOnClickListener {
            PickerDialog.show(this, "Kategori NG", ngOptions) { pick ->
                row.categoryId = pick.value
                catBtn.text = pick.label
            }
        }
        if (posBtn != null) {
            posBtn.text = if (position.isBlank()) "Posisi" else position
            posBtn.setOnClickListener {
                PickerDialog.show(this, "Posisi NG", positions.map { PickOption(it, it) }) { pick ->
                    row.position = pick.value
                    posBtn.text = pick.value
                }
            }
            line.addView(posBtn)
        }
        line.addView(qtyIn)
        line.addView(label(" pcs", 13f, Color.parseColor("#6B7280")))
        line.addView(del)
        wrap.addView(catBtn)
        wrap.addView(line)
        afterChange(qtyIn) { recalc() }
        del.setOnClickListener {
            box.removeView(wrap)
            part.ngRows.remove(row)
            recalc()
        }
        part.ngRows += row
        box.addView(wrap)
        recalc()
    }

    /** Isi kartu bagian 2 sesuai penerus yang dipilih. */
    private fun rebuildPart2(saved: JSONObject?) {
        val box = part2Body ?: return
        box.removeAllViews()
        part2.ngRows.clear()
        part2.qtyInput = null
        part2.summary = null
        box.addView(label("Perolehan Penerus Shift — $successorName", 15f, bold = true))
        val sameAsSaved = successorId == savedSuccessorId && part2Saved
        val src = if (sameAsSaved && saved != null) saved else JSONObject().put("saved", false).put("qty_input", 0)
        if (canPart2) {
            buildPart(box, part2, editable = true, src)
        } else if (sameAsSaved) {
            buildPart(box, part2, editable = false, src)
        } else {
            part2.editable = false
            part2.savedQtyNg = 0
            part2.savedQtyOk = 0
            val note = if (savedSuccessorId > 0 && successorId != savedSuccessorId && part2Saved)
                "Akan diisi oleh $successorName saat scan QR Plan ini. Perolehan penerus sebelumnya akan dihapus saat Anda mengirim."
            else "Akan diisi oleh $successorName saat scan QR Plan ini (sebelum dikirim ke Admin)."
            box.addView(label(note, 13f, Color.parseColor("#92400E")).apply { setPadding(0, dp(6), 0, 0) })
        }
    }

    private fun onSuccessorChanged() {
        val show = successorId > 0
        part2Card?.visibility = if (show) View.VISIBLE else View.GONE
        totalLine?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val p2 = form?.optJSONObject("part2")
            rebuildPart2(p2)
        }
        // Assembly/Inspection: tanpa penerus, Qty operator utama = Qty OK proses sebelumnya (dikunci).
        val q1 = part1.qtyInput
        if (!isWinding && q1 != null) {
            q1.isEnabled = show
            if (!show) q1.setText(prevOk.toString())
            part1.qtyLabel?.text = if (show) "Qty dikerjakan operator utama (pcs)" else "Qty OK (pcs)"
            part1.qtyHint?.text = if (show) "Sisanya (dari ${fmt(prevOk)}) otomatis bagian penerus." else "Otomatis dari proses sebelumnya."
        } else if (isWinding) {
            part1.qtyLabel?.text = "Total Check OK (pcs)"
            part1.qtyHint?.text = "Qty OK final = Total Check OK − NG."
        }
        if (isWinding) {
            part2.qtyLabel?.text = "Total Check OK (pcs)"
            part2.qtyHint?.text = "Qty OK final = Total Check OK − NG."
        } else {
            part2.qtyInput?.isEnabled = false
            part2.qtyLabel?.text = "Qty dikerjakan penerus (pcs)"
            part2.qtyHint?.text = "Otomatis: Qty OK proses sebelumnya − qty operator utama."
        }
        recalc()
    }

    /** Hitung ulang ringkasan OK/NG, qty penerus (Assembly/Inspection), dan Additional Material. */
    private fun recalc() {
        val show = successorId > 0
        if (!isWinding && show) {
            val q2 = part2.qtyInput
            val want = maxOf(0, prevOk - qty1()).toString()
            if (q2 != null && q2.text.toString() != want) q2.setText(want)
        }
        var totalOk = 0
        var totalNg = 0
        for (part in listOf(part1, part2)) {
            if (part === part2 && !show) continue
            val ng = part.ngTotal()
            val ok = if (part.editable) {
                val q = if (part === part1) qty1() else part.qty()
                if (isWinding) maxOf(0, q - ng) else q
            } else part.savedQtyOk
            part.summary?.text = "NG: ${fmt(ng)} pcs · Qty OK final: ${fmt(ok)} pcs"
            part.summary?.setTextColor(if (isWinding && part.editable && ng > part.qty()) Color.parseColor("#DC2626") else Color.parseColor("#111827"))
            totalOk += ok
            totalNg += ng
        }
        totalLine?.text = "Total stage (utama + penerus): OK ${fmt(totalOk)} pcs · NG ${fmt(totalNg)} pcs"
        updateExcess()
    }

    /** Qty bagian 1 yang dikirim (Assembly/Inspection tanpa penerus = Qty proses sebelumnya). */
    private fun qty1(): Int = when {
        !isWinding && successorId == 0 -> prevOk
        part1.editable -> part1.qty()
        else -> part1SavedQty // Penerus Shift: bagian utama hanya dibaca
    }

    // ── Additional Material (Winding) ─────────────────────────────────
    private fun buildExcess(ex: JSONObject) {
        val details = ex.optJSONArray("bom_details") ?: return
        if (details.length() == 0) return
        val (c, box) = card(bg = Color.parseColor("#EFF6FF"))
        excessCard = c
        box.addView(label("Additional Material (Excess Production)", 15f, Color.parseColor("#1E40AF"), true))
        excessLabel = label("", 12f, Color.parseColor("#1E40AF"))
        box.addView(excessLabel)
        val saved = ex.optJSONArray("saved_lots") ?: JSONArray()
        for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            val id = d.optInt("id")
            box.addView(label(d.optString("item_name"), 14f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            box.addView(label(d.optString("item_code"), 11f, Color.parseColor("#6B7280")))
            val needed = label("", 13f, Color.parseColor("#1E40AF"), true)
            box.addView(needed)
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(rows)
            val det = ExcessDetail(id, d.optDouble("qty_per_pcs", 0.0), d.optString("unit"), needed, rows)
            excessDetails += det
            val add = smallButton("+ Tambah lot")
            add.setOnClickListener { addLotRow(det, "", "") }
            box.addView(add)
            for (j in 0 until saved.length()) {
                val l = saved.optJSONObject(j) ?: continue
                if (l.optInt("bom_detail_id") == id) addLotRow(det, l.optString("lot_number"), fmtNum(l.optDouble("qty", 0.0)))
            }
        }
        c.visibility = View.GONE
    }

    private fun fmtNum(d: Double): String = String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')

    private fun addLotRow(det: ExcessDetail, lot: String, qty: String) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lotBtn = pickButton(if (lot.isBlank()) "Pilih lot" else lot).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { marginEnd = dp(6) }
        }
        val qtyIn = numberInput(qty, true, "Qty").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val del = smallButton("Hapus").apply { setTextColor(Color.parseColor("#DC2626")) }
        line.addView(lotBtn); line.addView(qtyIn); line.addView(del)
        val row = LotRow(line, lotBtn, qtyIn)
        row.lotNumber = lot
        lotBtn.setOnClickListener { pickLot(det, row) }
        del.setOnClickListener {
            det.rowsBox.removeView(line)
            det.rows.remove(row)
        }
        det.rows += row
        det.rowsBox.addView(line)
    }

    private fun pickLot(det: ExcessDetail, row: LotRow) {
        val cached = lotCache[det.id]
        if (cached != null) {
            PickerDialog.show(this, "Pilih lot material", cached) { pick ->
                row.lotNumber = pick.value
                row.lotButton.text = pick.value
            }
            return
        }
        lifecycleScope.launch {
            val r = api.call("GET", "/api/mobile/bom-detail/${det.id}/lots")
            when (r) {
                is ApiResult.Response -> {
                    if (r.code == 401) { goToLogin(r.message); return@launch }
                    if (!r.ok) { showMessage("Lot material", r.message); return@launch }
                    val arr = r.json.optJSONArray("lots") ?: JSONArray()
                    val opts = (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { l ->
                            PickOption(l.optString("lot_number"), "${l.optString("lot_number")} (sisa ${fmtDec(l.optDouble("available", 0.0))} ${l.optString("unit")})")
                        }
                    }
                    if (opts.isEmpty()) { showMessage("Lot material", "Tidak ada lot dengan sisa stok untuk material ini."); return@launch }
                    lotCache[det.id] = opts
                    pickLot(det, row)
                }
                is ApiResult.UntrustedCert -> askTrustCertificate(r) { pickLot(det, row) }
                is ApiResult.NetworkError -> showMessage("Lot material", r.message)
            }
        }
    }

    /** Total Check OK semua bagian (yang diisi di HP ini) melebihi Qty Plan → tampilkan Additional Material. */
    private fun grossQty(): Int = qty1() + (if (successorId > 0 && part2.editable) part2.qty() else 0)

    private fun excessQty(): Int = if (isWinding) grossQty() - qtyPlan else 0

    private fun updateExcess() {
        val c = excessCard ?: return
        val excess = excessQty()
        c.visibility = if (excess > 0) View.VISIBLE else View.GONE
        if (excess <= 0) return
        excessLabel?.text = "Qty melebihi Qty Plan (${fmt(excess)} pcs lebih). Pilih lot tambahan untuk menutupi kelebihan material yang dipakai."
        for (d in excessDetails) {
            d.neededLabel.text = "Dibutuhkan: ${fmtDec(d.qtyPerPcs * excess)} ${d.unit}"
        }
    }

    private fun buildParams(p: JSONObject) {
        val (_, box) = card(bg = Color.parseColor("#FEF3DC"))
        box.addView(label("Assembly Process Data", 15f, Color.parseColor("#92600A"), true))
        val fields = listOf(
            Triple("solder_temp", "Solder Temp (°C)", true),
            Triple("solder_time", "Solder Time (detik)", false),
            Triple("oven_dry_temp", "Oven Dry Temp (°C)", true),
            Triple("oven_dry_time", "Oven Dry Time (detik)", false),
            Triple("refrigerator_temp", "Refrigerator Temp (°C)", true),
        )
        for ((key, title, decimal) in fields) {
            box.addView(label(title, 12f, Color.parseColor("#374151")).apply { setPadding(0, dp(6), 0, 0) })
            val e = numberInput(p.str(key)?.let { fmtNum(it.toDoubleOrNull() ?: 0.0) }, decimal)
            params[key] = e
            box.addView(e)
        }
        box.addView(label("Lot Code (A–Z, tanpa I dan O)", 12f, Color.parseColor("#374151")).apply { setPadding(0, dp(6), 0, 0) })
        val lot = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
            setText(p.str("lot_code") ?: "")
        }
        params["lot_code"] = lot
        box.addView(lot)
    }

    // ── Kirim ─────────────────────────────────────────────────────────
    /** Kumpulkan isian; null + pesan kalau ada yang belum lengkap. */
    private fun buildBody(): Pair<JSONObject?, String?> {
        val body = JSONObject().put("process_stage", stage)
        if (canPart1) {
            if (isWinding && part1.qtyInput?.text.isNullOrBlank()) return null to "Total Check OK operator utama wajib diisi."
            body.put("qty_ok_input", qty1())
            val err = putNg(body, part1, "")
            if (err != null) return null to err
        }
        if (canChooseSuccessor) body.put("operator_2_id", if (successorId > 0) successorId else JSONObject.NULL)
        if (successorId > 0 && canPart2) {
            if (isWinding && part2.qtyInput?.text.isNullOrBlank()) return null to "Total Check OK Penerus Shift wajib diisi."
            body.put("s2_present", 1)
            body.put("s2_qty_ok_input", if (isWinding) part2.qty() else maxOf(0, prevOk - qty1()))
            val err = putNg(body, part2, "s2_")
            if (err != null) return null to err
        }
        if (excessQty() > 0 && excessDetails.isNotEmpty()) {
            val lots = JSONObject()
            for (d in excessDetails) {
                val arr = JSONArray()
                for (r in d.rows) {
                    val q = r.qty.text.toString().trim().replace(',', '.')
                    if (r.lotNumber.isBlank() && q.isEmpty()) continue
                    if (r.lotNumber.isBlank()) return null to "Additional Material: pilih lot untuk setiap baris yang diisi."
                    val qd = q.toDoubleOrNull() ?: return null to "Additional Material: qty lot ${r.lotNumber} wajib diisi."
                    arr.put(JSONObject().put("lot_number", r.lotNumber).put("qty", qd))
                }
                if (arr.length() > 0) lots.put(d.id.toString(), arr)
            }
            if (lots.length() > 0) body.put("excess_material_lots", lots)
        }
        for ((key, e) in params) {
            val v = e.text.toString().trim().replace(',', '.')
            body.put(key, if (v.isEmpty()) JSONObject.NULL else v)
        }
        val notes = notesInput?.text?.toString()?.trim().orEmpty()
        body.put("notes", if (notes.isEmpty()) JSONObject.NULL else notes)
        return body to null
    }

    private fun putNg(body: JSONObject, part: Part, prefix: String): String? {
        val cats = JSONArray()
        val qtys = JSONArray()
        val poses = JSONArray()
        val who = if (part.no == 1) "operator utama" else "Penerus Shift"
        for (r in part.ngRows) {
            val q = r.qty.text.toString().trim().toIntOrNull() ?: 0
            if (r.categoryId.isBlank()) return "NG $who: pilih kategori untuk setiap baris NG (atau hapus barisnya)."
            if (q <= 0) return "NG $who: qty NG wajib diisi (lebih dari 0)."
            if (isWinding && r.position.isBlank()) return "NG $who: pilih Posisi NG untuk setiap baris."
            cats.put(r.categoryId.toInt())
            qtys.put(q)
            poses.put(if (r.position.isBlank()) JSONObject.NULL else r.position)
        }
        body.put("${prefix}ng_categories", cats)
        body.put("${prefix}ng_quantities", qtys)
        if (isWinding) body.put("${prefix}ng_winding_positions", poses)
        return null
    }

    private fun confirmAndSubmit() {
        val (body, err) = buildBody()
        if (body == null) {
            showMessage("Belum lengkap", err ?: "Periksa isian.")
            return
        }
        val msg = StringBuilder()
        msg.append("Proses: ${form?.optString("stage_label")}\n")
        msg.append(totalLine?.takeIf { successorId > 0 }?.text ?: (part1.summary?.text ?: part2.summary?.text ?: ""))
        msg.append("\n\nKirim perolehan ini?")
        MaterialAlertDialogBuilder(this)
            .setTitle("Kirim perolehan")
            .setMessage(msg.toString())
            .setPositiveButton("Kirim") { _, _ -> submit(body) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun submit(body: JSONObject) {
        btnSubmit.isEnabled = false
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = api.call("POST", "/api/mobile/input/" + Uri.encode(code), body)
            progress.visibility = View.GONE
            btnSubmit.isEnabled = true
            when (r) {
                is ApiResult.Response -> when {
                    r.ok -> {
                        MaterialAlertDialogBuilder(this@InputActivity)
                            .setTitle("Tersimpan")
                            .setMessage(r.message)
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .show()
                    }
                    r.code == 401 -> goToLogin(r.message)
                    else -> showMessage("Belum tersimpan", errorDetail(r.json, r.message))
                }
                is ApiResult.UntrustedCert -> askTrustCertificate(r) { submit(body) }
                is ApiResult.NetworkError -> showMessage("Belum tersimpan", r.message)
            }
        }
    }

    /** Pesan + daftar error validasi Laravel (errors: {field: [pesan]}). */
    private fun errorDetail(json: JSONObject, fallback: String): String {
        val errors = json.optJSONObject("errors") ?: return fallback
        val lines = mutableListOf<String>()
        val keys = errors.keys()
        while (keys.hasNext()) {
            val arr = errors.optJSONArray(keys.next()) ?: continue
            for (i in 0 until arr.length()) lines += "• " + arr.optString(i)
        }
        return if (lines.isEmpty()) fallback else lines.distinct().joinToString("\n")
    }
}
