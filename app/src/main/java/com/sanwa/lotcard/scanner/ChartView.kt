package com.sanwa.lotcard.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Grafik Dashboard (25 Nov 2026), meniru grafik Dashboard web:
 * - mode BAR  (rentang 1 hari, per produk): batang Plan (oranye), OK (hijau), NG (merah) per produk;
 *   ketuk batang → [onPointClick] (filter produk).
 * - mode LINE (tren tanggal/bulan): garis OK & NG.
 * Lebar view = jumlah titik × lebar slot (dibungkus HorizontalScrollView bila tidak muat).
 */
class ChartView(context: Context) : View(context) {
    data class Point(val label: String, val ok: Int, val ng: Int, val plan: Int?)

    var points: List<Point> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var barMode = true
        set(v) { field = v; requestLayout(); invalidate() }
    var onPointClick: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val axisW = 44 * d
    private val bottomH = 46 * d
    private val topPad = 10 * d
    private val chartH = 240 * d

    private val cPlan = Color.parseColor("#D97706")
    private val cOk = Color.parseColor("#1A7A4A")
    private val cNg = Color.parseColor("#B91C1C")

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E5E7EB"); strokeWidth = 1 * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6B7280"); textSize = 10 * d }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun slotW(): Float = if (barMode) 64 * d else 46 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (axisW + slotW() * max(points.size, 1) + 8 * d).toInt()
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) max(wanted, minWidthPx) else max(wanted, parentW)
        setMeasuredDimension(w, (topPad + chartH + bottomH).toInt())
    }

    /** Lebar minimum (lebar layar) saat berada di dalam HorizontalScrollView. */
    var minWidthPx = 0

    private fun maxValue(): Int {
        var m = 0
        for (p in points) m = maxOf(m, p.ok, p.ng, p.plan ?: 0)
        return m
    }

    private fun niceMax(v: Int): Float {
        if (v <= 0) return 10f
        val mag = 10.0.pow(ceil(log10(v.toDouble())) - 1)
        val steps = listOf(1.0, 2.0, 2.5, 5.0, 10.0)
        for (s in steps) { val top = s * mag * 5; if (top >= v) return top.toFloat() }
        return (10 * mag * 5).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = topPad
        val bottom = topPad + chartH
        val vmax = niceMax(maxValue())
        // Grid + angka sumbu Y (5 garis).
        for (i in 0..5) {
            val v = vmax * i / 5
            val y = bottom - chartH * i / 5
            canvas.drawLine(axisW, y, width.toFloat(), y, grid)
            val s = if (v >= 1000) String.format("%.1fk", v / 1000).replace(".0k", "k") else v.toInt().toString()
            canvas.drawText(s, axisW - text.measureText(s) - 4 * d, y + 3 * d, text)
        }
        if (points.isEmpty()) {
            val s = "Belum ada data untuk periode ini."
            canvas.drawText(s, axisW + 12 * d, top + chartH / 2, text)
            return
        }
        val slot = slotW()
        fun yOf(v: Int): Float = bottom - chartH * (v / vmax)
        if (barMode) {
            val bw = (slot - 14 * d) / 3
            points.forEachIndexed { i, p ->
                val x0 = axisW + i * slot + 7 * d
                val series = listOf((p.plan ?: 0) to cPlan, p.ok to cOk, p.ng to cNg)
                series.forEachIndexed { k, (v, c) ->
                    if (v <= 0) return@forEachIndexed
                    bar.color = c
                    canvas.drawRect(x0 + k * bw, yOf(v), x0 + (k + 1) * bw - 1 * d, bottom, bar)
                }
            }
        } else {
            for ((sel, c) in listOf<Pair<(Point) -> Int, Int>>({ p: Point -> p.ok } to cOk, { p: Point -> p.ng } to cNg)) {
                val path = Path()
                val area = Path()
                points.forEachIndexed { i, p ->
                    val x = axisW + i * slot + slot / 2
                    val y = yOf(sel(p))
                    if (i == 0) { path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
                    if (i == points.size - 1) { area.lineTo(x, bottom); area.close() }
                }
                fill.color = Color.argb(28, Color.red(c), Color.green(c), Color.blue(c))
                canvas.drawPath(area, fill)
                line.color = c
                canvas.drawPath(path, line)
                fill.color = c
                points.forEachIndexed { i, p -> canvas.drawCircle(axisW + i * slot + slot / 2, yOf(sel(p)), 3 * d, fill) }
            }
        }
        // Label sumbu X (dipotong bila terlalu panjang, 2 baris).
        points.forEachIndexed { i, p ->
            val cx = axisW + i * slot + slot / 2
            val maxW = slot - 4 * d
            val lines = wrap(p.label, maxW)
            lines.forEachIndexed { li, s -> canvas.drawText(s, cx - text.measureText(s) / 2, bottom + (14 + li * 12) * d, text) }
        }
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        if (text.measureText(s) <= maxW) return listOf(s)
        var a = s
        while (a.length > 1 && text.measureText(a) > maxW) a = a.dropLast(1)
        var b = s.substring(a.length)
        if (text.measureText(b) > maxW) {
            while (b.length > 1 && text.measureText("$b…") > maxW) b = b.dropLast(1)
            b += "…"
        }
        return listOf(a, b)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cb = onPointClick ?: return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            val i = ((event.x - axisW) / slotW()).toInt()
            if (event.x >= axisW && i in points.indices) { performClick(); cb(i) }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
