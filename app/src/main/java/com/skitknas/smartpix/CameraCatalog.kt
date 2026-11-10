package com.skitknas.smartpix

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One selectable camera. Either a camera the system lists directly (physicalId == null),
 * or a single physical lens hidden behind a "logical" multi-camera (physicalId != null).
 */
data class Lens(
    val logicalId: String,
    val physicalId: String?,
    val facing: Int,
    val eqFocalMm: Int?,
    val minFocusCm: Float?,
    val monochrome: Boolean,
    val isAutoLogical: Boolean,
    val sensorOrientation: Int,
    val jpegSize: Size,
    val previewSize: Size,
    val hasContinuousAf: Boolean,
    val label: String,
    val details: String,
) {
    val key: String get() = logicalId + "/" + (physicalId ?: "")
    val isFront: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT
}

object CameraCatalog {

    fun load(context: Context): List<Lens> {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = try { manager.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        val out = mutableListOf<Lens>()

        for (id in ids) {
            val c = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue

            val isLogical = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            val physicalIds = if (isLogical) c.physicalCameraIds.toList() else emptyList()

            build(id, null, c, c, isAuto = physicalIds.isNotEmpty(), physicalIds)?.let(out::add)

            for (pid in physicalIds) {
                // A lens that is also listed on its own is opened directly instead.
                if (pid in ids) continue
                val pc = try { manager.getCameraCharacteristics(pid) } catch (e: Exception) { continue }
                build(id, pid, c, pc, isAuto = false, emptyList())?.let(out::add)
            }
        }

        fun facingOrder(f: Int) = when (f) {
            CameraCharacteristics.LENS_FACING_BACK -> 0
            CameraCharacteristics.LENS_FACING_FRONT -> 1
            else -> 2
        }
        return out.sortedWith(
            compareBy<Lens>({ facingOrder(it.facing) }, { if (it.isAutoLogical) 0 else 1 }, { it.eqFocalMm ?: 0 })
        )
    }

    private fun build(
        logicalId: String,
        physicalId: String?,
        logical: CameraCharacteristics,
        lens: CameraCharacteristics,
        isAuto: Boolean,
        physicalIds: List<String>,
    ): Lens? {
        val facing = lens.get(CameraCharacteristics.LENS_FACING)
            ?: logical.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_EXTERNAL

        val map = lens.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: logical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: return null
        if (jpegSizes.isEmpty()) return null
        val jpeg = jpegSizes.maxBy { it.width.toLong() * it.height }
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return null
        if (previewSizes.isEmpty()) return null
        val preview = choosePreview(previewSizes, jpeg)

        val focals = lens.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
        val focal = focals.firstOrNull() ?: 0f
        val eq = equivalentFocal(lens, focal)

        val minFocusDiopters = lens.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val minFocusCm = if (minFocusDiopters > 0f) 100f / minFocusDiopters else null

        val caps = lens.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        val cfa = lens.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val mono = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME in caps ||
            cfa == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
            cfa == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR

        val orientation = lens.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: logical.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        // Focus control goes to the camera we actually open (the logical one).
        val afModes = logical.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val hasCafs = CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes

        val side = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            else -> "External"
        }
        val mp = String.format(Locale.US, "%.1f MP", jpeg.width.toLong() * jpeg.height / 1_000_000.0)
        val parts = mutableListOf(side)
        if (isAuto) {
            parts += "Auto (phone switches lenses)"
        } else {
            if (facing == CameraCharacteristics.LENS_FACING_BACK && eq != null) parts += lensType(eq)
            eq?.let { parts += "$it mm" }
            if (mono) parts += "B/W"
            minFocusCm?.let { if (it <= 6f) parts += "close focus ${fmt(it)} cm" }
        }
        parts += mp
        val label = parts.joinToString(" · ")

        val details = buildString {
            append(label).append('\n')
            append("Camera ID: ").append(logicalId)
            if (physicalId != null) append("  (physical lens ").append(physicalId).append(')')
            append('\n')
            if (physicalIds.isNotEmpty()) append("Lenses behind it: ").append(physicalIds.joinToString()).append('\n')
            append("Focal length: ").append(focals.joinToString { fmt(it) }).append(" mm")
            if (eq != null) append("  (≈ ").append(eq).append(" mm full-frame)")
            append('\n')
            append("Closest focus: ").append(minFocusCm?.let { "${fmt(it)} cm" } ?: "fixed focus").append('\n')
            append("Monochrome sensor: ").append(if (mono) "yes" else "no").append('\n')
            append("Hardware level: ").append(hardwareLevel(logical)).append('\n')
            append("Largest JPEG: ").append(jpeg.width).append('×').append(jpeg.height).append('\n')
            append("Preview: ").append(preview.width).append('×').append(preview.height).append('\n')
            append("Sensor orientation: ").append(orientation).append('°')
        }

        return Lens(
            logicalId = logicalId,
            physicalId = physicalId,
            facing = facing,
            eqFocalMm = eq,
            minFocusCm = minFocusCm,
            monochrome = mono,
            isAutoLogical = isAuto,
            sensorOrientation = orientation,
            jpegSize = jpeg,
            previewSize = preview,
            hasContinuousAf = hasCafs,
            label = label,
            details = details,
        )
    }

    /** Full-frame (35 mm) equivalent focal length, using the active part of the sensor. */
    private fun equivalentFocal(c: CameraCharacteristics, focal: Float): Int? {
        if (focal <= 0f) return null
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        var w = phys.width
        var h = phys.height
        if (pixels != null && active != null && pixels.width > 0 && pixels.height > 0) {
            w *= active.width().toFloat() / pixels.width
            h *= active.height().toFloat() / pixels.height
        }
        val diag = hypot(w.toDouble(), h.toDouble())
        if (diag <= 0.0) return null
        return (focal * 43.27 / diag).roundToInt()
    }

    private fun lensType(eq: Int) = when {
        eq < 20 -> "Ultrawide"
        eq < 40 -> "Wide"
        else -> "Tele"
    }

    private fun choosePreview(sizes: Array<Size>, jpeg: Size): Size {
        val ratio = jpeg.width.toDouble() / jpeg.height
        return sizes.filter { abs(it.width.toDouble() / it.height - ratio) < 0.02 && it.width <= 1920 && it.height <= 1440 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minBy { it.width.toLong() * it.height }
    }

    private fun hardwareLevel(c: CameraCharacteristics) = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level 3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
        else -> "unknown"
    }

    private fun fmt(v: Float): String =
        if (abs(v - v.roundToInt()) < 0.05f) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)
}
