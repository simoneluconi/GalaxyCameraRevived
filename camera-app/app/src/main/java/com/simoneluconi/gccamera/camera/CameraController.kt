@file:Suppress("DEPRECATION")

package com.simoneluconi.gccamera.camera

import android.graphics.Rect
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.SurfaceHolder
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.pow

/**
 * Owns the Camera1 device (the HAL is a HAL1 M9MO device, docs/CAMERA-APP.md §2).
 *
 * Camera1 delivers callbacks on the looper of the thread that opened it, so every HAL call
 * and every callback runs on [thread]. Results go to [Listener] on the main thread.
 * The ISP powers up at startPreview() (lens out, about 2 s) and release() retracts the lens,
 * so the camera is only held while the viewfinder is in front.
 */
class CameraController(private val listener: Listener) {

    interface Listener {
        fun onCameraOpened(caps: CameraCaps)
        fun onCameraError(message: String)
        fun onFocusDone(success: Boolean)
        /** takePicture is about to run: the first frame (the RAW one, if asked for) is exposed now. */
        fun onCaptureStart()
        /** Sent once the first frame is exposed; a RAW shot's JPEG frame is exposed after it. */
        fun onShutter()
        /** [raw] is the HAL's raw frame when RAW was asked for and delivered. */
        fun onPictureTaken(jpeg: ByteArray, raw: ByteArray?, info: ShotInfo, frame: FrameInfo)
        /** The shot produced no picture at all. */
        fun onCaptureFailed()
        fun onZoomChanged(zoom: Int)
        fun onStatus(status: LiveStatus)
        /** A new histogram is ready; fetch it with [copyHistogram]. */
        fun onHistogram()
    }

    /** PAUSED: open with the lens out but no preview, waiting to resume or to be released. */
    private enum class State { CLOSED, PAUSED, IDLE, FOCUSING, CAPTURING }

    private val thread = HandlerThread("camera", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Camera thread only
    private var camera: Camera? = null
    private var caps: CameraCaps? = null
    private var state = State.CLOSED
    private var settings = ShotSettings()
    private var zoom = 0
    private var captureAfterFocus = false
    private var closeAfterCapture = false
    /** The surface to show the preview on once the capture in flight finishes (a resume during it). */
    private var resumeAfterCapture: SurfaceHolder? = null
    /** shutdown() came during a capture: the thread quits once the camera is released. */
    private var quitAfterRelease = false
    private var framesEnabled = true
    private var frameBuffer: ByteArray? = null
    /** The frame buffer is in the JNI's queue (Camera1 keeps it there across stopPreview). */
    private var frameArmed = false
    private var zoomDir = 0
    private var zoomFast = false
    // The continuous zoom: the move the lever wants, and what the HAL last got (sendZoom)
    private var zoomWantDir = 0
    private var zoomWantFast = false
    private var zoomSentAction = "stop"

    // Histogram: filled on the camera thread, copied out on the main thread
    private val histBack = IntArray(HIST_BINS)
    private val histFront = IntArray(HIST_BINS)
    private var previewEvFront = Float.NaN

    fun open(holder: SurfaceHolder, s: ShotSettings, zoomLevel: Int, shutterSound: Boolean) = handler.post {
        handler.removeCallbacks(releaseLater)
        settings = s
        closeAfterCapture = false
        val warm = camera
        if (warm != null) {
            when (state) {
                State.PAUSED -> resume(warm, holder, s, shutterSound)
                // Back before the shot finished: finishCapture() brings the preview up on this surface
                State.CAPTURING -> resumeAfterCapture = holder
                else -> Unit
            }
            return@post
        }
        val cam = openWithRetry() ?: return@post
        camera = cam
        try {
            val p = cam.parameters
            val c = CameraCaps(p)
            caps = c
            p.setPreviewSize(PREVIEW_W, PREVIEW_H)
            p.previewFormat = android.graphics.ImageFormat.NV21
            zoom = zoomLevel.coerceIn(0, c.maxZoom)
            fill(p, s, c)
            if (c.maxZoom > 0) p.zoom = zoom
            cam.parameters = p
            cam.enableShutterSound(shutterSound)
            cam.setErrorCallback(errorCallback)
            cam.setPreviewDisplay(holder)
            cam.startPreview()
            state = State.IDLE
            restoreZoom()
            startFrames(cam)
            handler.postDelayed(poll, POLL_MS)
            main.post { listener.onCameraOpened(c) }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            releaseCamera()
            main.post { listener.onCameraError(e.message ?: "start failed") }
        }
    }

    /**
     * Stops the preview at once, so the surface can go, and releases the camera (the lens
     * retracts, about 1 s) after [keepWarmMs]. An [open] before then resumes instantly.
     * A capture in flight finishes first (its frames are still saved, and its watchdog stays
     * armed) and then releases.
     */
    fun pause(keepWarmMs: Long) = handler.post {
        val cam = camera ?: return@post
        stopLive()
        stopTimerLed()
        // The RAW pool is only needed while shooting; 20 MB each is a lot on this device
        rawFree.forEach(rawOwned::remove)
        rawFree.clear()
        if (state == State.CAPTURING) {
            closeAfterCapture = true
            resumeAfterCapture = null
            return@post
        }
        if (state == State.FOCUSING) {
            handler.removeCallbacks(focusTimeout)
            try {
                cam.cancelAutoFocus()
            } catch (_: RuntimeException) {
            }
        }
        captureAfterFocus = false
        if (state != State.PAUSED) {
            try {
                cam.setPreviewCallbackWithBuffer(null)
                frameArmed = false
                cam.stopPreview()
            } catch (_: RuntimeException) {
            }
            state = State.PAUSED
        }
        // Always a separate message, so awaitIdle() returns before the slow release
        handler.removeCallbacks(releaseLater)
        handler.postDelayed(releaseLater, keepWarmMs)
    }

    private val releaseLater = Runnable { releaseCamera() }

    /**
     * A recreated activity (theme change) can get here while the previous instance is still
     * finishing a shot (a RAW shot takes about 3 s) and releasing the camera (about 1 s for
     * the lens); give it a few tries.
     */
    private fun openWithRetry(): Camera? {
        var last: RuntimeException? = null
        repeat(OPEN_TRIES) { attempt ->
            try {
                return Camera.open(0)
            } catch (e: RuntimeException) {
                last = e
                if (attempt < OPEN_TRIES - 1) Thread.sleep(OPEN_RETRY_MS)
            }
        }
        Log.e(TAG, "open failed", last)
        main.post { listener.onCameraError(last?.message ?: "open failed") }
        return null
    }

    private fun resume(cam: Camera, holder: SurfaceHolder, s: ShotSettings, shutterSound: Boolean) {
        val c = caps ?: return
        try {
            val p = cam.parameters
            fill(p, s, c)
            cam.parameters = p
            cam.enableShutterSound(shutterSound)
            cam.setPreviewDisplay(holder)
            cam.startPreview()
            state = State.IDLE
            restoreZoom()
            startFrames(cam)
            handler.postDelayed(poll, POLL_MS)
            main.post { listener.onCameraOpened(c) }
        } catch (e: Exception) {
            Log.e(TAG, "resume failed", e)
            releaseCamera()
            main.post { listener.onCameraError(e.message ?: "resume failed") }
        }
    }

    /** Waits until the camera thread has handled everything posted so far. */
    fun awaitIdle(timeoutMs: Long) {
        val latch = CountDownLatch(1)
        handler.post { latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** For good: a shot in flight still completes and is delivered, then the thread ends. */
    fun shutdown() = handler.post {
        if (state == State.CAPTURING) {
            closeAfterCapture = true
            resumeAfterCapture = null
            quitAfterRelease = true
        } else {
            releaseCamera()
            thread.quitSafely()
        }
    }

    fun setSettings(s: ShotSettings) = handler.post {
        settings = s
        if (!s.raw) {
            // RAW off: let the idle 20 MB buffers go
            rawFree.forEach(rawOwned::remove)
            rawFree.clear()
        }
        val cam = camera ?: return@post
        // Parameters set during a capture do not reach the shots after it (a dial turned while
        // a RAW shot ran left the ISP in the old mode): they go in when the preview restarts
        if (state == State.CAPTURING) {
            settingsPending = true
            return@post
        }
        applySettings(cam)
    }

    private var settingsPending = false

    private fun applySettings(cam: Camera) {
        settingsPending = false
        val c = caps ?: return
        try {
            val p = cam.parameters
            fill(p, settings, c)
            cam.parameters = p
        } catch (e: RuntimeException) {
            Log.w(TAG, "setParameters rejected", e)
        }
    }

    fun setShutterSound(on: Boolean) = handler.post { camera?.enableShutterSound(on) }

    /**
     * The self-timer lamp: the ISP blinks it by itself (slow, then fast near the end) for a
     * [seconds] countdown it supports; 0 stops it.
     */
    fun setTimerLed(seconds: Int) = handler.post {
        val c = caps ?: return@post
        val value = seconds.toString()
        if (seconds > 0 && value in c.timerLeds) {
            if (setParam("timer-led", value)) timerLedOn = true
        } else {
            stopTimerLed()
        }
    }

    private var timerLedOn = false

    /**
     * Test hook: sets HAL parameters as given, unchecked, to try ones the app has no control
     * for yet (wb-kelvin, sharpness…). The HAL keeps them until the camera closes.
     */
    fun setTestParams(params: Map<String, String>) = handler.post {
        params.forEach { (k, v) -> if (setParam(k, v)) Log.i(TAG, "test param $k=$v") }
    }

    private fun stopTimerLed() {
        if (timerLedOn) {
            timerLedOn = false
            setParam("timer-led", "off")
        }
    }

    fun setFramesEnabled(on: Boolean) = handler.post {
        framesEnabled = on
        val cam = camera ?: return@post
        if (!previewing()) return@post
        if (on) {
            startFrames(cam)
        } else {
            cam.setPreviewCallbackWithBuffer(null)
            frameArmed = false
        }
    }

    private fun previewing() = state == State.IDLE || state == State.FOCUSING

    /**
     * Touch AF: focus on [area] (Camera1 coordinates, −1000..1000 over the preview) from now
     * on; null goes back to the af-area mode (centre, multi or face).
     */
    fun setFocusArea(area: Rect?) = handler.post {
        val cam = camera ?: return@post
        if (caps?.touchAf != true) return@post
        try {
            val p = cam.parameters
            p.focusAreas = area?.let { listOf(Camera.Area(it, 1000)) }
            cam.parameters = p
        } catch (e: RuntimeException) {
            Log.w(TAG, "focus-areas rejected", e)
        }
    }

    /** Half press. */
    fun focus() = handler.post {
        val cam = camera ?: return@post
        if (state != State.IDLE) return@post
        // The HAL refuses autoFocus() while the lens is zooming
        if (zoomDir != 0 || zoomWantDir != 0) {
            zoomDir = 0
            handler.removeCallbacks(zoomStep)
            stopZoomNow()
        }
        state = State.FOCUSING
        try {
            cam.autoFocus { ok, _ -> focusDone(ok) }
            handler.postDelayed(focusTimeout, FOCUS_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            state = State.IDLE
            main.post { listener.onFocusDone(false) }
        }
    }

    private fun focusDone(ok: Boolean) {
        if (state != State.FOCUSING) return
        handler.removeCallbacks(focusTimeout)
        state = State.IDLE
        main.post { listener.onFocusDone(ok) }
        if (captureAfterFocus) {
            captureAfterFocus = false
            doCapture()
        }
    }

    /** An AF result that never comes must not leave the shutter dead. */
    private val focusTimeout = Runnable {
        if (state != State.FOCUSING) return@Runnable
        Log.w(TAG, "no AF result, giving up")
        try {
            camera?.cancelAutoFocus()
        } catch (_: RuntimeException) {
        }
        focusDone(false)
    }

    /** Half press released: unlocks AE/AWB on the ISP again. */
    fun cancelFocus() = handler.post {
        val cam = camera ?: return@post
        if (state == State.CAPTURING || captureAfterFocus) return@post
        try {
            cam.cancelAutoFocus()
        } catch (_: RuntimeException) {
        }
        if (state == State.FOCUSING) {
            handler.removeCallbacks(focusTimeout)
            state = State.IDLE
        }
    }

    /** Full press. While focusing, the shot waits for the focus result (focus priority). */
    fun capture() = handler.post {
        when (state) {
            State.FOCUSING -> captureAfterFocus = true
            State.IDLE -> doCapture()
            else -> Unit
        }
    }

    /** The zoom lever: drives the optical zoom until [stopZoom]. */
    fun startZoom(direction: Int, fast: Boolean) = handler.post {
        val c = caps ?: return@post
        if (isContinuousZoom(c)) {
            zoomWantDir = direction
            zoomWantFast = fast
            sendZoom()
            return@post
        }
        val wasStopped = zoomDir == 0
        zoomDir = direction
        zoomFast = fast
        if (wasStopped) zoomStep.run()
    }

    fun stopZoom() = handler.post {
        zoomWantDir = 0
        handler.removeCallbacks(zoomStep)
        if (caps?.let(::isContinuousZoom) == true) sendZoom() else zoomDir = 0
    }

    private fun isContinuousZoom(c: CameraCaps) = "stop" in c.zoomActions

    private fun wantedZoomAction(c: CameraCaps): String {
        if (zoomWantDir == 0) return "stop"
        val side = if (zoomWantDir > 0) "tele" else "wide"
        return "$side-fast".takeIf { zoomWantFast && it in c.zoomActions } ?: "$side-slow"
    }

    /*
     * Sends zoom-action only when the wanted move changes: the driver restarts the motor on
     * every start it gets, so a repeat is not free. Pacing the motor (stop, wait for the lens
     * to be idle, start) is the driver's job, m9mo_set_zoom_ctrl().
     */
    private fun sendZoom() {
        val c = caps ?: return
        if (camera == null) return
        val action = wantedZoomAction(c)
        if (action == zoomSentAction) return
        if (state == State.CAPTURING && action != "stop") return
        if (!setParam("zoom-action", action)) return
        zoomSentAction = action
        // While it moves the HAL reports the position in `zoom`; after a stop, one last read
        zoomDir = if (action == "stop") 0 else zoomWantDir
        handler.removeCallbacks(zoomPoll)
        handler.postDelayed(zoomPoll, ZOOM_POLL_MS)
    }

    /** Stops the motor before AF, a pause or a release. */
    private fun stopZoomNow() {
        zoomWantDir = 0
        if (zoomSentAction != "stop" && camera != null) setParam("zoom-action", "stop")
        zoomSentAction = "stop"
    }

    private val zoomPoll = object : Runnable {
        override fun run() {
            val cam = camera ?: return
            try {
                val z = cam.parameters.zoom
                if (z != zoom) {
                    zoom = z
                    main.post { listener.onZoomChanged(z) }
                }
            } catch (_: RuntimeException) {
            }
            if (zoomDir != 0) handler.postDelayed(this, ZOOM_POLL_MS)
        }
    }

    private fun setParam(key: String, value: String): Boolean {
        val cam = camera ?: return false
        return try {
            val p = cam.parameters
            p.set(key, value)
            cam.parameters = p
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "$key=$value rejected", e)
            false
        }
    }

    /** Copies the latest histogram into [dst] and returns the preview's centre-weighted EV offset. */
    fun copyHistogram(dst: IntArray): Float = synchronized(histFront) {
        System.arraycopy(histFront, 0, dst, 0, HIST_BINS)
        previewEvFront
    }

    private fun doCapture() {
        val cam = camera ?: return
        state = State.CAPTURING
        dropPendingRaw()
        frameIndex = 0
        // A bracket mode delivers several JPEGs per takePicture (the HAL sets the count)
        frameCount = try {
            cam.parameters.get("num-snaps-per-shutter")?.toIntOrNull()?.coerceIn(1, 9) ?: 1
        } catch (_: RuntimeException) {
            1
        }
        val wantRaw = settings.raw && caps?.rawLayout != null && queueRawBuffer(cam)
        main.post(listener::onCaptureStart)
        try {
            cam.takePicture(shutterCallback, if (wantRaw) rawCallback else null, jpegCallback)
            // A shot that never delivers a frame must not leave the camera stuck in CAPTURING
            handler.postDelayed(bracketTimeout, FIRST_FRAME_TIMEOUT_MS + exposureMs())
        } catch (e: RuntimeException) {
            // -EBUSY if the HAL is still busy with the previous shot
            Log.w(TAG, "takePicture failed", e)
            state = State.IDLE
        }
    }

    private val shutterCallback = Camera.ShutterCallback { main.post { listener.onShutter() } }

    // The raw frame arrives before the JPEG; held until the JPEG callback hands both on
    private var pendingRaw: ByteArray? = null

    private val rawCallback = Camera.PictureCallback { data, _ ->
        rawQueued = null
        val expected = caps?.rawLayout?.frameBytes ?: 0L
        Log.i(TAG, "raw callback: ${data?.size ?: "no data"} (expected $expected)")
        pendingRaw = data?.takeIf { it.size >= expected }
        if (data != null && pendingRaw == null) {
            Log.w(TAG, "raw frame too short: ${data.size} < $expected")
            giveBackRaw(data)
        }
    }

    /*
     * Camera1's JNI hands the app the RAW data only into a buffer the app supplied with the
     * hidden Camera.addRawImageCallbackBuffer(); without one the HAL is only asked for a
     * RAW_IMAGE_NOTIFY, with no data. The 20 MB buffers are reused: one normally, a second
     * while the previous DNG is still being written. [rawOwned] holds every buffer of this
     * camera session (free, queued, or out being written); one from an earlier session, or
     * one already given up, is never taken back, so the pool cannot grow past the limit.
     */
    private val rawFree = ArrayList<ByteArray>()
    private val rawOwned: MutableSet<ByteArray> = Collections.newSetFromMap(IdentityHashMap())
    private var rawQueued: ByteArray? = null

    private val addRawBuffer: Method? by lazy {
        try {
            Camera::class.java.getMethod("addRawImageCallbackBuffer", ByteArray::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "addRawImageCallbackBuffer is not reachable; no RAW", e)
            null
        }
    }

    private fun queueRawBuffer(cam: Camera): Boolean {
        if (rawQueued != null) return true
        val bytes = rawBufferBytes() ?: return false
        val m = addRawBuffer ?: return false
        val buf = rawFree.removeLastOrNull() ?: if (rawOwned.size < MAX_RAW_BUFFERS) {
            try {
                ByteArray(bytes).also { rawOwned.add(it) }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "no memory for a RAW buffer", e)
                return false
            }
        } else {
            Log.w(TAG, "both RAW buffers still being written; this shot is JPEG only")
            return false
        }
        return try {
            m.invoke(cam, buf)
            rawQueued = buf
            true
        } catch (e: Exception) {
            Log.e(TAG, "addRawImageCallbackBuffer failed", e)
            rawFree.add(buf)
            false
        }
    }

    /** Hands a RAW buffer back once its DNG is written, or when it will not be. */
    fun recycleRaw(buf: ByteArray) = handler.post { giveBackRaw(buf) }

    private fun giveBackRaw(buf: ByteArray) {
        // Only this session's buffers, and only while RAW is wanted; anything else goes to the GC
        if (camera == null || buf !in rawOwned || rawFree.any { it === buf }) return
        if (settings.raw && state != State.PAUSED) rawFree.add(buf) else rawOwned.remove(buf)
    }

    private fun dropPendingRaw() {
        pendingRaw?.let(::giveBackRaw)
        pendingRaw = null
    }

    /**
     * The HAL sends more than the rows: a 4160-byte tail follows them (20,440,000 bytes in
     * all), and the JNI skips any buffer smaller than what arrives.
     */
    private fun rawBufferBytes() = caps?.rawLayout?.let { (it.frameBytes + RAW_TAIL_MARGIN).toInt() }

    private var frameIndex = 0
    private var frameCount = 1
    /** The ISP's planned exposure for the next shot (live-shot-tv), from the status poll. */
    private var plannedShotSeconds: Double? = null

    /**
     * How long the shutter may stay open for this shot: the set time in S/M, otherwise the
     * ISP's plan. The capture watchdogs wait this long on top of their margins (a 16 s exposure
     * outlasts them otherwise, and a preview restart mid-exposure loses the frame in the HAL).
     */
    private fun exposureMs(): Long {
        val s = if (settings.pasm.userShutter) runCatching { Apex.parseShutter(settings.shutter) }.getOrNull()
            else plannedShotSeconds
        return ((s ?: 0.0) * 1000).toLong()
    }

    private val jpegCallback = Camera.PictureCallback { data, cam ->
        // Read what the ISP used before anything else changes it. A bracket's JPEGs arrive back
        // to back after the HAL has moved on to its last frame, so the frame number is counted
        // here, and each frame's exposure comes from the per-frame list (bracket-exif)
        val index = frameIndex
        var frameEv: String? = null
        val info = try {
            val p = cam.parameters
            val perFrame = if (frameCount > 1) ShotInfo.fromBracket(p, index) else null
            frameEv = perFrame?.biasEv?.let { "%+.1f".format(it) }
            perFrame ?: ShotInfo.from(p)
        } catch (_: RuntimeException) {
            ShotInfo(null, null, null, null, null, null, null)
        }
        val frame = FrameInfo(index, frameCount, frameEv)
        frameIndex++
        val raw = pendingRaw
        pendingRaw = null
        if (data != null) {
            main.post { listener.onPictureTaken(data, raw, info, frame) }
        } else {
            // The JNI could not allocate the JPEG's array (memory): the shot is lost, say so
            Log.e(TAG, "JPEG callback without data (frame ${index + 1} of $frameCount)")
            raw?.let(::giveBackRaw)
            main.post { listener.onCaptureFailed() }
        }
        handler.removeCallbacks(bracketTimeout)
        if (frameIndex < frameCount) {
            // More frames of the bracket to come; the preview restarts after the last one
            // The over-exposed frames run up to 2 EV (4x) longer than the plan
            handler.postDelayed(bracketTimeout, BRACKET_TIMEOUT_MS + 4 * exposureMs())
            return@PictureCallback
        }
        finishCapture(cam)
    }

    /**
     * CAMERA_MSG_ERROR: a failed bracket is all or nothing (the HAL delivers no JPEG), so get
     * the preview back at once instead of waiting for the watchdog. A dead or evicted camera
     * outside a shot is released and reported (the viewfinder would freeze otherwise).
     */
    private val errorCallback = Camera.ErrorCallback { error, _ ->
        Log.w(TAG, "camera error $error in state $state")
        when {
            state == State.CAPTURING -> {
                handler.removeCallbacks(bracketTimeout)
                bracketTimeout.run()
            }
            error == Camera.CAMERA_ERROR_SERVER_DIED || error == Camera.CAMERA_ERROR_EVICTED -> {
                releaseCamera()
                main.post { listener.onCameraError("camera error $error") }
            }
        }
    }

    /** Moves the zoom straight to [level] (a test hook: calibration shots per zoom position). */
    fun setZoomLevel(level: Int) = handler.post {
        val c = caps ?: return@post
        val target = level.coerceIn(0, c.maxZoom)
        if (camera == null || state == State.CAPTURING) return@post
        if (state == State.PAUSED) {
            // The lens does not move without a preview; applied when it restarts
            zoom = target
            main.post { listener.onZoomChanged(target) }
            return@post
        }
        if (setParam("zoom", target.toString())) {
            zoom = target
            main.post { listener.onZoomChanged(target) }
        }
    }

    /**
     * The ISP powers up at startPreview(), so a zoom set before it is lost and the lens comes
     * out at wide. Send it again once the preview runs, so the lens matches the zoom bar.
     */
    private fun restoreZoom() {
        if (zoom > 0 && (caps?.maxZoom ?: 0) > 0) setParam("zoom", zoom.toString())
    }

    /** A shot or bracket that stops short: give up on the missing frames, get the preview back. */
    private val bracketTimeout = Runnable {
        val cam = camera ?: return@Runnable
        if (state != State.CAPTURING) return@Runnable
        Log.w(TAG, "capture: got $frameIndex of $frameCount frames, giving up")
        try {
            // The HAL may still be in its picture session
            cam.stopPreview()
        } catch (_: RuntimeException) {
        }
        finishCapture(cam)
        if (frameIndex == 0) main.post { listener.onCaptureFailed() }
    }

    private fun finishCapture(cam: Camera) {
        state = State.IDLE
        dropPendingRaw()
        if (closeAfterCapture) {
            releaseCamera()
            return
        }
        try {
            // Paused and resumed during the shot: the old surface is gone
            val holder = resumeAfterCapture
            resumeAfterCapture = null
            if (holder != null) cam.setPreviewDisplay(holder)
            // The HAL restarts nothing after a shot (Camera1 contract)
            cam.startPreview()
            if (settingsPending) applySettings(cam)
            startFrames(cam)
            if (holder != null) {
                restoreZoom()
                handler.removeCallbacks(poll)
                handler.postDelayed(poll, POLL_MS)
                caps?.let { c -> main.post { listener.onCameraOpened(c) } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "preview restart failed", e)
            main.post { listener.onCameraError(e.message ?: "preview restart failed") }
        }
    }

    private val zoomStep = object : Runnable {
        override fun run() {
            val cam = camera ?: return
            val c = caps ?: return
            if (zoomDir == 0) return
            val target = (zoom + zoomDir).coerceIn(0, c.maxZoom)
            if (target != zoom && state != State.CAPTURING) {
                try {
                    val p = cam.parameters
                    p.zoom = target
                    cam.parameters = p
                    zoom = target
                    main.post { listener.onZoomChanged(target) }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "zoom rejected", e)
                }
            }
            // TODO(HAL §6.2): continuous drive through V4L2_CID_CAMERA_OPTICAL_ZOOM_CTRL
            val atEnd = (zoomDir > 0 && zoom >= c.maxZoom) || (zoomDir < 0 && zoom <= 0)
            if (!atEnd) handler.postDelayed(this, if (zoomFast) ZOOM_FAST_MS else ZOOM_SLOW_MS)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val cam = camera ?: return
            if (state == State.IDLE) {
                try {
                    val p = cam.parameters
                    val status = LiveStatus.from(p)
                    plannedShotSeconds = status.shotTv?.let { 2.0.pow(-it) }
                    main.post { listener.onStatus(status) }
                    // Meter calibration aid: `setprop log.tag.GcLive D` to trace the ISP's registers
                    if (Log.isLoggable(LIVE_TAG, Log.DEBUG)) {
                        Log.d(LIVE_TAG, "pasm=${p.get("pasm-mode")} raw=${p.get("m9mo-live-raw")} " +
                            "tv=${p.get("live-tv")} av=${p.get("live-av")} bv=${p.get("live-bv")} " +
                            "src=${p.get("live-bv-source")} iso=${p.get("live-iso")} lv=${p.get("live-lv")} " +
                            "af=${p.get("af-area")} areas=${p.get("focus-areas")} result=${p.get("af-result-area")} " +
                            "afraw=${p.get("af-result-raw")} faces=${p.get("face-count")}")
                    }
                } catch (_: RuntimeException) {
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun startFrames(cam: Camera) {
        if (!framesEnabled) return
        val buf = frameBuffer ?: ByteArray(PREVIEW_W * PREVIEW_H * 3 / 2).also { frameBuffer = it }
        cam.setPreviewCallbackWithBuffer(frameCallback)
        handler.removeCallbacks(rearm)
        // Queued twice, the one array would be filled while analyse() reads it
        if (!frameArmed) {
            cam.addCallbackBuffer(buf)
            frameArmed = true
        }
    }

    // One buffer, handed back to the HAL only every FRAME_MS: a few histograms per second,
    // and no binder copy for the frames in between
    private val rearm = Runnable {
        val cam = camera ?: return@Runnable
        if (framesEnabled && previewing() && !frameArmed) {
            frameBuffer?.let {
                cam.addCallbackBuffer(it)
                frameArmed = true
            }
        }
    }

    private val frameCallback = Camera.PreviewCallback { data, _ ->
        frameArmed = false
        if (data == null) return@PreviewCallback
        analyse(data)
        handler.postDelayed(rearm, FRAME_MS)
    }

    /** Histogram of the Y plane, sampled every 8th pixel, plus a centre-weighted mean. */
    private fun analyse(y: ByteArray) {
        val hist = histBack
        hist.fill(0)
        var centreSum = 0.0
        var centreCount = 0
        val x0 = PREVIEW_W / 4
        val x1 = PREVIEW_W * 3 / 4
        val y0 = PREVIEW_H / 4
        val y1 = PREVIEW_H * 3 / 4
        var row = 0
        while (row < PREVIEW_H) {
            val base = row * PREVIEW_W
            val inRows = row in y0 until y1
            var col = 0
            while (col < PREVIEW_W) {
                val v = y[base + col].toInt() and 0xff
                hist[v shr 2]++
                if (inRows && col >= x0 && col < x1) {
                    centreSum += LINEAR[v]
                    centreCount++
                }
                col += 8
            }
            row += 8
        }
        // Mean linear luminance against 18% grey: roughly how far the preview is from "correct"
        val mean = if (centreCount > 0) centreSum / centreCount else 0.0
        val ev = if (mean > 1e-4) (ln(mean / 0.18) / ln(2.0)).toFloat() else -10f
        synchronized(histFront) {
            System.arraycopy(hist, 0, histFront, 0, HIST_BINS)
            previewEvFront = ev
        }
        main.post(histogramReady)
    }

    private val histogramReady = Runnable { listener.onHistogram() }

    private fun fill(p: Camera.Parameters, s: ShotSettings, c: CameraCaps) {
        if (c.pasmModes.size > 1) {
            // The HAL sends the values before the mode, because the mode switch writes them
            if (c.shutterSpeeds.contains(s.shutter)) p.set("shutter-speed", s.shutter)
            if (c.apertures.contains(s.aperture)) p.set("aperture", s.aperture)
            p.set("pasm-mode", s.pasm.value)
        }
        val isoList = c.isoFor(s.pasm)
        val iso = if (!s.pasm.userIso) "auto" else s.iso.takeIf { it in isoList } ?: isoList.firstOrNull()
        if (iso != null && iso in c.isoValues) p.set("iso", iso)
        p.exposureCompensation = if (s.pasm.userEv) s.ev.coerceIn(c.evMin, c.evMax) else 0
        if (s.flash in c.flashModes) p.flashMode = s.flash
        if (s.focus in c.focusModes) p.focusMode = s.focus
        if (s.whiteBalance in c.whiteBalances) p.whiteBalance = s.whiteBalance
        if (s.metering in c.meteringModes) p.set("metering", s.metering)
        if (c.hasOis) p.set("image-stabilization", if (s.ois) "on" else "off")
        c.pictureSizeFor(s.aspect)?.let { (w, h) -> p.setPictureSize(w, h) }
        p.jpegQuality = s.jpegQuality
        if (c.hasRawSwitch) p.set("raw-capture", if (s.raw) "on" else "off")
        if (s.afArea in c.afAreas) p.set("af-area", s.afArea)
        if (c.hasAfLamp) p.set("af-lamp", if (s.afLamp) "on" else "off")
        if (c.captureModes.isNotEmpty()) {
            val mode = when (s.drive) {
                "aeb" -> "ae-bracket"
                "hdr" -> "hdr"
                else -> "single"
            }
            if (mode in c.captureModes) p.set("capture-mode", mode)
            // Compared as numbers: the HAL prints its floats as "1" and "2", the app keeps "1.0"
            val ev = s.bracketEv.toDoubleOrNull()
            c.bracketEvs.firstOrNull { it.toDoubleOrNull() == ev }?.let { p.set("bracket-ev", it) }
        }
        // The zoom is not touched here: getParameters() already carries the lens position
    }

    /** Stops the poll, the frame re-arm and any zoom drive (not a capture in flight). */
    private fun stopLive() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(rearm)
        handler.removeCallbacks(zoomStep)
        handler.removeCallbacks(zoomPoll)
        stopZoomNow()
        zoomDir = 0
    }

    private fun releaseCamera() {
        stopLive()
        handler.removeCallbacks(focusTimeout)
        handler.removeCallbacks(bracketTimeout)
        handler.removeCallbacks(releaseLater)
        captureAfterFocus = false
        resumeAfterCapture = null
        pendingRaw = null
        stopTimerLed()
        timerLedOn = false
        val cam = camera
        if (cam != null) {
            camera = null
            if (state != State.PAUSED) {
                try {
                    cam.setPreviewCallbackWithBuffer(null)
                    cam.stopPreview()
                } catch (_: RuntimeException) {
                }
            }
            state = State.CLOSED
            frameArmed = false
            cam.release()
            // The JNI drops its queued buffers with the camera; the pool starts over, and
            // buffers still out being written are not taken back (not in rawOwned any more)
            rawQueued = null
            rawFree.clear()
            rawOwned.clear()
        }
        if (quitAfterRelease) thread.quitSafely()
    }

    companion object {
        private const val TAG = "GcCamera"
        private const val LIVE_TAG = "GcLive"
        const val PREVIEW_W = 960
        const val PREVIEW_H = 720
        const val HIST_BINS = 64
        private const val POLL_MS = 500L
        private const val FRAME_MS = 250L
        private const val ZOOM_SLOW_MS = 220L
        private const val ZOOM_FAST_MS = 90L
        private const val ZOOM_POLL_MS = 100L
        /** Frames come about 1 s apart; well past that, the bracket is not coming. */
        private const val BRACKET_TIMEOUT_MS = 8000L
        /** A RAW shot takes about 3 s; a bracket's first frame about 2 s. */
        private const val FIRST_FRAME_TIMEOUT_MS = 15000L
        private const val FOCUS_TIMEOUT_MS = 6000L
        private const val OPEN_TRIES = 15
        private const val MAX_RAW_BUFFERS = 2
        private const val RAW_TAIL_MARGIN = 64 * 1024L
        private const val OPEN_RETRY_MS = 400L

        /** sRGB-ish preview luma to linear light (gamma 2.2), by table. */
        private val LINEAR = DoubleArray(256) { (it / 255.0).pow(2.2) }
    }
}
