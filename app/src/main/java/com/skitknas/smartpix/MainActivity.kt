package com.skitknas.smartpix

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity(), CameraController.Listener {

    private lateinit var prefs: Prefs
    private lateinit var controller: CameraController

    private lateinit var texture: AutoFitTextureView
    private lateinit var overlay: CropOverlayView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var lensLabel: TextView
    private lateinit var sizeButton: TextView
    private lateinit var mirrorButton: TextView
    private lateinit var thumb: ImageView

    private var lenses: List<Lens> = emptyList()
    private var current: Lens? = null
    private var deviceOrientation = 0
    private var lastUri: Uri? = null
    private var permissionAsked = false

    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicInteger(0)
    private lateinit var orientationListener: OrientationEventListener

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCameraIfReady() else showPermissionMissing()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        controller = CameraController(this, this)

        texture = findViewById(R.id.texture)
        overlay = findViewById(R.id.overlay)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        lensLabel = findViewById(R.id.lensLabel)
        sizeButton = findViewById(R.id.sizeButton)
        mirrorButton = findViewById(R.id.mirrorButton)
        thumb = findViewById(R.id.thumb)

        // Keep the controls clear of the status bar, camera cutout and navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.updatePadding(top = bars.top + dp(4), left = bars.left + dp(12), right = bars.right + dp(4))
            bottomBar.updatePadding(bottom = bars.bottom + dp(12), left = bars.left, right = bars.right)
            insets
        }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startCameraIfReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        texture.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (r > l && b > t) overlay.setPreviewRect(RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()))
        }

        lensLabel.setOnClickListener { showLensPicker() }
        findViewById<View>(R.id.infoButton).setOnClickListener { showDetails() }
        sizeButton.setOnClickListener { showSizeMenu() }
        mirrorButton.setOnClickListener {
            prefs.mirrorFront = !prefs.mirrorFront
            updateUi()
        }
        findViewById<View>(R.id.shutter).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.flipButton).setOnClickListener { flipCamera() }
        thumb.setOnClickListener { openLastPhoto() }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = ((o + 45) / 90 * 90) % 360
                if (r != deviceOrientation) {
                    deviceOrientation = r
                    updateOverlay()
                }
            }
        }
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        controller.start()
        orientationListener.enable()
        if (texture.isAvailable) startCameraIfReady()
    }

    override fun onPause() {
        orientationListener.disable()
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- camera ----------

    private fun startCameraIfReady() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) {
                permissionAsked = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                showPermissionMissing()
            }
            return
        }
        if (!texture.isAvailable) return
        if (lenses.isEmpty()) lenses = CameraCatalog.load(this)
        if (lenses.isEmpty()) {
            lensLabel.text = "No usable cameras found"
            return
        }
        val lens = lenses.find { it.key == prefs.lensKey }
            ?: lenses.firstOrNull { it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
            ?: lenses.first()
        openLens(lens)
    }

    private fun openLens(lens: Lens) {
        current = lens
        prefs.lensKey = lens.key
        if (lens.isFront) prefs.lastFrontKey = lens.key else prefs.lastBackKey = lens.key

        val ps = lens.previewSize
        if (lens.sensorOrientation % 180 == 90) texture.setAspectRatio(ps.height, ps.width)
        else texture.setAspectRatio(ps.width, ps.height)

        updateUi()
        val st = texture.surfaceTexture ?: return
        controller.open(lens, st)
    }

    private fun flipCamera() {
        val cur = current ?: return
        val wantFront = !cur.isFront
        val savedKey = if (wantFront) prefs.lastFrontKey else prefs.lastBackKey
        val next = lenses.find { it.key == savedKey && it.isFront == wantFront }
            ?: lenses.firstOrNull { it.isFront == wantFront }
        if (next == null) toast(if (wantFront) "No front camera" else "No back camera")
        else openLens(next)
    }

    /** Clockwise rotation that makes the photo upright for how the phone is held right now. */
    private fun photoRotation(lens: Lens): Int {
        val d = if (lens.isFront) -deviceOrientation else deviceOrientation
        return (lens.sensorOrientation + d + 360) % 360
    }

    private fun takePhoto() {
        val lens = current ?: return
        if (inFlight.get() >= 2) {
            toast("Still saving, one moment…"); return
        }
        inFlight.incrementAndGet()
        val job = CaptureJob(
            rotation = photoRotation(lens),
            mirror = lens.isFront && prefs.mirrorFront,
            target = prefs.target,
            quality = prefs.quality,
        )
        controller.capture(job)
        texture.animate().alpha(0.25f).setDuration(60).withEndAction {
            texture.animate().alpha(1f).setDuration(160).start()
        }.start()
    }

    override fun onJpeg(bytes: ByteArray, job: CaptureJob) {
        worker.execute {
            try {
                val bmp = ImageProcessor.process(bytes, job)
                val uri = ImageProcessor.save(this, bmp, job.quality)
                val w = bmp.width
                val h = bmp.height
                val scale = 160f / maxOf(w, h)
                val small = Bitmap.createScaledBitmap(bmp, maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()), true)
                if (small !== bmp) bmp.recycle()
                runOnUiThread {
                    lastUri = uri
                    thumb.setImageBitmap(small)
                    toast("Saved $w×$h")
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { toast("Not enough memory for that size") }
            } catch (e: Exception) {
                runOnUiThread { toast("Save failed: ${e.message}") }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    override fun onCameraError(message: String) {
        runOnUiThread {
            if (message.startsWith("Capture failed") || message.startsWith("Camera is not ready")) {
                inFlight.updateAndGet { maxOf(0, it - 1) }
            }
            if (isFinishing) return@runOnUiThread
            AlertDialog.Builder(this, DIALOG_THEME)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Choose camera") { _, _ -> showLensPicker() }
                .show()
        }
    }

    // ---------- UI ----------

    private fun updateUi() {
        val lens = current
        lensLabel.text = (lens?.label ?: "Choose camera") + "  ▾"
        val t = prefs.target
        sizeButton.text = if (t == null) "Size: Original" else "Size: ${t.width}×${t.height}"
        mirrorButton.visibility = if (lens?.isFront == true) View.VISIBLE else View.GONE
        mirrorButton.text = if (prefs.mirrorFront) "Save mirrored" else "Save true view"
        updateOverlay()
    }

    private fun updateOverlay() {
        val t = prefs.target
        if (t == null) {
            overlay.setTarget(null, "")
            return
        }
        val sideways = deviceOrientation == 90 || deviceOrientation == 270
        val aspect = if (sideways) t.height.toFloat() / t.width else t.width.toFloat() / t.height
        overlay.setTarget(aspect, "${t.width}×${t.height}")
    }

    private fun showPermissionMissing() {
        lensLabel.text = "Camera permission needed – tap here"
        lensLabel.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                lensLabel.setOnClickListener { showLensPicker() }
                startCameraIfReady()
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    private fun showLensPicker() {
        if (lenses.isEmpty()) {
            startCameraIfReady(); return
        }
        val labels = lenses.map { (if (it.key == current?.key) "● " else "") + it.label }.toTypedArray()
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Lock to camera")
            .setItems(labels) { _, i -> openLens(lenses[i]) }
            .setNeutralButton("Details") { _, _ -> showDetails() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetails() {
        val text = buildString {
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(", Android ").append(Build.VERSION.RELEASE).append("\n\n")
            if (lenses.isEmpty()) append("No cameras loaded yet.")
            lenses.forEach { append(it.details).append("\n\n") }
        }.trimEnd()
        val tv = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Cameras on this phone")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("SmartPix cameras", text))
                toast("Copied")
            }
            .show()
    }

    private fun showSizeMenu() {
        val presets = prefs.presets
        val max = current?.jpegSize
        val maxText = if (max != null) " – ${maxOf(max.width, max.height)}×${minOf(max.width, max.height)}" else ""
        val items = mutableListOf("Original$maxText")
        items += presets.map { "${it.width}×${it.height}" }
        items += "Custom size…"
        items += "Remove a preset…"
        items += "JPEG quality: ${prefs.quality}"
        val customIdx = 1 + presets.size

        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Saved image size")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i == 0 -> { prefs.target = null; updateUi() }
                    i < customIdx -> { prefs.target = presets[i - 1]; updateUi() }
                    i == customIdx -> showCustomSize()
                    i == customIdx + 1 -> showRemovePreset()
                    else -> showQuality()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomSize() {
        val t = prefs.target
        fun field(hint: String, value: Int?) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            if (value != null) setText(value.toString())
            setSelectAllOnFocus(true)
        }
        val wField = field("Width (px)", t?.width)
        val hField = field("Height (px)", t?.height)
        val keep = CheckBox(this).apply { text = "Add to presets"; isChecked = true }
        val note = TextView(this).apply {
            text = "The picture is cropped from the center to fit, then scaled to exactly this size."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(wField); addView(hField); addView(keep); addView(note)
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Custom size")
            .setView(box)
            .setPositiveButton("Use") { _, _ ->
                val w = wField.text.toString().toIntOrNull() ?: 0
                val h = hField.text.toString().toIntOrNull() ?: 0
                when {
                    w < 16 || h < 16 -> toast("Width and height must be at least 16")
                    w.toLong() * h > MAX_PIXELS -> toast("Too big – max ${MAX_PIXELS / 1_000_000} megapixels")
                    else -> {
                        val size = Size(w, h)
                        prefs.target = size
                        if (keep.isChecked && size !in prefs.presets) prefs.presets = prefs.presets + size
                        updateUi()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        wField.requestFocus()
    }

    private fun showRemovePreset() {
        val presets = prefs.presets
        if (presets.isEmpty()) {
            toast("No presets"); return
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Remove preset")
            .setItems(presets.map { "${it.width}×${it.height}" }.toTypedArray()) { _, i ->
                prefs.presets = presets.filterIndexed { idx, _ -> idx != i }
            }
            .setNeutralButton("Restore defaults") { _, _ -> prefs.presets = Prefs.DEFAULT_PRESETS }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQuality() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.quality.toString())
            setSelectAllOnFocus(true)
            gravity = Gravity.CENTER
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("JPEG quality (1–100)")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val q = field.text.toString().toIntOrNull()
                if (q == null || q !in 1..100) toast("Enter a number from 1 to 100") else prefs.quality = q
            }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    private fun openLastPhoto() {
        val uri = lastUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            toast("No gallery app found")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert
        private const val MAX_PIXELS = 40_000_000L
    }
}
