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
 * Lot Material Assembly (sama dengan web): POST …/{kode}/lots, pilih/ganti
 * BOM (Admin): POST …/{kode}/bom. Lot yang belum disimpan ikut tersimpan
 * saat menekan Simpan / Simpan & Submit.
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

    // Preset Line: Line terpilih (untuk dropdown operator inti/backup).
    private var currentLine: JSONObject? = null
    private var linePresets: JSONArray = JSONArray()
    private var lineBtn: MaterialButton? = null

    // Lot Material (Assembly)
    private val lotGroups = mutableListOf<LotGroup>()
    private var lotsDirty = false
    private var maxLots = 10
    private var lotSaveBtn: MaterialButton? = null
    private var lotEditBtn: MaterialButton? = null

    private inner class LotGroup(val detailId: Int, val box: LinearLayout, val addBtn: MaterialButton) {
        val inputs = mutableListOf<EditText>()
    }

    private inner class StepRow(val order: Int, val label: String, val saved: Boolean, val needsMachine: Boolean) {
        lateinit var done: CheckBox
        var workDate: String = ""
        var shift: String = ""
        var lineId: String = ""
        lateinit var dateBtn: MaterialButton
        lateinit var shiftBtn: MaterialButton
        var machineBtn: MaterialButton? = null
        var machineOptions: List<PickOption> = emptyList()
        var nikPick: MaterialButton? = null
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
        steps.clear(); ngRows.clear(); lotGroups.clear()
        linePresets = if (subcontOf(json)) JSONArray() else (json.optJSONArray("line_presets") ?: JSONArray())
        currentLine = null
        lineBtn = null
        lotsDirty = false
        lotSaveBtn = null; lotEditBtn = null

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
            // Preset Line (23 Nov 2026): pilih Line → NIK & mesin sub-proses
            // terisi dari Preset Line (diatur Admin di web), sub-proses yang
            // punya NIK di preset otomatis dicentang Selesai.
            val presets = linePresets
            val lBtn = pickButton("Line")
            lineBtn = lBtn
            val lineOpts = (0 until presets.length()).mapNotNull { i -> presets.optJSONObject(i)?.optString("name")?.let { PickOption(it, it) } }
            lBtn.setOnClickListener {
                PickerDialog.show(this, "Line", listOf(PickOption("", "— Tanpa Line —")) + lineOpts) { p ->
                    setCurrentLine((0 until presets.length()).mapNotNull { presets.optJSONObject(it) }.firstOrNull { it.optString("name") == p.value })
                }
            }
            val apply = smallButton("Terapkan")
            apply.setOnClickListener {
                val byOrder = presetByOrder(currentLine)
                for (st in steps) {
                    if (st.saved) continue
                    if (applyDate.isNotBlank()) { st.workDate = applyDate; st.dateBtn.text = showDate(applyDate) }
                    if (applyShift.isNotBlank()) { st.shift = applyShift; st.shiftBtn.text = "Shift $applyShift" }
                    val pr = byOrder[st.order] ?: continue
                    val nik = pr.str("operator_nik")
                    if (!nik.isNullOrBlank()) { st.nik.setText(nik); st.done.isChecked = true } // operator inti
                    val lineId = if (pr.isNull("line_id")) "" else pr.optInt("line_id").toString()
                    val opt = st.machineOptions.firstOrNull { it.value == lineId }
                    if (opt != null) { st.lineId = opt.value; st.machineBtn?.text = opt.label }
                }
            }
            if (lineOpts.isNotEmpty() && !subcont) {
                box.addView(row(weighted(dBtn, 1.3f, dp(6)), weighted(sBtn, 1f)))
                box.addView(row(weighted(lBtn, 1.3f, dp(6)), weighted(apply, 1f)))
            } else {
                box.addView(row(weighted(dBtn, 1.3f, dp(6)), weighted(sBtn, 1f, dp(6)), apply))
            }
        }

        // Lot Material (Assembly saja)
        json.optJSONObject("materials")?.let { buildMaterials(it) }

        // Sub-proses
        content.addView(label("1. Sub-proses", 16f, bold = true).apply { setPadding(dp(4), dp(6), 0, dp(6)) })
        for (i in 0 until stepArr.length()) {
            val s = stepArr.optJSONObject(i) ?: continue
            buildStep(s)
        }

        // Tebak Line dari mesin yang sudah tersimpan → dropdown operator siap.
        if (currentLine == null && linePresets.length() > 0) {
            var best: JSONObject? = null
            var bestHits = 0
            for (i in 0 until linePresets.length()) {
                val ln = linePresets.optJSONObject(i) ?: continue
                val byOrder = presetByOrder(ln)
                val hits = steps.count { st ->
                    val pr = byOrder[st.order]
                    st.lineId.isNotBlank() && pr != null && !pr.isNull("line_id") && pr.optInt("line_id").toString() == st.lineId
                }
                if (hits > bestHits) { best = ln; bestHits = hits }
            }
            setCurrentLine(best)
        } else {
            refreshNikPicks()
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

    // ── Lot Material ──────────────────────────────────────────────────
    private fun buildMaterials(m: JSONObject) {
        val matLocked = m.optBoolean("locked") || locked
        maxLots = m.optInt("max_lots", 10)
        val box = card(Color.parseColor("#F5F3FF"))
        box.addView(label("Lot Material", 16f, Color.parseColor("#5B21B6"), true))

        val bom = m.optJSONObject("bom")
        if (bom != null) {
            box.addView(label("BOM: ${bom.optString("code")} — ${bom.optString("name")}", 13f, Color.parseColor("#374151")).apply { setPadding(0, dp(2), 0, dp(4)) })
        } else {
            box.addView(label(m.optString("message"), 13f, Color.parseColor("#DC2626")).apply { setPadding(0, dp(2), 0, dp(4)) })
        }

        // Pilih / ganti BOM (Admin saja).
        val opts = m.optJSONArray("bom_options") ?: JSONArray()
        if (m.optBoolean("can_choose_bom") && opts.length() > 0) {
            val list = (0 until opts.length()).mapNotNull { i ->
                opts.optJSONObject(i)?.let { PickOption(it.optInt("id").toString(), "${it.optString("code")} — ${it.optString("name")}") }
            }
            val b = pickButton(if (bom == null) "Pilih BOM Assembly" else "Ganti BOM Assembly")
            b.setOnClickListener {
                PickerDialog.show(this, "BOM Assembly", list) { p ->
                    if (bom != null && p.value == bom.optInt("id").toString()) return@show
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (bom == null) "Pilih BOM" else "Ganti BOM")
                        .setMessage("Set BOM Assembly ke ${p.label}?" +
                            (if (bom != null) "\n\nLot material milik BOM lama akan dihapus." else "") +
                            "\n\nIsian lain di halaman ini yang belum disimpan akan hilang.")
                        .setPositiveButton("Ya") { _, _ -> postMaterial("bom", JSONObject().put("bom_assembly_id", p.value.toInt()), "BOM") }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
            box.addView(b)
        }

        val details = m.optJSONArray("details") ?: JSONArray()
        if (details.length() == 0) return
        var anySaved = false
        for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            box.addView(label("${d.optString("item_code")}  ${d.optString("item_name")}", 13f, Color.parseColor("#1A6FC4"), true)
                .apply { setPadding(0, dp(10), 0, dp(2)) })
            // Coil hasil Winding: lot = No. IPCS, otomatis (tidak bisa diubah).
            if (d.optBoolean("auto")) {
                val lots = d.optJSONArray("lots") ?: JSONArray()
                val v = if (lots.length() > 0) lots.optString(0) else code
                box.addView(EditText(this).apply {
                    setText(v)
                    isEnabled = false
                    setSingleLine()
                    tag = "auto_lot"
                })
                box.addView(label("Lot otomatis dari No. IPCS", 11f, Color.parseColor("#6B7280")))
                continue
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(col)
            val add = smallButton("+ Tambah lot")
            val g = LotGroup(d.optInt("id"), col, add)
            val lots = d.optJSONArray("lots") ?: JSONArray()
            if (lots.length() > 0) anySaved = true
            for (j in 0 until lots.length()) addLotInput(g, lots.optString(j))
            if (lots.length() == 0) addLotInput(g, "")
            add.setOnClickListener {
                if (g.inputs.size < maxLots) { addLotInput(g, "").requestFocus(); lotsDirty = true }
                syncLotAdd(g)
            }
            box.addView(add)
            lotGroups += g
            syncLotAdd(g)
        }

        if (matLocked) {
            lotGroups.forEach { g -> g.addBtn.visibility = View.GONE; g.inputs.forEach { it.isEnabled = false } }
            setLotRemoveVisible(false)
            return
        }
        if (lotGroups.isEmpty()) return // hanya Coil (lot otomatis)
        // Sama dengan web: lot yang sudah tersimpan dikunci dulu → tombol Edit.
        val save = MaterialButton(this).apply { text = "Simpan Lot"; isAllCaps = false }
        val edit = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Edit Lot"; isAllCaps = false }
        save.setOnClickListener { saveLotsOnly() }
        edit.setOnClickListener { setLotsEditable(true) }
        box.addView(row(weighted(edit, 1f, dp(6)), weighted(save, 1f)))
        lotSaveBtn = save
        lotEditBtn = edit
        setLotsEditable(!anySaved)
    }

    private fun addLotInput(g: LotGroup, value: String): EditText {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(100))
            setSingleLine()
            hint = "Nomor lot"
            setText(value)
        }
        afterChange(e) { lotsDirty = true }
        val del = smallButton("Hapus").apply {
            setTextColor(Color.parseColor("#DC2626"))
            tag = "lot_remove"
        }
        val line = row(weighted(e, 1f, dp(6)), del)
        del.setOnClickListener {
            if (g.inputs.size <= 1) { e.setText("") } else {
                g.box.removeView(line)
                g.inputs.remove(e)
            }
            lotsDirty = true
            syncLotAdd(g)
        }
        g.box.addView(line)
        g.inputs += e
        return e
    }

    private fun syncLotAdd(g: LotGroup) {
        g.addBtn.isEnabled = g.inputs.size < maxLots && g.inputs.firstOrNull()?.isEnabled != false
    }

    private fun setLotRemoveVisible(visible: Boolean) {
        for (g in lotGroups) for (i in 0 until g.box.childCount) {
            val line = g.box.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until line.childCount) {
                val v = line.getChildAt(j)
                if (v.tag == "lot_remove") v.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setLotsEditable(editable: Boolean) {
        lotGroups.forEach { g -> g.inputs.forEach { it.isEnabled = editable }; g.addBtn.visibility = if (editable) View.VISIBLE else View.GONE; syncLotAdd(g) }
        setLotRemoveVisible(editable)
        lotSaveBtn?.visibility = if (editable) View.VISIBLE else View.GONE
        lotEditBtn?.visibility = if (editable) View.GONE else View.VISIBLE
    }

    private fun lotsBody(): JSONObject {
        val lots = JSONObject()
        for (g in lotGroups) {
            val arr = JSONArray()
            g.inputs.map { it.text.toString().trim() }.filter { it.isNotEmpty() }.distinct().forEach { arr.put(it) }
            lots.put(g.detailId.toString(), arr)
        }
        return JSONObject().put("lots", lots)
    }

    private fun saveLotsOnly() = postMaterial("lots", lotsBody(), "Lot material")

    /** POST …/lots atau …/bom, lalu muat ulang form. */
    private fun postMaterial(path: String, body: JSONObject, what: String) {
        setButtons(false)
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = api.call("POST", "/api/mobile/manual/" + Uri.encode(code) + "/" + path, body)
            progress.visibility = View.GONE
            setButtons(true)
            when (r) {
                is ApiResult.Response -> when {
                    r.ok -> MaterialAlertDialogBuilder(this@ManualInputActivity)
                        .setTitle("Tersimpan")
                        .setMessage(r.message)
                        .setCancelable(false)
                        .setPositiveButton("OK") { _, _ -> load() }
                        .show()
                    r.code == 401 -> goToLogin(r.message)
                    else -> showMessage("$what belum tersimpan", errorDetail(r.json, r.message))
                }
                is ApiResult.UntrustedCert -> askTrustCertificate(r) { postMaterial(path, body, what) }
                is ApiResult.NetworkError -> showMessage("$what belum tersimpan", r.message)
            }
        }
    }

    private fun subcontOf(json: JSONObject): Boolean = json.optBoolean("subcont")

    private fun presetByOrder(line: JSONObject?): Map<Int, JSONObject> {
        val out = mutableMapOf<Int, JSONObject>()
        line?.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out[it.optInt("order")] = it }
        }
        return out
    }

    private fun setCurrentLine(line: JSONObject?) {
        currentLine = line
        lineBtn?.text = line?.optString("name") ?: "Line"
        refreshNikPicks()
    }

    /** Tombol dropdown operator tampil bila Line terpilih punya operator untuk sub-proses itu. */
    private fun refreshNikPicks() {
        val byOrder = presetByOrder(currentLine)
        for (st in steps) {
            val n = byOrder[st.order]?.optJSONArray("operators")?.length() ?: 0
            st.nikPick?.visibility = if (n > 0 && !locked) View.VISIBLE else View.GONE
        }
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
        }
        // Sub-proses opsional (20 Nov 2026): tidak semua produk melewati semua
        // sub-proses. Yang tidak dicentang = tidak dilalui; centang sub-proses
        // yang sudah tersimpan boleh dihapus (isinya dikosongkan saat disimpan).
        if (st.saved) {
            st.done.setOnCheckedChangeListener { cb, checked ->
                if (checked || locked) return@setOnCheckedChangeListener
                MaterialAlertDialogBuilder(this)
                    .setTitle("Tidak dilalui?")
                    .setMessage("Sub-proses \"${st.order}. ${st.label}\" sudah tersimpan.\n\nHapus centang = sub-proses ini dianggap TIDAK DILALUI dan isinya dikosongkan saat disimpan.")
                    .setPositiveButton("Ya, hapus centang", null)
                    .setNegativeButton("Batal") { _, _ -> cb.isChecked = true }
                    .setOnCancelListener { cb.isChecked = true }
                    .show()
            }
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
        // Dropdown operator inti / backup dari Preset Line.
        val pickBtn = smallButton("▼ Operator")
        pickBtn.visibility = View.GONE
        pickBtn.setOnClickListener {
            val ops = presetByOrder(currentLine)[st.order]?.optJSONArray("operators") ?: JSONArray()
            val opts = (0 until ops.length()).mapNotNull { i ->
                ops.optJSONObject(i)?.let { PickOption(it.optString("nik"), it.optString("nik") + " · " + it.optString("role")) }
            }
            if (opts.isNotEmpty()) PickerDialog.show(this, "Operator — ${st.label}", opts) { p -> st.nik.setText(p.value) }
        }
        st.nikPick = pickBtn
        box.addView(row(weighted(st.nik, 1f, dp(6)), pickBtn))

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
                st.machineOptions = opts
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
            val posStep = steps.firstOrNull { it.order.toString() == r.position }
            if (posStep != null && !posStep.done.isChecked) {
                return null to "NG di posisi ${posStep.order}. ${posStep.label}, padahal sub-proses itu tidak dicentang Selesai. Centang sub-prosesnya atau ganti posisi NG."
            }
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
        if (notDone.size == steps.size) {
            showMessage("Belum lengkap", "Centang Selesai minimal 1 sub-proses.")
            return
        }
        if (checkInput?.text.isNullOrBlank()) {
            showMessage("Belum lengkap", "Total Check OK wajib diisi.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Simpan & Submit")
            .setMessage("Total Check OK ${checkInput?.text}\n${summary?.text}" +
                (if (notDone.isNotEmpty()) "\n\nTidak dilalui (tidak dicentang):\n" + notDone.joinToString("\n") else "") +
                "\n\nKirim ke Admin untuk approval?")
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
            // Lot material yang diubah tapi belum disimpan ikut disimpan dulu.
            if (lotsDirty && lotGroups.isNotEmpty() && lotSaveBtn?.visibility == View.VISIBLE) {
                val lr = api.call("POST", "/api/mobile/manual/" + Uri.encode(code) + "/lots", lotsBody())
                if (!(lr is ApiResult.Response && lr.ok)) {
                    progress.visibility = View.GONE
                    setButtons(true)
                    val msg = when (lr) {
                        is ApiResult.Response -> errorDetail(lr.json, lr.message)
                        is ApiResult.NetworkError -> lr.message
                        is ApiResult.UntrustedCert -> "Sertifikat server belum dipercaya."
                    }
                    showMessage("Lot material belum tersimpan", msg + "\n\nIsian lain belum dikirim.")
                    return@launch
                }
                lotsDirty = false
            }
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
