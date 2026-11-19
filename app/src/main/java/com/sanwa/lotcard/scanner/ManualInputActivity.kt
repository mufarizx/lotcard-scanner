package com.sanwa.lotcard.scanner

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import java.util.Calendar
import java.util.Locale

/**
 * INPUT MANUAL Assembly/Inspection — metode 1 lembar (tanpa IPCS turunan),
 * sama dengan halaman web Input Hasil Manual:
 *  - per sub-proses: Selesai, Tanggal, Shift, NIK, Mesin, parameter;
 *  - Total Check OK + NG per kategori dengan Posisi (sub-proses);
 *  - Qty OK = Total Check OK − total NG.
 * Data form: GET /api/mobile/manual/{kode}?stage=… ;
 * simpan: POST /api/mobile/manual/{kode} (submit=true → sekaligus Submit).
 */
class ManualInputActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnSubmit: MaterialButton
    private var code: String = ""
    private var stage: String? = null

    private var locked = false
    private var subcont = false
    private var startQty: Int? = null
    private var today = ""
    private var ngOptions: List<PickOption> = emptyList()
    private var posOptions: List<PickOption> = emptyList()
    private val steps = mutableListOf<StepRow>()
    private val ngRows = mutableListOf<NgRow>()
    private var ngBox: LinearLayout? = null
    private var checkInput: EditText? = null
    private var summary: TextView? = null

    private inner class StepRow(val order: Int, val label: String, val saved: Boolean, val needsMachine: Boolean) {
        lateinit var done: CheckBox
        var workDate: String = ""
        var shift: String = ""
        var lineId: String = ""
        lateinit var dateBtn: MaterialButton
        lateinit var shiftBtn: MaterialButton
        var machineBtn: MaterialButton? = null
        var machineText: EditText? = null
        lateinit var nik: EditText
        val params = linkedMapOf<String, EditText>()
    }

    private inner class NgRow(val view: View, val qty: EditText, val posBtn: MaterialButton, val catBtn: MaterialButton) {
        var position: String = ""
        var categoryId: String = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual)
        code = intent.getStringExtra(PlanActivity.EXTRA_CODE).orEmpty()
        setupToolbar("Input Manual — $code", back = true)

        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        scroll = findViewById(R.id.scroll)
        btnSave = findViewById(R.id.btnSave)
        btnSubmit = findViewById(R.id.btnSubmit)
        findViewById<MaterialButton>(R.id.btnRetry).setOnClickListener { load() }
        btnSave.setOnClickListener { save(false) }
        btnSubmit.setOnClickListener { confirmSubmit() }
        load()
    }

    // ── Ambil data form ───────────────────────────────────────────────
    private fun load() {
        progress.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        setButtons(false)
        content.removeAllViews()
        lifecycleScope.launch {
            val path = "/api/mobile/manual/" + Uri.encode(code) + (stage?.let { "?stage=" + Uri.encode(it) } ?: "")
            val r = api.call("GET", path)
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
        setButtons(false)
    }

    private fun setButtons(enabled: Boolean) {
        btnSave.isEnabled = enabled
        btnSubmit.isEnabled = enabled
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

    private fun card(bg: Int = Color.WHITE): LinearLayout {
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
        content.addView(card)
        return inner
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

    private fun weighted(v: View, weight: Float, endMargin: Int = 0): View = v.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = endMargin }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private fun afterChange(e: EditText, f: () -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = f()
        })
    }

    private fun fmt(n: Int): String = String.format(Locale("in", "ID"), "%,d", n)

    private fun fmtNum(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    /** "2026-11-19" → "19/11/2026". */
    private fun showDate(iso: String): String =
        if (iso.length == 10) "${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}" else iso

    // ── Gambar form ───────────────────────────────────────────────────
    private fun render(json: JSONObject) {
        content.removeAllViews()
        errorBox.visibility = View.GONE
        steps.clear(); ngRows.clear()

        val plan = json.optJSONObject("plan") ?: JSONObject()
        stage = json.optString("stage")
        locked = json.optBoolean("locked")
        subcont = json.optBoolean("subcont")
        startQty = if (json.isNull("start_qty")) null else json.optInt("start_qty")
        today = json.optString("today")
        supportActionBar?.title = "Input Manual — " + (plan.str("lot_id") ?: code)

        val cats = json.optJSONArray("ng_categories") ?: JSONArray()
        ngOptions = (0 until cats.length()).mapNotNull { i ->
            cats.optJSONObject(i)?.let { c -> PickOption(c.optInt("id").toString(), "${c.optString("code")} — ${c.optString("name")}") }
        }
        val stepArr = json.optJSONArray("steps") ?: JSONArray()
        posOptions = (0 until stepArr.length()).mapNotNull { i ->
            stepArr.optJSONObject(i)?.let { s -> PickOption(s.optInt("order").toString(), "${s.optInt("order")}. ${s.optString("label")}") }
        }

        // Kepala
        val head = card()
        head.addView(label(plan.str("lot_id") ?: "—", 22f, Color.parseColor("#1A6FC4"), true))
        head.addView(label(plan.str("product") ?: "—", 16f, bold = true))
        head.addView(label("Proses: " + json.optString("stage_label") + (json.str("subcont_name")?.let { " · Subcont $it" } ?: ""),
            15f, Color.parseColor("#92400E"), true).apply { setPadding(0, dp(6), 0, 0) })
        val status = json.optString("status")
        val statusText = when (status) {
            "approved" -> "Sudah di-approve — hanya bisa dilihat."
            "submitted" -> "Sudah disubmit — menunggu approval Admin."
            "rejected" -> "Ditolak Admin — perbaiki lalu submit ulang."
            else -> "Draft — belum disubmit."
        }
        head.addView(label(statusText, 13f, when (status) {
            "approved" -> Color.parseColor("#166534")
            "rejected" -> Color.parseColor("#DC2626")
            else -> Color.parseColor("#6B7280")
        }).apply { setPadding(0, dp(4), 0, 0) })
        val notes = json.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until notes.length()) {
            head.addView(label("• " + notes.optString(i), 12f, Color.parseColor("#92400E")).apply { setPadding(0, dp(4), 0, 0) })
        }

        // Pilih stage (Plan dengan Assembly & Inspection Manual).
        val stages = json.optJSONArray("stages") ?: JSONArray()
        if (stages.length() > 1) {
            val opts = mutableListOf<PickOption>()
            for (i in 0 until stages.length()) {
                val s = stages.optJSONObject(i) ?: continue
                val extra = if (s.optBoolean("available")) "" else " (belum bisa)"
                opts += PickOption(s.optString("key"), s.optString("label") + extra)
            }
            val b = pickButton("Ganti proses: " + json.optString("stage_label"))
            b.tag = KEEP_ENABLED
            b.setOnClickListener {
                PickerDialog.show(this, "Pilih proses", opts) { pick ->
                    val s = (0 until stages.length()).mapNotNull { stages.optJSONObject(it) }.firstOrNull { it.optString("key") == pick.value }
                    if (s != null && !s.optBoolean("available")) {
                        showMessage("Belum bisa dibuka", s.optString("reason"))
                    } else if (pick.value != stage) {
                        stage = pick.value
                        load()
                    }
                }
            }
            head.addView(b)
        }

        // Terapkan tanggal/shift ke sub-proses yang belum tersimpan.
        if (!locked) {
            val box = card(Color.parseColor("#EFF6FF"))
            box.addView(label("Isi cepat sub-proses yang belum tersimpan", 13f, Color.parseColor("#1E40AF"), true))
            var applyDate = today
            var applyShift = ""
            val dBtn = pickButton(showDate(applyDate))
            val sBtn = pickButton("Shift")
            dBtn.setOnClickListener { pickDate(applyDate) { d -> applyDate = d; dBtn.text = showDate(d) } }
            sBtn.setOnClickListener {
                PickerDialog.show(this, "Shift", listOf(PickOption("1", "Shift 1"), PickOption("2", "Shift 2"))) { p ->
                    applyShift = p.value; sBtn.text = p.label
                }
            }
            val apply = smallButton("Terapkan")
            apply.setOnClickListener {
                for (st in steps) {
                    if (st.saved) continue
                    if (applyDate.isNotBlank()) { st.workDate = applyDate; st.dateBtn.text = showDate(applyDate) }
                    if (applyShift.isNotBlank()) { st.shift = applyShift; st.shiftBtn.text = "Shift $applyShift" }
                }
            }
            box.addView(row(weighted(dBtn, 1.3f, dp(6)), weighted(sBtn, 1f, dp(6)), apply))
        }

        // Sub-proses
        content.addView(label("1. Sub-proses", 16f, bold = true).apply { setPadding(dp(4), dp(6), 0, dp(6)) })
        for (i in 0 until stepArr.length()) {
            val s = stepArr.optJSONObject(i) ?: continue
            buildStep(s)
        }

        // Hasil
        content.addView(label("2. Hasil", 16f, bold = true).apply { setPadding(dp(4), dp(6), 0, dp(6)) })
        val res = card(Color.parseColor("#F0FDF4"))
        res.addView(label("Qty OK stage sebelumnya: " + (startQty?.let { fmt(it) + " pcs" } ?: "—"), 13f, Color.parseColor("#374151")))
        res.addView(label("Total Check OK", 13f, Color.parseColor("#166534"), true).apply { setPadding(0, dp(8), 0, 0) })
        checkInput = numberInput(json.str("qty_check"), false, "Jumlah yang diperiksa").also {
            afterChange(it) { recalc() }
            res.addView(it)
        }
        res.addView(label("NG per kategori & posisi (sub-proses tempat NG ditemukan)", 13f, Color.parseColor("#B91C1C"), true).apply { setPadding(0, dp(10), 0, dp(2)) })
        ngBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        res.addView(ngBox)
        val ng = json.optJSONArray("ng") ?: JSONArray()
        for (i in 0 until ng.length()) {
            val n = ng.optJSONObject(i) ?: continue
            addNgRow(n.optInt("position").toString(), n.optInt("category_id").toString(), n.optInt("qty"))
        }
        if (!locked) {
            val add = smallButton("+ Tambah NG")
            add.setOnClickListener { addNgRow("", "", 0) }
            res.addView(add)
        }
        summary = label("", 15f, bold = true).apply { setPadding(0, dp(8), 0, 0) }
        res.addView(summary)

        if (locked) {
            findViewById<View>(R.id.actions).visibility = View.GONE
            disableAll(content)
        } else {
            findViewById<View>(R.id.actions).visibility = View.VISIBLE
            setButtons(true)
        }
        recalc()
        scroll.scrollTo(0, 0)
    }

    private fun buildStep(s: JSONObject) {
        val v = s.optJSONObject("values") ?: JSONObject()
        val st = StepRow(s.optInt("order"), s.optString("label"), s.optBoolean("filled"), s.optBoolean("needs_machine"))
        val box = card(if (st.saved) Color.parseColor("#F8FFFA") else Color.WHITE)

        st.done = CheckBox(this).apply {
            text = "${st.order}. ${st.label}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            isChecked = st.saved
            isEnabled = !st.saved
        }
        val savedTag = label(if (st.saved) "✓ tersimpan" else "", 12f, Color.parseColor("#16A34A"))
        box.addView(row(weighted(st.done, 1f), savedTag))
        val touch = { if (!st.done.isChecked) st.done.isChecked = true }

        st.workDate = v.str("work_date") ?: ""
        st.shift = v.str("shift") ?: ""
        st.dateBtn = pickButton(if (st.workDate.isBlank()) "Tanggal" else showDate(st.workDate))
        st.dateBtn.setOnClickListener {
            pickDate(st.workDate.ifBlank { today }) { d -> st.workDate = d; st.dateBtn.text = showDate(d); touch() }
        }
        st.shiftBtn = pickButton(if (st.shift.isBlank()) "Shift" else "Shift ${st.shift}")
        st.shiftBtn.setOnClickListener {
            PickerDialog.show(this, "Shift", listOf(PickOption("1", "Shift 1"), PickOption("2", "Shift 2"))) { p ->
                st.shift = p.value; st.shiftBtn.text = p.label; touch()
            }
        }
        box.addView(row(weighted(st.dateBtn, 1.4f, dp(6)), weighted(st.shiftBtn, 1f)))

        st.nik = numberInput(v.str("operator_nik"), false, "NIK (4 angka)").apply {
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        afterChange(st.nik) { if (st.nik.text.isNotEmpty()) touch() }
        box.addView(st.nik)

        if (st.needsMachine) {
            if (subcont) {
                st.machineText = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine()
                    hint = "Mesin"
                    setText(v.str("machine_text") ?: "")
                }
                afterChange(st.machineText!!) { if (st.machineText!!.text.isNotEmpty()) touch() }
                box.addView(st.machineText)
            } else {
                val ms = s.optJSONArray("machines") ?: JSONArray()
                val opts = (0 until ms.length()).mapNotNull { i -> ms.optJSONObject(i)?.let { PickOption(it.optInt("id").toString(), it.optString("name")) } }
                st.lineId = v.str("line_id") ?: ""
                val btn = pickButton(opts.firstOrNull { it.value == st.lineId }?.label ?: if (opts.isEmpty()) "(belum ada mesin)" else "Pilih mesin")
                btn.setOnClickListener {
                    if (opts.isEmpty()) showMessage("Mesin", "Belum ada mesin terdaftar untuk sub-proses ${st.label}. Hubungi Admin.")
                    else PickerDialog.show(this, "Mesin — ${st.label}", opts) { p -> st.lineId = p.value; btn.text = p.label; touch() }
                }
                st.machineBtn = btn
                box.addView(btn)
            }
        }

        val params = s.optJSONArray("params") ?: JSONArray()
        for (i in 0 until params.length()) {
            val p = params.optJSONObject(i) ?: continue
            val field = p.optString("field")
            val type = p.optString("type")
            val unit = p.optString("unit")
            val raw = v.str(field)
            val e = if (type == "lot") {
                EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    filters = arrayOf(InputFilter.LengthFilter(10), InputFilter.AllCaps())
                    setSingleLine()
                    setText(raw ?: "")
                }
            } else {
                numberInput(raw?.let { if (type == "decimal") fmtNum(it.toDoubleOrNull() ?: 0.0) else it }, type == "decimal")
            }
            e.hint = p.optString("label") + if (unit.isNotBlank()) " ($unit)" else ""
            afterChange(e) { if (e.text.isNotEmpty()) touch() }
            st.params[field] = e
            box.addView(label(e.hint.toString(), 11f, Color.parseColor("#6B7280")).apply { setPadding(0, dp(4), 0, 0) })
            box.addView(e)
        }
        steps += st
    }

    private fun pickDate(current: String, onPick: (String) -> Unit) {
        val c = Calendar.getInstance()
        if (current.length == 10) {
            c.set(current.substring(0, 4).toInt(), current.substring(5, 7).toInt() - 1, current.substring(8, 10).toInt())
        }
        val dlg = DatePickerDialog(this, { _, y, m, d ->
            onPick(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.show()
    }

    private fun addNgRow(position: String, categoryId: String, qty: Int) {
        val box = ngBox ?: return
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        val posBtn = pickButton(posOptions.firstOrNull { it.value == position }?.label ?: "Pilih posisi (sub-proses)")
        val catBtn = pickButton(ngOptions.firstOrNull { it.value == categoryId }?.label ?: "Pilih kategori NG")
        val qtyIn = numberInput(if (qty > 0) qty.toString() else "", false, "Qty NG")
        val del = smallButton("Hapus").apply { setTextColor(Color.parseColor("#DC2626")) }
        val r = NgRow(wrap, qtyIn, posBtn, catBtn)
        r.position = position
        r.categoryId = categoryId
        posBtn.setOnClickListener {
            PickerDialog.show(this, "Posisi NG", posOptions) { p -> r.position = p.value; posBtn.text = p.label }
        }
        catBtn.setOnClickListener {
            PickerDialog.show(this, "Kategori NG", ngOptions) { p -> r.categoryId = p.value; catBtn.text = p.label }
        }
        afterChange(qtyIn) { recalc() }
        del.setOnClickListener {
            box.removeView(wrap)
            ngRows.remove(r)
            recalc()
        }
        wrap.addView(posBtn)
        wrap.addView(catBtn)
        wrap.addView(row(weighted(qtyIn, 1f, dp(6)), del))
        box.addView(wrap)
        ngRows += r
        recalc()
    }

    private fun ngTotal(): Int = ngRows.sumOf { it.qty.text.toString().trim().toIntOrNull() ?: 0 }

    private fun recalc() {
        val s = summary ?: return
        val ng = ngTotal()
        val check = checkInput?.text?.toString()?.trim()?.toIntOrNull()
        val sb = StringBuilder("Total NG ${fmt(ng)}")
        var bad = false
        if (check == null) {
            sb.append(" · Qty OK —")
        } else {
            val ok = check - ng
            sb.append(" · Qty OK ${fmt(ok)}")
            if (ok < 0) { sb.append("\nTotal NG melebihi Total Check OK."); bad = true }
            val start = startQty
            if (start != null && check > start) { sb.append("\nTotal Check OK melebihi Qty OK stage sebelumnya (${fmt(start)})."); bad = true }
        }
        s.text = sb.toString()
        s.setTextColor(if (bad) Color.parseColor("#DC2626") else Color.parseColor("#166534"))
    }

    private fun disableAll(v: View) {
        if (v is ViewGroup) for (i in 0 until v.childCount) disableAll(v.getChildAt(i))
        if ((v is EditText || v is MaterialButton || v is CheckBox) && v.tag != KEEP_ENABLED) v.isEnabled = false
    }

    // ── Simpan / Submit ───────────────────────────────────────────────
    private fun buildBody(submit: Boolean): Pair<JSONObject?, String?> {
        val body = JSONObject().put("stage", stage).put("submit", submit)
        val stepsJson = JSONObject()
        for (st in steps) {
            val o = JSONObject()
                .put("done", st.done.isChecked)
                .put("work_date", st.workDate.ifBlank { null } ?: JSONObject.NULL)
                .put("shift", st.shift.ifBlank { null } ?: JSONObject.NULL)
                .put("operator_nik", st.nik.text.toString().trim())
            if (st.needsMachine) {
                if (subcont) o.put("machine_text", st.machineText?.text?.toString()?.trim().orEmpty())
                else o.put("line_id", st.lineId.ifBlank { null } ?: JSONObject.NULL)
            }
            for ((field, e) in st.params) {
                val v = e.text.toString().trim().replace(',', '.')
                o.put(field, if (v.isEmpty()) JSONObject.NULL else v)
            }
            stepsJson.put(st.order.toString(), o)
        }
        body.put("steps", stepsJson)
        val check = checkInput?.text?.toString()?.trim().orEmpty()
        body.put("qty_check", if (check.isEmpty()) JSONObject.NULL else check.toIntOrNull() ?: return null to "Total Check OK harus angka.")
        val ng = JSONArray()
        for (r in ngRows) {
            val q = r.qty.text.toString().trim().toIntOrNull() ?: 0
            if (r.position.isBlank()) return null to "NG: pilih Posisi (sub-proses) untuk setiap baris NG (atau hapus barisnya)."
            if (r.categoryId.isBlank()) return null to "NG: pilih kategori untuk setiap baris NG (atau hapus barisnya)."
            if (q <= 0) return null to "NG: qty NG wajib diisi (lebih dari 0)."
            ng.put(JSONObject().put("position", r.position.toInt()).put("category_id", r.categoryId.toInt()).put("qty", q))
        }
        body.put("ng", ng)
        return body to null
    }

    private fun confirmSubmit() {
        val (body, err) = buildBody(true)
        if (body == null) {
            showMessage("Belum lengkap", err ?: "Periksa isian.")
            return
        }
        val notDone = steps.filter { !it.done.isChecked }.map { "${it.order}. ${it.label}" }
        if (notDone.isNotEmpty()) {
            showMessage("Belum lengkap", "Sub-proses berikut belum dicentang Selesai:\n" + notDone.joinToString("\n"))
            return
        }
        if (checkInput?.text.isNullOrBlank()) {
            showMessage("Belum lengkap", "Total Check OK wajib diisi.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Simpan & Submit")
            .setMessage("Total Check OK ${checkInput?.text}\n${summary?.text}\n\nKirim ke Admin untuk approval?")
            .setPositiveButton("Kirim") { _, _ -> send(body) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun save(submit: Boolean) {
        val (body, err) = buildBody(submit)
        if (body == null) {
            showMessage("Belum lengkap", err ?: "Periksa isian.")
            return
        }
        send(body)
    }

    private fun send(body: JSONObject) {
        setButtons(false)
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = api.call("POST", "/api/mobile/manual/" + Uri.encode(code), body)
            progress.visibility = View.GONE
            setButtons(true)
            when (r) {
                is ApiResult.Response -> when {
                    r.ok -> {
                        val submitted = r.json.optBoolean("submitted")
                        MaterialAlertDialogBuilder(this@ManualInputActivity)
                            .setTitle(if (submitted) "Terkirim" else "Tersimpan")
                            .setMessage(r.message)
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ -> if (submitted) finish() else load() }
                            .show()
                    }
                    r.code == 401 -> goToLogin(r.message)
                    r.json.optBoolean("saved") -> {
                        MaterialAlertDialogBuilder(this@ManualInputActivity)
                            .setTitle("Tersimpan, belum disubmit")
                            .setMessage(r.message)
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ -> load() }
                            .show()
                    }
                    else -> showMessage("Belum tersimpan", errorDetail(r.json, r.message))
                }
                is ApiResult.UntrustedCert -> askTrustCertificate(r) { send(body) }
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

    companion object {
        private const val KEEP_ENABLED = "keep_enabled"
    }
}
