package com.sanwa.lotcard.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kamera scanner QR. Mode (EXTRA_MODE):
 *  - MODE_VIEW  → hasil scan dibuka di PlanActivity (Lihat Data Plan, read-only)
 *  - MODE_INPUT → hasil scan dibuka di InputActivity (Input Perolehan operator)
 *  - MODE_MANUAL → hasil scan dibuka di ManualInputActivity (Input Manual Assembly/Inspection)
 */
class ScanActivity : BaseActivity() {
    private var mode: String = MODE_VIEW
    override val darkFooter: Boolean = true

    private lateinit var previewView: PreviewView
    private lateinit var permissionText: TextView
    private lateinit var userInfo: TextView
    private lateinit var btnTorch: MaterialButton

    private var camera: Camera? = null
    private var torchOn = false
    private val handled = AtomicBoolean(false)
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (prefs.token.isNullOrBlank()) {
            goToLogin(null)
            return
        }
        mode = when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_INPUT -> MODE_INPUT
            MODE_MANUAL -> MODE_MANUAL
            else -> MODE_VIEW
        }
        setContentView(R.layout.activity_scan)
        setupToolbar(
            when (mode) {
                MODE_INPUT -> "Scan — Input Perolehan"
                MODE_MANUAL -> "Scan — Input Manual"
                else -> "Scan — Lihat Data"
            },
            back = true
        )
        findViewById<TextView>(R.id.scanHint).text = when (mode) {
            MODE_INPUT -> "Scan QR Plan yang akan diinput perolehannya"
            MODE_MANUAL -> "Scan QR Plan Assembly/Inspection Manual"
            else -> "Arahkan kamera ke QR"
        }

        previewView = findViewById(R.id.previewView)
        permissionText = findViewById(R.id.permissionText)
        userInfo = findViewById(R.id.userInfo)
        btnTorch = findViewById(R.id.btnTorch)

        analysisExecutor = Executors.newSingleThreadExecutor()
        barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )

        btnTorch.setOnClickListener { toggleTorch() }
        findViewById<MaterialButton>(R.id.btnManual).setOnClickListener { askManualCode() }
        permissionText.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::userInfo.isInitialized) return
        handled.set(false) // siap scan lagi setelah kembali dari halaman data
        userInfo.text = "Login: ${prefs.userName} (${prefs.userNik})"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        if (::barcodeScanner.isInitialized) barcodeScanner.close()
    }

    // ── Kamera & scanner ──────────────────────────────────────────────
    private fun showPermissionNeeded() {
        permissionText.visibility = View.VISIBLE
    }

    private fun startCamera() {
        permissionText.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (e: Exception) {
                showMessage("Kamera", "Kamera tidak bisa dibuka: ${e.message}")
                return@addListener
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                btnTorch.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            } catch (e: Exception) {
                showMessage("Kamera", "Kamera tidak bisa dibuka: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || handled.get()) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { codes ->
                val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf { v -> v.isNotEmpty() } }
                if (value != null && handled.compareAndSet(false, true)) {
                    runOnUiThread { onCodeScanned(value) }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnTorch.text = if (torchOn) "Senter: nyala" else "Senter"
    }

    private fun onCodeScanned(raw: String) {
        previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        openPlan(raw)
    }

    private fun openPlan(raw: String) {
        // Isi QR produksi = nomor lot (mis. 26060001). Kalau QR berisi
        // alamat web, ambil bagian terakhirnya.
        val code = raw.trim().trimEnd('/').substringAfterLast('/')
        if (code.isEmpty()) {
            handled.set(false)
            return
        }
        val target = when (mode) {
            MODE_INPUT -> InputActivity::class.java
            MODE_MANUAL -> ManualInputActivity::class.java
            else -> PlanActivity::class.java
        }
        startActivity(Intent(this, target).putExtra(PlanActivity.EXTRA_CODE, code))
    }

    private fun askManualCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "Contoh: 26060001 atau 26060001-2"
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ketik nomor Lot / turunan")
            .setView(box)
            .setPositiveButton("Buka") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    handled.set(true)
                    openPlan(v)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_VIEW = "view"
        const val MODE_INPUT = "input"
        const val MODE_MANUAL = "manual"
    }
}
