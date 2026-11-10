package com.skitknas.smartpix

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object ImageProcessor {

    /**
     * Turns the camera's JPEG into the final upright picture:
     * center-crop to the target aspect, scale to the exact size, rotate, then mirror if asked.
     * Only the needed part of the image is decoded, at a reduced resolution when possible.
     */
    fun process(jpeg: ByteArray, job: CaptureJob): Bitmap {
        val exifRotation = try {
            ExifInterface(ByteArrayInputStream(jpeg)).rotationDegrees
        } catch (_: Exception) {
            0
        }
        val rotation = (job.rotation + exifRotation) % 360

        val decoder = newDecoder(jpeg) ?: throw IOException("Could not read the photo")
        try {
            val srcW = decoder.width
            val srcH = decoder.height
            val swap = rotation % 180 != 0

            // Work out the crop and size in the sensor's own orientation (before rotating).
            val region: Rect
            val outW: Int
            val outH: Int
            val t = job.target
            if (t == null) {
                region = Rect(0, 0, srcW, srcH)
                outW = srcW
                outH = srcH
            } else {
                outW = if (swap) t.height else t.width
                outH = if (swap) t.width else t.height
                region = centerCrop(srcW, srcH, outW, outH)
            }

            var sample = 1
            while (region.width() / (sample * 2) >= outW && region.height() / (sample * 2) >= outH) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = decoder.decodeRegion(region, opts) ?: throw IOException("Could not decode the photo")

            if (bmp.width != outW || bmp.height != outH) {
                val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }

            if (rotation != 0 || job.mirror) {
                val m = Matrix().apply {
                    postRotate(rotation.toFloat())
                    if (job.mirror) postScale(-1f, 1f)
                }
                // Quarter turns and flips map pixels exactly, so no filtering needed.
                val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
                if (turned !== bmp) bmp.recycle()
                bmp = turned
            }
            return bmp
        } finally {
            decoder.recycle()
        }
    }

    private fun newDecoder(jpeg: ByteArray): BitmapRegionDecoder? =
        if (Build.VERSION.SDK_INT >= 31) {
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
        }

    private fun centerCrop(srcW: Int, srcH: Int, tw: Int, th: Int): Rect {
        val srcRatio = srcW.toDouble() / srcH
        val targetRatio = tw.toDouble() / th
        return if (srcRatio > targetRatio) {
            val w = (srcH * targetRatio).roundToInt().coerceIn(1, srcW)
            val x = (srcW - w) / 2
            Rect(x, 0, x + w, srcH)
        } else {
            val h = (srcW / targetRatio).roundToInt().coerceIn(1, srcH)
            val y = (srcH - h) / 2
            Rect(0, y, srcW, y + h)
        }
    }

    /** Saves to Pictures/SmartPix in the gallery. No storage permission needed. */
    fun save(context: Context, bmp: Bitmap, quality: Int): Uri {
        val now = Date()
        val name = "SP_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(now) + ".jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmartPix")
            put(MediaStore.MediaColumns.DATE_TAKEN, now.time)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
                    throw IOException("Could not encode the photo")
                }
            } ?: throw IOException("Could not open the file")

            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
                    ExifInterface(pfd.fileDescriptor).apply {
                        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                        setAttribute(ExifInterface.TAG_DATETIME, stamp)
                        setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                        setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                        setAttribute(ExifInterface.TAG_SOFTWARE, "SmartPix")
                        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                        saveAttributes()
                    }
                }
            } catch (_: Exception) {
                // Photo is fine without the extra tags.
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
