package com.skitknas.smartpix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/** What to do with a photo once the camera delivers it. */
data class CaptureJob(
    val rotation: Int,      // clockwise degrees to make the image upright
    val mirror: Boolean,    // flip left-right after rotating
    val target: Size?,      // exact output size (upright), null = keep sensor size
    val quality: Int,       // JPEG quality 1–100
)

/**
 * Runs Camera2 on its own thread. All camera calls happen on that thread;
 * listener callbacks also arrive there.
 */
class CameraController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onCameraError(message: String)
        fun onJpeg(bytes: ByteArray, job: CaptureJob)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var executor: Executor? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var currentLens: Lens? = null
    private val pendingJobs = ArrayDeque<CaptureJob>()

    /** Bumped on every open/close so late callbacks from an old camera are ignored. */
    private var generation = 0

    fun start() {
        if (thread != null) return
        val t = HandlerThread("smartpix-camera").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        executor = Executor { h.post(it) }
    }

    fun stop() {
        val h = handler ?: return
        h.post {
            generation++
            closeInternal()
        }
        thread?.quitSafely()
        thread = null
        handler = null
        executor = null
    }

    fun open(lens: Lens, texture: SurfaceTexture) {
        handler?.post { openInternal(lens, texture) }
    }

    fun capture(job: CaptureJob) {
        handler?.post { captureInternal(job) }
    }

    @SuppressLint("MissingPermission")
    private fun openInternal(lens: Lens, texture: SurfaceTexture) {
        closeInternal()
        val gen = ++generation
        val exec = executor ?: return
        currentLens = lens
        try {
            manager.openCamera(lens.logicalId, exec, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation) {
                        camera.close(); return
                    }
                    device = camera
                    createSession(camera, lens, texture, gen)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    if (gen == generation) listener.onCameraError(cameraErrorText(error))
                }
            })
        } catch (e: Exception) {
            listener.onCameraError("Could not open camera: ${e.message}")
        }
    }

    private fun createSession(camera: CameraDevice, lens: Lens, texture: SurfaceTexture, gen: Int) {
        val exec = executor ?: return
        try {
            texture.setDefaultBufferSize(lens.previewSize.width, lens.previewSize.height)
            val preview = Surface(texture)
            previewSurface = preview

            val r = ImageReader.newInstance(lens.jpegSize.width, lens.jpegSize.height, ImageFormat.JPEG, 2)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                val image = rd.acquireNextImage() ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buf = image.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } finally {
                    image.close()
                }
                val job = synchronized(pendingJobs) { pendingJobs.removeFirstOrNull() }
                if (job != null) listener.onJpeg(bytes, job)
            }, handler)

            val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(r.surface))
            // This is what "locks" the lens: both streams come from one physical sensor.
            lens.physicalId?.let { pid -> outputs.forEach { it.setPhysicalCameraId(pid) } }

            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) {
                            s.close(); return
                        }
                        session = s
                        startPreview(camera, s, preview, lens)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen == generation) listener.onCameraError(refusedText(lens))
                    }
                })

            if (lens.physicalId != null) {
                val supported = try {
                    camera.isSessionConfigurationSupported(config)
                } catch (e: Exception) {
                    true // the phone can't tell in advance; just try
                }
                if (!supported) {
                    listener.onCameraError(refusedText(lens)); return
                }
            }
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            listener.onCameraError("Could not start camera: ${e.message}")
        }
    }

    private fun startPreview(camera: CameraDevice, s: CameraCaptureSession, preview: Surface, lens: Lens) {
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                if (lens.hasContinuousAf) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            s.setRepeatingRequest(req.build(), null, handler)
        } catch (e: Exception) {
            listener.onCameraError("Preview failed: ${e.message}")
        }
    }

    private fun captureInternal(job: CaptureJob) {
        val cam = device
        val s = session
        val r = reader
        val lens = currentLens
        if (cam == null || s == null || r == null || lens == null) {
            listener.onCameraError("Camera is not ready yet"); return
        }
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                if (lens.hasContinuousAf) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // We rotate the pixels ourselves, so ask for the raw sensor orientation.
                set(CaptureRequest.JPEG_ORIENTATION, 0)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            }
            synchronized(pendingJobs) { pendingJobs.addLast(job) }
            s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    synchronized(pendingJobs) { pendingJobs.removeFirstOrNull() }
                    listener.onCameraError("Capture failed (reason ${failure.reason})")
                }
            }, handler)
        } catch (e: Exception) {
            synchronized(pendingJobs) { pendingJobs.removeLastOrNull() }
            listener.onCameraError("Capture failed: ${e.message}")
        }
    }

    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        previewSurface?.release()
        previewSurface = null
        synchronized(pendingJobs) { pendingJobs.clear() }
    }

    private fun refusedText(lens: Lens) =
        "The phone refused to stream this lens on its own:\n${lens.label}\nPick another camera."

    private fun cameraErrorText(error: Int) = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "The camera is in use by another app."
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras are open."
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "The camera is disabled on this device."
        else -> "Camera error ($error)."
    }
}
