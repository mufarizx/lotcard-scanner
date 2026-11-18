package com.sanwa.lotcard.scanner

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Satu pilihan: value = yang dikirim ke server, label = yang tampil. */
data class PickOption(val value: String, val label: String)

/**
 * Dialog pilih dengan kotak cari (kategori NG ±266, operator, lot material).
 * Pencarian: semua kata yang diketik harus ada di label (huruf besar/kecil diabaikan).
 */
object PickerDialog {
    fun show(activity: Activity, title: String, options: List<PickOption>, onPick: (PickOption) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val search = EditText(activity).apply {
            hint = "Cari…"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val empty = TextView(activity).apply {
            text = "Tidak ada yang cocok."
            setPadding(0, pad / 2, 0, pad / 2)
            visibility = android.view.View.GONE
        }
        val shown = options.toMutableList()
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, shown.map { it.label }.toMutableList())
        val list = ListView(activity).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * density).toInt())
        }
        box.addView(search)
        box.addView(empty)
        box.addView(list)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Batal", null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            if (position in shown.indices) {
                onPick(shown[position])
                dialog.dismiss()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val words = s?.toString().orEmpty().lowercase().split(' ').filter { it.isNotBlank() }
                shown.clear()
                shown.addAll(options.filter { o -> val l = o.label.lowercase(); words.all { l.contains(it) } })
                adapter.clear()
                adapter.addAll(shown.map { it.label })
                adapter.notifyDataSetChanged()
                empty.visibility = if (shown.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        })
        dialog.show()
    }
}
