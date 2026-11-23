package com.sanwa.lotcard.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

/**
 * CETAK QR ke printer thermal Bluetooth 58 mm (Iware MP-581RL = Rongta
 * RPP02N: ESC/POS, 203 dpi = 8 titik/mm, lebar cetak 48 mm = 384 titik).
 *
 * Kartu digambar SAMA dengan halaman web "Print Card" (label 30 × 20 mm):
 *   [kode stage + tanggal, diputar] [QR stage, diputar 90°] [produk + mesin, diputar]
 * lalu dikirim sebagai gambar raster ESC/POS (GS v 0) — cara yang didukung
 * hampir semua printer thermal ESC/POS, tanpa driver/aplikasi lain.
 */
object LabelRenderer {
    const val DOTS_PER_MM = 8
    const val CARD_W = 30 * DOTS_PER_MM // 240
    const val CARD_H = 20 * DOTS_PER_MM // 160
    const val PRINT_WIDTH = 384         // 48 mm

    /** Gambar 1 kartu 30 × 20 mm (240 × 160 titik), hitam-putih. */
    fun renderCard(card: JSONObject): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 11f
            textAlign = Paint.Align.CENTER
        }

        // Tata letak (titik) mengikuti print-card.blade.php: kotak isi 25,9 mm
        // di tengah label: teks stage 4,8 mm · jarak 0,5 · QR 17 mm · jarak 1 · teks produk 2,6 mm.
        val qrSize = 17 * DOTS_PER_MM // 136
        val left = 16
        val stageCx = left + 19f
        val qrX = left + 42
        val qrY = (CARD_H - qrSize) / 2
        val prodCx = (qrX + qrSize + 8 + 10).toFloat()
        val cy = CARD_H / 2f

        // Kiri: kode stage + tanggal (diputar -90°, dibaca dari bawah ke atas).
        drawRotatedLines(c, paint, listOf(card.optString("stage_code"), card.optString("date")), stageCx, cy)

        // Tengah: QR dari server (PNG yang sama dengan web), diputar 90°.
        val b64 = card.optString("qr_png")
        if (b64.isNotEmpty()) {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)?.let { qr ->
                val m = Matrix()
                m.postRotate(90f)
                val rotated = Bitmap.createBitmap(qr, 0, 0, qr.width, qr.height, m, true)
                val scaled = Bitmap.createScaledBitmap(rotated, qrSize, qrSize, true)
                c.drawBitmap(scaled, qrX.toFloat(), qrY.toFloat(), null)
            }
        }

        // Kanan: nama produk (+ mesin untuk stage Auto), diputar -90°.
        val lines = mutableListOf(card.optString("product"))
        if (!card.isNull("machine") && card.optString("machine").isNotBlank()) lines += card.optString("machine")
        drawRotatedLines(c, paint, lines, prodCx, cy)

        return threshold(bmp)
    }

    /** Kartu uji kalibrasi: bingkai 30 × 20 mm + tanda tengah. */
    fun renderTestCard(): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = false }
        c.drawRect(1f, 1f, CARD_W - 2f, CARD_H - 2f, p)
        c.drawLine(CARD_W / 2f, 10f, CARD_W / 2f, CARD_H - 10f, p)
        c.drawLine(10f, CARD_H / 2f, CARD_W - 10f, CARD_H / 2f, p)
        val t = Paint().apply {
            color = Color.BLACK; textSize = 16f; isAntiAlias = false; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        c.drawText("TES 30x20", CARD_W / 2f, CARD_H / 2f - 8f, t)
        return threshold(bmp)
    }

    private fun drawRotatedLines(c: Canvas, paint: Paint, lines: List<String>, cx: Float, cy: Float) {
        val lh = paint.textSize * 1.3f
        c.save()
        c.rotate(-90f, cx, cy)
        val top = cy - lh * lines.size / 2f
        lines.forEachIndexed { i, s ->
            c.drawText(s, cx, top + lh * (i + 1) - lh * 0.25f, paint)
        }
        c.restore()
    }

    private fun threshold(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) and 0xff
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val dark = a > 0 && lum < 150
            px[i] = if (dark) Color.BLACK else Color.WHITE
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Perintah ESC/POS untuk 1 kartu (beberapa salinan). */
    fun escPos(card: Bitmap, offsetMm: Float, topMm: Float, gapMm: Float, useGsFf: Boolean, copies: Int): ByteArray {
        val offset = (offsetMm * DOTS_PER_MM).roundToInt().coerceIn(0, PRINT_WIDTH - card.width)
        val top = (topMm * DOTS_PER_MM).roundToInt().coerceIn(0, 80)
        val widthBytes = PRINT_WIDTH / 8
        val height = card.height
        val raster = ByteArray(widthBytes * height)
        val px = IntArray(card.width * height)
        card.getPixels(px, 0, card.width, 0, 0, card.width, height)
        for (y in 0 until height) {
            for (x in 0 until card.width) {
                if (px[y * card.width + x] == Color.BLACK) {
                    val gx = x + offset
                    raster[y * widthBytes + gx / 8] = (raster[y * widthBytes + gx / 8].toInt() or (0x80 ushr (gx % 8))).toByte()
                }
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @ — inisialisasi
        repeat(copies.coerceIn(1, 50)) {
            if (top > 0) out.write(byteArrayOf(0x1B, 0x4A, top.toByte())) // ESC J n — maju n titik
            // GS v 0 m xL xH yL yH d1..dk — gambar raster
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00,
                (widthBytes and 0xff).toByte(), ((widthBytes shr 8) and 0xff).toByte(),
                (height and 0xff).toByte(), ((height shr 8) and 0xff).toByte()))
            out.write(raster)
            if (useGsFf) {
                out.write(byteArrayOf(0x1D, 0x0C)) // GS FF — maju ke awal label berikutnya
            } else {
                var gap = (gapMm * DOTS_PER_MM).roundToInt().coerceIn(0, 400)
                while (gap > 0) {
                    val n = gap.coerceAtMost(255)
                    out.write(byteArrayOf(0x1B, 0x4A, n.toByte()))
                    gap -= n
                }
            }
        }
        return out.toByteArray()
    }
}

/** Koneksi Bluetooth klasik (SPP) ke printer yang sudah di-pair di Android. */
object BluetoothPrinter {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> =
        adapter(context)?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    /** Kirim data ke printer. Mengembalikan null bila berhasil, atau pesan error. */
    @SuppressLint("MissingPermission")
    suspend fun send(context: Context, address: String, data: ByteArray): String? = withContext(Dispatchers.IO) {
        val ad = adapter(context) ?: return@withContext "HP ini tidak punya Bluetooth."
        if (!ad.isEnabled) return@withContext "Bluetooth HP belum dinyalakan."
        val device = try { ad.getRemoteDevice(address) } catch (e: IllegalArgumentException) { null }
            ?: return@withContext "Printer belum dipilih."
        try { ad.cancelDiscovery() } catch (_: Exception) {}
        var socket: BluetoothSocket? = null
        try {
            val secure = device.createRfcommSocketToServiceRecord(SPP)
            socket = secure
            try {
                secure.connect()
            } catch (first: IOException) {
                try { secure.close() } catch (_: IOException) {}
                val insecure = device.createInsecureRfcommSocketToServiceRecord(SPP)
                socket = insecure
                insecure.connect()
            }
            val os = socket!!.outputStream
            // Dikirim per potongan kecil — printer murah mudah kelebihan data.
            var i = 0
            while (i < data.size) {
                val n = minOf(512, data.size - i)
                os.write(data, i, n)
                os.flush()
                i += n
                Thread.sleep(30)
            }
            // Beri waktu printer mencetak sebelum koneksi ditutup.
            Thread.sleep(800L + data.size / 20)
            null
        } catch (e: IOException) {
            "Tidak bisa tersambung ke printer ${device.name ?: address}. Pastikan printer menyala, sudah di-pair, dan tidak sedang dipakai HP lain. (${e.message})"
        } catch (e: SecurityException) {
            "Izin Bluetooth belum diberikan."
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }
    }
}
