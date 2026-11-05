@file:Suppress("DEPRECATION")

package com.simoneluconi.gccamera.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Camera
import android.media.MediaActionSound
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.graphics.Rect
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.simoneluconi.gccamera.R
import com.simoneluconi.gccamera.camera.Apex
import com.simoneluconi.gccamera.camera.CameraCaps
import com.simoneluconi.gccamera.camera.CameraController
import com.simoneluconi.gccamera.camera.CameraModel
import com.simoneluconi.gccamera.camera.FrameInfo
import com.simoneluconi.gccamera.camera.LiveStatus
import com.simoneluconi.gccamera.camera.Pasm
import com.simoneluconi.gccamera.camera.ShotInfo
import com.simoneluconi.gccamera.camera.ShotSettings
import com.simoneluconi.gccamera.location.GeoTagger
import com.simoneluconi.gccamera.sensors.OrientationSensor
import com.simoneluconi.gccamera.settings.Prefs
import com.simoneluconi.gccamera.settings.SettingsActivity
import com.simoneluconi.gccamera.storage.CaptureTags
import com.simoneluconi.gccamera.storage.PhotoWriter
import com.simoneluconi.gccamera.storage.ShotMeta
import com.simoneluconi.gccamera.storage.StorageTarget
import com.simoneluconi.gccamera.ui.widget.ExposureMeterView
import com.simoneluconi.gccamera.ui.widget.HistogramView
import com.simoneluconi.gccamera.ui.widget.ModeSelectorView
import com.simoneluconi.gccamera.ui.widget.QuickMenuView
import com.simoneluconi.gccamera.ui.widget.StatusBarView
import com.simoneluconi.gccamera.ui.widget.ValueRowView
import com.simoneluconi.gccamera.ui.widget.ViewfinderOverlayView
import com.simoneluconi.gccamera.ui.widget.ViewfinderOverlayView.Af
import com.simoneluconi.gccamera.ui.widget.ZoomBarView
import com.simoneluconi.gccamera.ui.widget.quarterOf
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The camera screen. A plain framework Activity (no AndroidX) to keep the start-up and the
 * heap small on the 2012 hardware; every HAL call goes through [CameraController].
 */
class ViewfinderActivity : Activity(), CameraController.Listener, SurfaceHolder.Callback {

    private lateinit var prefs: Prefs
    private lateinit var controller: CameraController
    private lateinit var storage: StorageTarget
    private lateinit var writer: PhotoWriter
    private lateinit var orientation: OrientationSensor
    private lateinit var geo: GeoTagger

    private lateinit var preview: SurfaceView
    private lateinit var review: ImageView
    private lateinit var overlay: ViewfinderOverlayView
    private lateinit var hud: View
    private lateinit var status: StatusBarView
    private lateinit var zoomBar: ZoomBarView
    private lateinit var quickScrim: View
    private lateinit var quickMenu: QuickMenuView
    private lateinit var mode: ModeSelectorView
    private lateinit var rowShutter: ValueRowView
    private lateinit var rowAperture: ValueRowView
    private lateinit var rowIso: ValueRowView
    private lateinit var meter: ExposureMeterView
    private lateinit var histogram: HistogramView
    private lateinit var btnFlash: TextView
    private lateinit var btnFormat: TextView

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private var caps: CameraCaps? = null
    private var shot = ShotSettings()
    private var zoom = 0
    private var lastInfo: ShotInfo? = null
    private var live: LiveStatus? = null
    private var previewEv = Float.NaN
    private var flashUp: Boolean? = null
    private var target: StorageTarget.Target? = null
    private var surfaceReady = false
    private var resumed = false
    private var cameraError: String? = null
    private var reopenTried = false
    private var halfPressed = false
    private var keepCameraWarm = false
    private var sequenceBase: String? = null
    private lateinit var btnLast: ImageView
    /** The newest JPEG we saved (or found in our folder), for the last-shot button. */
    private var lastShot: Uri? = null
    private lateinit var rotatingViews: List<View>
    private var uiRotation = 0f
    private var rotationAnim: ValueAnimator? = null
    private var countdownLeft = 0
    private var shutterAt = 0L
    /** How the camera was held when the shutter fired (the JPEG arrives 1–3 s later). */
    private var shutterOrientation = -1
    private var reviewToken = 0
    private var afSound: MediaActionSound? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private val wifiNetworks = HashSet<Network>()
    private lateinit var meterTitle: String
    private lateinit var compTitle: String
    private lateinit var software: String
    private val timeFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewfinder)
        hideSystemBars()

        prefs = Prefs(this)
        shot = prefs.loadShot()
        controller = CameraController(this)
        storage = StorageTarget(this)
        writer = PhotoWriter.get(applicationContext)
        meterTitle = getString(R.string.meter_exposure)
        compTitle = getString(R.string.meter_compensation)
        software = "GC Camera " + packageManager.getPackageInfo(packageName, 0).versionName

        preview = findViewById(R.id.preview)
        review = findViewById(R.id.review)
        overlay = findViewById(R.id.overlay)
        hud = findViewById(R.id.hud)
        status = findViewById(R.id.status)
        zoomBar = findViewById(R.id.zoom)
        quickScrim = findViewById(R.id.quick_menu_scrim)
        quickMenu = findViewById(R.id.quick_menu)
        mode = findViewById(R.id.mode)
        rowShutter = findViewById(R.id.row_shutter)
        rowAperture = findViewById(R.id.row_aperture)
        rowIso = findViewById(R.id.row_iso)
        meter = findViewById(R.id.meter)
        histogram = findViewById(R.id.histogram)
        btnFlash = findViewById(R.id.label_flash)
        btnFormat = findViewById(R.id.label_format)
        btnLast = findViewById(R.id.btn_last)

        orientation = OrientationSensor(this, overlay::setRoll, ::rotateUi)
        // The labels turn, not the buttons: a wide button turned sideways overlapped its neighbours
        rotatingViews = listOf(btnFlash, btnFormat, findViewById<View>(R.id.label_fn), findViewById<View>(R.id.label_settings), quickMenu)
        geo = GeoTagger(this) { updateGps(it) }

        preview.holder.addCallback(this)
        preview.holder.setFixedSize(CameraController.PREVIEW_W, CameraController.PREVIEW_H)

        rowShutter.label = getString(R.string.row_shutter)
        rowAperture.label = getString(R.string.row_aperture)
        rowIso.label = getString(R.string.row_iso)
        rowShutter.onStep = ::stepShutter
        rowAperture.onStep = ::stepAperture
        rowIso.onStep = ::stepIso
        meter.onSet = ::setEv
        mode.onSelect = { i -> caps?.let { c -> setShot(normalise(shot.copy(pasm = c.pasmModes[i]), c)) } }
        findViewById<View>(R.id.btn_flash).setOnClickListener { cycleFlash() }
        findViewById<View>(R.id.btn_format).setOnClickListener { toggleRaw() }
        btnLast.setOnClickListener { openLastShot() }
        findViewById<View>(R.id.btn_fn).setOnClickListener { toggleQuickMenu() }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            keepCameraWarm = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        quickScrim.setOnClickListener { hideQuickMenu() }
        quickMenu.onChange = ::onQuickChange
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                tapFocus(e.x / overlay.width, e.y / overlay.height)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetFocusPoint()
                return true
            }
        })
        overlay.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            gestures.onTouchEvent(e)
        }
        review.setOnClickListener { hideReview() }

        overlay.aspect = shot.aspect
        // Every mode until the camera says which it has (the GC100 has all of them)
        mode.labels = Pasm.entries.map(::modeLabel)
        status.ois = shot.ois
        refreshExposureUi()
        refreshFlash()
        refreshFormat()
        zoomFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        zoomFromIntent(intent)
    }

    /** Test hook: `am start -n …/.ui.ViewfinderActivity --ei zoom N` goes to zoom step N. */
    private fun zoomFromIntent(i: Intent?) {
        val z = i?.getIntExtra(EXTRA_ZOOM, -1) ?: -1
        if (z < 0) return
        i?.removeExtra(EXTRA_ZOOM)
        zoom = z
        if (caps != null) controller.setZoomLevel(z)
    }

    private val testHooks get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Test hook, debuggable builds only: `am broadcast -a com.simoneluconi.gccamera.TEST_SHOT
     * --es pasm aperture --ei aperture 40 …` changes the shot settings with the viewfinder open,
     * the way the dials do. Extras: pasm, shutter, aperture, iso, ev, flash, drive, bracket_ev, timer,
     * ois, raw, and `params` "key=value;key=value" for raw HAL parameters. (An `am start`
     * would pause the activity, and the pause releases the camera.)
     */
    private val testShotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, i: Intent) {
            // Turns the UI as if the camera were held so (0, -90, 90, 180), until the sensor
            // sees the next real change
            if (i.hasExtra("ui_rotation")) rotateUi(i.getIntExtra("ui_rotation", 0))
            val c = caps ?: return
            i.getStringExtra("params")?.let { list ->
                controller.setTestParams(list.split(';').mapNotNull { kv ->
                    kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
                }.toMap())
            }
            var s = shot
            i.getStringExtra("pasm")?.let { s = s.copy(pasm = Pasm.of(it)) }
            i.getStringExtra("shutter")?.let { s = s.copy(shutter = it) }
            if (i.hasExtra("aperture")) s = s.copy(aperture = i.getIntExtra("aperture", s.aperture))
            i.getStringExtra("iso")?.let { s = s.copy(iso = it) }
            if (i.hasExtra("ev")) s = s.copy(ev = i.getIntExtra("ev", s.ev))
            i.getStringExtra("flash")?.let { s = s.copy(flash = it) }
            i.getStringExtra("drive")?.let { s = s.copy(drive = it) }
            i.getStringExtra("bracket_ev")?.let { s = s.copy(bracketEv = it) }
            if (i.hasExtra("timer")) s = s.copy(timer = i.getIntExtra("timer", s.timer))
            if (i.hasExtra("ois")) s = s.copy(ois = i.getBooleanExtra("ois", s.ois))
            if (i.hasExtra("raw")) s = s.copy(raw = i.getBooleanExtra("raw", s.raw))
            setShot(normalise(s, c))
            Log.i(TAG, "test hook: $shot")
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        reopenTried = false
        pokeAutoOff()
        applyDisplayPrefs()
        // The settings screen may have changed these
        shot = shot.copy(jpegQuality = prefs.jpegQuality, raw = prefs.raw, afLamp = prefs.afLamp)
        refreshFormat()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
            ?.let(::onBattery)
        if (testHooks) registerReceiver(testShotReceiver, IntentFilter(ACTION_TEST_SHOT), RECEIVER_EXPORTED)
        storage.watch(this) { refreshStorage() }
        refreshStorage()
        if (lastShot == null) findLastShot()
        registerWifi()
        clockTick.run()
        orientation.start()
        if (prefs.geotag) geo.start(prefs.geotagMaxAgeMs) else geo.stop()
        updateGps(geo.state)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            maybeOpen()
        }
        refreshBanner()
    }

    override fun onPause() {
        resumed = false
        cancelCountdown()
        hideQuickMenu()
        hideReview()
        halfPressed = false
        overlay.af = Af.NONE
        controller.stopZoom()
        // Our own settings screen keeps the lens out for a quick return; anything else retracts it
        controller.pause(if (keepCameraWarm) KEEP_WARM_MS else 0)
        keepCameraWarm = false
        unregisterReceiver(batteryReceiver)
        if (testHooks) unregisterReceiver(testShotReceiver)
        storage.unwatch()
        unregisterWifi()
        main.removeCallbacks(clockTick)
        main.removeCallbacks(clearTapFocus)
        main.removeCallbacks(autoOff)
        main.removeCallbacks(reopen)
        orientation.stop()
        geo.stop()
        super.onPause()
    }

    /*
     * Auto power off: FLAG_KEEP_SCREEN_ON alone would keep a camera left on the table running
     * (screen, lens out, ISP, GPS) until the battery is flat. After the idle time the flag goes
     * and the system's screen timeout turns the screen off; onPause then retracts the lens.
     */
    private val autoOff = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun pokeAutoOff() {
        main.removeCallbacks(autoOff)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ms = prefs.autoOffMs
        if (ms > 0 && resumed) main.postDelayed(autoOff, ms)
    }

    /** Touch and every key, the shutter and the zoom lever included. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        pokeAutoOff()
    }

    override fun onDestroy() {
        controller.shutdown()
        decoder.shutdown()
        afSound?.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) maybeOpen()
        refreshBanner()
    }

    // --- Surface ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        maybeOpen()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // The HAL must stop drawing into the surface before it goes away. onPause already
        // asked for the preview to stop; this waits for that, not for the lens release.
        controller.awaitIdle(3000)
    }

    private fun maybeOpen() {
        if (!resumed || !surfaceReady || !hasCameraPermission()) return
        cameraError = null
        controller.setFramesEnabled(framesWanted())
        controller.open(preview.holder, shot, zoom, prefs.shutterSound)
    }

    // --- Camera callbacks (main thread) ---

    override fun onCameraOpened(caps: CameraCaps) {
        this.caps = caps
        mode.labels = caps.pasmModes.map(::modeLabel)
        val fixed = normalise(shot, caps)
        if (fixed != shot) setShot(fixed)
        controller.setFramesEnabled(framesWanted())
        status.ois = caps.hasOis && shot.ois
        status.invalidate()
        refreshExposureUi()
        refreshFlash()
        refreshFormat()
        refreshZoom()
        refreshBanner()
    }

    private fun modeLabel(p: Pasm) = if (p == Pasm.AUTO) getString(R.string.mode_auto) else p.label

    override fun onCameraError(message: String) {
        cameraError = message
        refreshBanner()
        // One automatic retry per visit (a camera service restart, a preview that did not come back)
        if (resumed && !reopenTried) {
            reopenTried = true
            main.postDelayed(reopen, REOPEN_DELAY_MS)
        }
    }

    private val reopen = Runnable { maybeOpen() }

    override fun onFocusDone(success: Boolean) {
        if (!halfPressed && overlay.af == Af.NONE) return
        overlay.af = if (success) Af.OK else Af.FAILED
        if (success && prefs.afBeep) {
            val s = afSound ?: MediaActionSound().also {
                it.load(MediaActionSound.FOCUS_COMPLETE)
                afSound = it
            }
            s.play(MediaActionSound.FOCUS_COMPLETE)
        }
    }

    override fun onShutter() {
        shutterAt = System.currentTimeMillis()
        shutterOrientation = orientation.exifOrientation
        overlay.shutterBlink()
    }

    override fun onPictureTaken(jpeg: ByteArray, raw: ByteArray?, info: ShotInfo, frame: FrameInfo) {
        if (frame.index == 0 || frame.count == 1) {
            lastInfo = info
            if (!halfPressed) overlay.af = Af.NONE
            refreshExposureUi()
            showReview(jpeg)
        }
        save(jpeg, raw, info, frame)
    }

    override fun onCaptureFailed() {
        if (!halfPressed) overlay.af = Af.NONE
        Toast.makeText(this, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
    }

    override fun onZoomChanged(zoom: Int) {
        this.zoom = zoom
        refreshZoom()
    }

    override fun onStatus(status: LiveStatus) {
        val hadLive = hasLiveExposure()
        live = status
        if (status.flashUp != flashUp) {
            flashUp = status.flashUp
            followFlashPopup()
            refreshFlash()
        }
        // The first live reading: the preview estimate is no longer needed for the meter
        if (!hadLive && hasLiveExposure()) controller.setFramesEnabled(framesWanted())
        if (hasLiveExposure()) refreshExposureUi()
        overlay.faceLabel = status.faceCount?.takeIf { shot.afArea == "face" }?.let { getString(R.string.face_count, it) }
    }

    override fun onHistogram() {
        previewEv = controller.copyHistogram(histogram.bins)
        if (histogram.visibility == View.VISIBLE) histogram.invalidate()
        refreshMeter()
    }

    // --- Physical keys (docs/CAMERA-APP.md §4) ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isRemoteShutter(keyCode, event)) {
            if (event.repeatCount == 0) remotePress()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_FOCUS -> {
                if (event.repeatCount == 0) halfPress()
                return true
            }
            KeyEvent.KEYCODE_CAMERA -> {
                if (event.repeatCount == 0) fullPress()
                return true
            }
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_ZOOM_OUT -> {
                if (event.repeatCount == 0) {
                    hideReview()
                    controller.startZoom(zoomDirection(keyCode), isFastZoom(event))
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isRemoteShutter(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_FOCUS -> {
                halfRelease()
                return true
            }
            KeyEvent.KEYCODE_CAMERA -> return true
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_ZOOM_OUT -> {
                // The fast stage is released inside the slow one: drop back to slow
                if (isFastZoom(event)) controller.startZoom(zoomDirection(keyCode), false) else controller.stopZoom()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Any Bluetooth photo remote, not one model: they send volume keys (most of them), media
     * keys (play/pause, next/previous on two-button ones), Enter or Space (keyboard-style),
     * gamepad A / R1 (clickers), or, from an external device, CAMERA. The GC100 has no
     * volume or media keys of its own, so these only ever come from outside. An external
     * FOCUS works as the half press already. Off in the settings, they do what they normally do.
     */
    private fun isRemoteShutter(keyCode: Int, event: KeyEvent): Boolean {
        if (!prefs.remoteShutter) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1 -> true
            // The camera's own shutter is not external: it keeps its half and full press
            KeyEvent.KEYCODE_CAMERA -> event.device?.isExternal == true
            else -> false
        }
    }

    /**
     * One press with no half stage: focus, then shoot as soon as the focus ends (a press
     * during the self-timer cancels it, as the shutter does).
     */
    private fun remotePress() {
        if (countdownLeft > 0 || shot.timer > 0 || !canShoot()) {
            fullPress()
            return
        }
        hideReview()
        hideQuickMenu()
        main.removeCallbacks(clearTapFocus)
        overlay.af = Af.FOCUSING
        controller.focus()
        controller.capture()
    }

    private fun zoomDirection(keyCode: Int) = if (keyCode == KeyEvent.KEYCODE_ZOOM_IN) 1 else -1

    private fun isFastZoom(e: KeyEvent) = e.scanCode == SCAN_TELE_FAST || e.scanCode == SCAN_WIDE_FAST

    private fun halfPress() {
        halfPressed = true
        hideReview()
        hideQuickMenu()
        if (countdownLeft > 0 || caps == null) return
        main.removeCallbacks(clearTapFocus)
        overlay.af = Af.FOCUSING
        controller.focus()
    }

    private fun halfRelease() {
        halfPressed = false
        if (countdownLeft > 0) return
        overlay.af = Af.NONE
        controller.cancelFocus()
    }

    private fun fullPress() {
        if (countdownLeft > 0) {
            cancelCountdown()
            return
        }
        hideReview()
        if (!canShoot()) {
            refreshBanner()
            return
        }
        // The self-timer runs first, then the drive mode (single or a bracket) takes the shot
        if (shot.timer > 0) startCountdown(shot.timer) else controller.capture()
    }

    /**
     * A tap focuses there (touch AF), and the point stays for the half press until another tap
     * moves it or a double tap resets it. Without touch AF in the HAL, a tap focuses the centre.
     */
    private fun tapFocus(fx: Float, fy: Float) {
        val c = caps ?: return
        if (halfPressed || countdownLeft > 0) return
        hideReview()
        hideQuickMenu()
        if (c.touchAf) {
            overlay.setAfPoint(fx, fy, true)
            controller.setFocusArea(focusArea(fx, fy))
        }
        overlay.af = Af.FOCUSING
        controller.cancelFocus()
        controller.focus()
        main.removeCallbacks(clearTapFocus)
        main.postDelayed(clearTapFocus, 3000)
    }

    private fun resetFocusPoint() {
        overlay.setAfPoint(0.5f, 0.5f, false)
        overlay.af = Af.NONE
        controller.setFocusArea(null)
        controller.cancelFocus()
    }

    /** A square of 10% of the frame width around the tap, in Camera1's −1000..1000 space. */
    private fun focusArea(fx: Float, fy: Float): Rect {
        val half = 100
        val x = (fx * 2000 - 1000).roundToInt().coerceIn(-1000 + half, 1000 - half)
        val y = (fy * 2000 - 1000).roundToInt().coerceIn(-1000 + half, 1000 - half)
        return Rect(x - half, y - half, x + half, y + half)
    }

    private val clearTapFocus = Runnable {
        if (!halfPressed) {
            overlay.af = Af.NONE
            controller.cancelFocus()
        }
    }

    private fun canShoot() = target != null && caps != null && cameraError == null

    // --- Rotation, camera style: the panel stays, its labels and menus turn in place; the
    // viewfinder's bars move to the user's top and bottom ---

    private fun rotateUi(target: Int) {
        val from = uiRotation
        var delta = (target - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        val to = from + delta
        if (to == from) return
        uiRotation = to
        for (v in rotatingViews) v.animate().rotation(to).setDuration(ROTATE_MS).start()
        rotationAnim?.cancel()
        rotationAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ROTATE_MS
            addUpdateListener {
                val a = it.animatedValue as Float
                mode.contentRotation = a
                overlay.contentRotation = a
                rowShutter.contentRotation = a
                rowAperture.contentRotation = a
                rowIso.contentRotation = a
                meter.contentRotation = a
            }
            start()
        }
        fitQuickMenu()
        // The bars cannot turn in place (the status bar is as wide as the viewfinder): they
        // fade out, move to the new frame and fade back in
        hud.animate().cancel()
        hud.animate().alpha(0f).setDuration(HUD_FADE_MS).withEndAction {
            layoutHud()
            placePanel()
            hud.animate().alpha(1f).setDuration(HUD_FADE_MS).start()
        }.start()
    }

    /**
     * Held vertically the panel belongs at the user's bottom. Turned anticlockwise that is the
     * screen's right, where it always sits; turned clockwise it is the screen's left, so the
     * panel and the viewfinder swap sides.
     */
    private fun placePanel() {
        val panel = findViewById<View>(R.id.panel)
        val root = panel.parent as ViewGroup
        val index = if (quarterOf(uiRotation) == 1) 0 else 1
        if (root.indexOfChild(panel) == index) return
        root.removeView(panel)
        root.addView(panel, index)
    }

    /** Held vertically, the bars' frame takes the viewfinder's swapped size and turns with the user. */
    private fun layoutHud() {
        val vf = hud.parent as View
        if (vf.width == 0) {
            vf.post(::layoutHud)
            return
        }
        val vertical = Math.round(uiRotation / 90f) % 2 != 0
        val lp = hud.layoutParams
        val w = if (vertical) vf.height else ViewGroup.LayoutParams.MATCH_PARENT
        val h = if (vertical) vf.width else ViewGroup.LayoutParams.MATCH_PARENT
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            hud.layoutParams = lp
        }
        hud.rotation = uiRotation % 360f
    }

    /** Turned sideways, the Fn menu has the viewfinder's height to fit in, not its width. */
    private fun fitQuickMenu() {
        val vertical = Math.round(uiRotation / 90f) % 2 != 0
        val lp = quickMenu.layoutParams
        val w = if (vertical) (quickScrim.height * 0.95f).toInt().takeIf { it > 0 } ?: lp.width else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.width != w) {
            lp.width = w
            quickMenu.layoutParams = lp
        }
    }

    // --- Self-timer ---

    private fun startCountdown(seconds: Int) {
        countdownLeft = seconds
        overlay.countdown = seconds
        // The front lamp blinks for the subject; the ISP times its own slow-then-fast pattern
        controller.setTimerLed(seconds)
        main.postDelayed(countdownTick, 1000)
    }

    private val countdownTick: Runnable = Runnable {
        countdownLeft--
        overlay.countdown = countdownLeft
        if (countdownLeft > 0) {
            main.postDelayed(countdownTick, 1000)
        } else {
            controller.setTimerLed(0)
            overlay.af = Af.FOCUSING
            controller.focus()
            controller.capture()
        }
    }

    private fun cancelCountdown() {
        if (countdownLeft > 0) controller.setTimerLed(0)
        main.removeCallbacks(countdownTick)
        countdownLeft = 0
        overlay.countdown = 0
    }

    // --- Settings changes ---

    private fun setShot(s: ShotSettings) {
        if (s == shot) return
        shot = s
        prefs.saveShot(s)
        controller.setSettings(s)
        overlay.aspect = s.aspect
        status.ois = (caps?.hasOis ?: true) && s.ois
        status.invalidate()
        refreshExposureUi()
        refreshFlash()
        refreshFormat()
        refreshBanner()
    }

    private fun normalise(s: ShotSettings, c: CameraCaps): ShotSettings {
        var r = s
        if (r.pasm !in c.pasmModes) r = r.copy(pasm = Pasm.AUTO)
        if (c.shutterSpeeds.isNotEmpty() && r.shutter !in c.shutterSpeeds) {
            r = r.copy(shutter = c.nearestShutter(1 / 125.0) ?: c.shutterSpeeds.first())
        }
        if (c.apertures.isNotEmpty() && r.aperture !in c.apertures) r = r.copy(aperture = c.apertures.first())
        if (c.flashModes.isNotEmpty() && r.flash !in c.flashModes) r = r.copy(flash = c.flashModes.first())
        if (r.ev !in c.evMin..c.evMax) r = r.copy(ev = 0)
        // In M the HAL turns "auto" into ISO100; show and keep a real value instead
        val isoList = c.isoFor(r.pasm)
        if (r.pasm.userIso && r.iso !in isoList && isoList.isNotEmpty()) r = r.copy(iso = isoList.first())
        return r
    }

    private fun stepShutter(d: Int) {
        val list = caps?.shutterSpeeds?.takeIf { it.isNotEmpty() } ?: return
        val i = list.indexOf(shot.shutter).coerceAtLeast(0)
        setShot(shot.copy(shutter = list[(i + d).coerceIn(0, list.lastIndex)]))
    }

    private fun stepAperture(d: Int) {
        val list = caps?.apertures?.takeIf { it.isNotEmpty() } ?: return
        val i = list.indexOf(shot.aperture).coerceAtLeast(0)
        setShot(shot.copy(aperture = list[(i + d).coerceIn(0, list.lastIndex)]))
    }

    private fun stepIso(d: Int) {
        val list = caps?.isoFor(shot.pasm)?.takeIf { it.isNotEmpty() } ?: return
        val i = list.indexOf(shot.iso).coerceAtLeast(0)
        setShot(shot.copy(iso = list[(i + d).coerceIn(0, list.lastIndex)]))
    }

    /** From the compensation scale, in 1/3 EV; the HAL's range is narrower than the scale. */
    private fun setEv(thirds: Int) {
        val c = caps ?: return
        if (!shot.pasm.userEv) return
        val steps = (thirds / (3 * c.evStep)).roundToInt()
        setShot(shot.copy(ev = steps.coerceIn(c.evMin, c.evMax)))
    }

    /**
     * The pop-up flash is the switch: raised means the remembered auto/on mode, closed means
     * off. The button only toggles auto and on while the flash is up.
     */
    private fun cycleFlash() {
        if (flashUp != true) {
            Toast.makeText(this, R.string.toast_raise_flash, Toast.LENGTH_SHORT).show()
            return
        }
        val next = if (shot.flash == Camera.Parameters.FLASH_MODE_AUTO) Camera.Parameters.FLASH_MODE_ON else Camera.Parameters.FLASH_MODE_AUTO
        if (caps?.flashModes?.contains(next) != true) return
        prefs.flashUpMode = next
        setShot(shot.copy(flash = next))
    }

    private fun followFlashPopup() {
        val up = flashUp ?: return
        val mode = if (up) prefs.flashUpMode else Camera.Parameters.FLASH_MODE_OFF
        if (caps?.flashModes?.contains(mode) == true) setShot(shot.copy(flash = mode))
    }

    // --- Quick menu (Fn) ---

    private fun toggleQuickMenu() {
        if (quickScrim.visibility == View.VISIBLE) hideQuickMenu() else showQuickMenu()
    }

    private fun showQuickMenu() {
        val c = caps ?: return
        val rows = ArrayList<QuickMenuView.Row>()
        fun add(key: String, label: Int, options: List<Pair<String, String>>, selected: String, section: Int) {
            rows += QuickMenuView.Row(key, getString(label), options, selected, getString(section))
        }

        // Exposure
        if (c.meteringModes.isNotEmpty()) {
            add("metering", R.string.qm_metering, c.meteringModes.map { it to label(METERING_LABELS, it) }, shot.metering, R.string.qm_section_exposure)
        }
        if (c.whiteBalances.isNotEmpty()) {
            add("wb", R.string.qm_wb, c.whiteBalances.map { it to label(WB_LABELS, it) }, shot.whiteBalance, R.string.qm_section_exposure)
        }
        // Focus
        val areas = c.afAreas.filter { it in AF_AREA_LABELS }
        if (areas.size > 1) {
            add("af_area", R.string.qm_af_area, areas.map { it to label(AF_AREA_LABELS, it) }, shot.afArea, R.string.qm_section_focus)
        }
        val focus = c.focusModes.filter { it in FOCUS_LABELS }
        if (focus.isNotEmpty()) {
            add("focus", R.string.qm_focus, focus.map { it to label(FOCUS_LABELS, it) }, shot.focus, R.string.qm_section_focus)
        }
        // Drive
        val drives = mutableListOf("single" to getString(R.string.drive_single))
        if ("ae-bracket" in c.captureModes) drives += "aeb" to getString(R.string.drive_aeb)
        if ("hdr" in c.captureModes) drives += "hdr" to getString(R.string.drive_hdr)
        if (drives.size > 1) add("drive", R.string.qm_drive, drives, shot.drive, R.string.qm_section_drive)
        if ((shot.drive == "aeb" || shot.drive == "hdr") && c.bracketEvs.isNotEmpty()) {
            val ev = shot.bracketEv.toDoubleOrNull()
            add(
                "bracket_ev", R.string.qm_bracket_ev, c.bracketEvs.map { it to "±$it" },
                c.bracketEvs.firstOrNull { it.toDoubleOrNull() == ev } ?: shot.bracketEv, R.string.qm_section_drive,
            )
        }
        add(
            "timer", R.string.qm_timer,
            listOf(
                "0" to getString(R.string.off),
                "2" to getString(R.string.drive_timer2),
                "5" to getString(R.string.drive_timer5),
                "10" to getString(R.string.drive_timer10),
            ),
            shot.timer.toString(), R.string.qm_section_drive,
        )
        // Image
        if (c.hasOis) {
            add(
                "ois", R.string.qm_ois, listOf("on" to getString(R.string.on), "off" to getString(R.string.off)),
                if (shot.ois) "on" else "off", R.string.qm_section_image,
            )
        }
        val aspects = ASPECTS.filter { c.pictureSizeFor(it) != null }
        if (aspects.size > 1) add("aspect", R.string.qm_aspect, aspects.map { it to it }, shot.aspect, R.string.qm_section_image)
        quickMenu.setRows(rows)
        quickScrim.visibility = View.VISIBLE
        fitQuickMenu()
    }

    private fun hideQuickMenu() {
        if (quickScrim.visibility != View.VISIBLE) return
        quickScrim.visibility = View.GONE
        quickMenu.clear()
    }

    private fun onQuickChange(key: String, value: String) {
        val wasBracket = shot.drive == "aeb" || shot.drive == "hdr"
        setShot(
            when (key) {
                "wb" -> shot.copy(whiteBalance = value)
                "metering" -> shot.copy(metering = value)
                "focus" -> shot.copy(focus = value)
                "af_area" -> shot.copy(afArea = value)
                "drive" -> shot.copy(drive = value)
                "timer" -> shot.copy(timer = value.toIntOrNull() ?: 0)
                "bracket_ev" -> shot.copy(bracketEv = value)
                "ois" -> shot.copy(ois = value == "on")
                "aspect" -> shot.copy(aspect = value)
                else -> shot
            },
        )
        // The bracket step row comes and goes with the bracket modes
        if (key == "drive" && wasBracket != (value == "aeb" || value == "hdr")) showQuickMenu()
    }

    private fun label(map: Map<String, Int>, value: String) = map[value]?.let(::getString) ?: value

    // --- Readouts ---

    private fun refreshExposureUi() {
        val c = caps
        val p = shot.pasm
        // Until the camera answers (caps null), the saved settings as they were: no "—" for
        // values the user set, no mode list that is empty or changes a moment later
        mode.selected = (c?.pasmModes ?: Pasm.entries).indexOf(p).coerceAtLeast(0)
        val l = live?.takeIf { hasLiveExposure() }
        val info = lastInfo

        rowShutter.active = p.userShutter && (c == null || c.shutterSpeeds.isNotEmpty())
        rowShutter.value = if (rowShutter.active) {
            Apex.formatShutter(shot.shutter)
        } else {
            // The shot's planned time first: the preview's is capped at 1/30 and reads 0 when dark
            val tv = l?.shotTv ?: l?.liveTv?.takeIf { it > 0 }
            val seconds = if (l != null) tv?.let { 2.0.pow(-it) } else info?.exposureSeconds
            seconds?.let { c?.nearestShutter(it) }?.let(Apex::formatShutter) ?: dash()
        }

        rowAperture.active = p.userAperture && (c == null || c.apertures.isNotEmpty())
        rowAperture.value = if (rowAperture.active) {
            Apex.formatAperture(shot.aperture)
        } else {
            val f = l?.liveAv?.takeIf { it > 0 }?.let { 2.0.pow(it / 2) } ?: l?.shotFNumber ?: info?.fNumber
            f?.let { "f/%.1f".format(it) } ?: dash()
        }

        rowIso.active = p.userIso
        rowIso.value = if (rowIso.active) {
            isoLabel(shot.iso)
        } else {
            // The preview's gain is finer than the shot's 1-stop code, but only while its AE is in range
            val iso = if (l != null) (l.liveIso?.takeIf { (l.liveTv ?: 0.0) > 0 } ?: l.shotIso) else info?.iso
            iso?.toString() ?: getString(R.string.iso_auto)
        }

        meter.interactive = p.userEv
        refreshMeter()
    }

    private fun refreshMeter() {
        val c = caps
        if (shot.pasm != Pasm.MANUAL) {
            val ev = if (shot.pasm.userEv) shot.ev * (c?.evStep ?: DEFAULT_EV_STEP) else 0f
            meter.set(compTitle, ev, false)
            return
        }
        val bv = live?.liveBv
        if (live?.tooDark == true) {
            // Below what the ISP can meter: pin the needle at the dark end
            meter.set(meterTitle, UNDER_RANGE_EV, false)
        } else if (bv != null && c != null) {
            // APEX: correct when Av + Tv = Bv + Sv; positive means more light than needed
            val iso = shot.iso.removePrefix("ISO").toDoubleOrNull() ?: 100.0
            val set = Apex.av(shot.aperture / 10.0) + Apex.tv(Apex.parseShutter(shot.shutter))
            meter.set(meterTitle, (bv + Apex.sv(iso) - set).toFloat(), false)
        } else {
            // TODO(HAL §6.3): until the ISP's live metering is exposed, estimate from the preview
            meter.set(meterTitle, previewEv.takeIf { !it.isNaN() }, true)
        }
    }

    private fun isoLabel(v: String) = if (v == "auto") getString(R.string.iso_auto) else v.removePrefix("ISO")

    private fun dash() = getString(R.string.unknown_value)

    private fun refreshFlash() {
        val label = when (shot.flash) {
            Camera.Parameters.FLASH_MODE_AUTO -> getString(R.string.flash_auto)
            Camera.Parameters.FLASH_MODE_ON -> getString(R.string.flash_on)
            else -> getString(R.string.flash_off)
        }
        btnFlash.text = getString(R.string.flash_button, label)
        // The same rule as RAW and the status bar: accent when it will fire, grey while it
        // waits for the pop-up to be raised, plain when off
        val wanted = shot.flash != Camera.Parameters.FLASH_MODE_OFF
        val ready = wanted && flashUp != false
        (btnFlash.parent as View).isActivated = ready
        btnFlash.setTextColor(getColor(when {
            ready -> R.color.on_accent
            wanted -> R.color.on_surface_muted
            else -> R.color.on_surface
        }))
    }

    private fun refreshZoom() {
        val c = caps ?: return
        val z = zoom.coerceIn(0, c.zoomRatios.lastIndex)
        zoomBar.set(z, c.maxZoom, c.focal35mm(z), c.zoomRatios[z])
    }

    private fun refreshBanner() {
        val text = when {
            !hasCameraPermission() -> getString(R.string.banner_permission)
            cameraError != null -> getString(R.string.banner_camera_error, cameraError)
            target == null -> getString(R.string.banner_no_sd)
            else -> null
        }
        overlay.setBanner(text, text != null)
    }

    private fun applyDisplayPrefs() {
        overlay.grid = prefs.grid
        overlay.levelEnabled = prefs.level
        histogram.visibility = if (prefs.histogram) View.VISIBLE else View.GONE
        controller.setFramesEnabled(framesWanted())
        controller.setShutterSound(prefs.shutterSound)
    }

    /** Preview frames feed the histogram, and the M meter until the HAL reports live metering. */
    private fun framesWanted() = prefs.histogram || !hasLiveExposure()

    /**
     * The HAL publishes the live-* readouts only once the preview runs, so the caps (read at
     * open, before startPreview) miss them; the first poll that carries them counts too.
     */
    private fun hasLiveExposure() = caps?.hasLiveExposure == true || live?.bvSource != null

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // --- Saving and review ---

    private fun save(jpeg: ByteArray, raw: ByteArray?, info: ShotInfo, frame: FrameInfo) {
        val app = applicationContext  // the activity may be gone by the time the save ends
        val t = target ?: run {
            Toast.makeText(app, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
            raw?.let(controller::recycleRaw)
            return
        }
        // A bracket shares one number: GC100_0042_1.jpg, _2, _3
        if (frame.index == 0 || sequenceBase == null) sequenceBase = "${CameraModel.shortName}_%04d".format(prefs.nextFileNumber())
        val name = if (frame.count > 1) "${sequenceBase}_${frame.index + 1}" else sequenceBase!!
        if (frame.count > 1) Log.i(TAG, "bracket frame ${frame.index + 1}/${frame.count} ev=${frame.ev} iso=${info.iso} t=${info.exposureSeconds} f=${info.fNumber} bias=${info.biasEv}")
        val fired = flashUp == true && when (shot.flash) {
            Camera.Parameters.FLASH_MODE_ON -> true
            Camera.Parameters.FLASH_MODE_AUTO -> (info.ispFlash ?: 0) and 1 == 1
            else -> false
        }
        val meta = ShotMeta(
            info = caps?.let { c -> info.copy(exposureSeconds = info.exposureSeconds?.let(c::nominalSeconds)) } ?: info,
            pasm = shot.pasm,
            takenAt = if (shutterAt > 0) shutterAt else System.currentTimeMillis(),
            exifOrientation = if (shutterOrientation >= 0) shutterOrientation else orientation.exifOrientation,
            location = if (prefs.geotag) geo.location() else null,
            flashFired = fired,
            flashMode = shot.flash,
            whiteBalanceAuto = shot.whiteBalance == Camera.Parameters.WHITE_BALANCE_AUTO,
            metering = shot.metering,
            artist = prefs.artist,
            copyright = prefs.copyright,
            software = software,
            capture = CaptureTags(
                ois = caps?.hasOis == true && shot.ois,
                focusMode = shot.focus,
                afArea = if (overlay.afTouched && caps?.touchAf == true) "touch" else shot.afArea,
                drive = shot.drive,
                bracketEv = shot.bracketEv.takeIf { frame.count > 1 },
                frameIndex = frame.index,
                frameCount = frame.count,
                timer = shot.timer,
                flashPopup = flashUp,
                zoomStep = zoom,
                zoomMax = caps?.maxZoom ?: 0,
            ),
        )
        // A bracket's frames all carry the time of the shutter
        if (frame.index >= frame.count - 1) {
            shutterAt = 0
            shutterOrientation = -1
        }
        val layout = caps?.rawLayout.takeIf { raw != null }
        val jpegBytes = jpeg.size.toLong()
        // Both callbacks post to the main thread in order, so this is set before done reads it
        var jpegOk = false
        writer.saveShot(t, prefs.folderName, name, jpeg, raw, layout, meta, jpegSaved = { uri ->
            main.post {
                jpegOk = uri != null
                if (uri == null) {
                    Toast.makeText(app, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
                } else {
                    // A bracket's button shows its first frame, the one the review showed
                    if (frame.index == 0) setLastShot(uri)
                    // JPEG sizes vary with the scene; the DNG is fixed and added in shotsLeft()
                    prefs.averageShotBytes = (prefs.averageShotBytes * 7 + jpegBytes) / 8
                    if (resumed) refreshStorage()
                }
            }
        }) { dngFailed ->
            raw?.let(controller::recycleRaw)
            main.post {
                // A failed JPEG has had its own toast
                if (dngFailed && jpegOk) Toast.makeText(app, R.string.toast_dng_failed, Toast.LENGTH_SHORT).show()
                if (raw != null && resumed) refreshStorage()
            }
        }
    }

    private fun toggleRaw() {
        if (caps?.rawLayout == null) {
            Toast.makeText(this, R.string.toast_raw_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        setShot(shot.copy(raw = !shot.raw))
    }

    private fun refreshFormat() {
        // Until the camera answers, the saved setting: it was valid when it was saved
        val on = shot.raw && (caps == null || caps?.rawLayout != null)
        val label = getString(if (on) R.string.format_jpeg_raw else R.string.format_jpeg)
        btnFormat.text = label
        // The accent background is on the button, around the label
        (btnFormat.parent as View).isActivated = on
        btnFormat.setTextColor(getColor(if (on) R.color.on_accent else R.color.on_surface))
        status.format = label.replace('\n', ' ')
        status.shotsLeft = target?.freeBytes()?.let { (it / shotBytes()).toInt() } ?: -1
        status.invalidate()
    }

    private fun showReview(jpeg: ByteArray) {
        val ms = prefs.reviewMs
        if (ms <= 0) return
        val token = ++reviewToken
        decoder.execute {
            // A quarter-size RGB565 decode: about 2 MB instead of 64 MB
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 4
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            main.post {
                if (token != reviewToken || !resumed || bmp == null) return@post
                review.setImageBitmap(bmp)
                review.visibility = View.VISIBLE
                main.removeCallbacks(hideReviewRunnable)
                main.postDelayed(hideReviewRunnable, ms)
            }
        }
    }

    private val hideReviewRunnable = Runnable { hideReview() }

    // --- Last shot (opens the gallery: GC Gallery, or any app taking ACTION_REVIEW) ---

    private fun setLastShot(uri: Uri) {
        lastShot = uri
        decoder.execute {
            // The EXIF thumbnail: MediaStore reads it instead of decoding the 16 MP image
            val bmp = runCatching { contentResolver.loadThumbnail(uri, Size(LAST_THUMB_W, LAST_THUMB_H), null) }.getOrNull()
            main.post {
                if (lastShot != uri) return@post
                btnLast.setImageBitmap(bmp)
                btnLast.visibility = if (bmp != null) View.VISIBLE else View.GONE
            }
        }
    }

    /** At start: the newest JPEG in our folder on the current card. */
    private fun findLastShot() {
        val t = target ?: return
        val folder = "DCIM/${prefs.folderName}/"
        decoder.execute {
            val collection = MediaStore.Images.Media.getContentUri(t.volumeName)
            val uri = runCatching {
                contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.MIME_TYPE}=?",
                    arrayOf(folder, "image/jpeg"),
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC",
                )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
            }.getOrNull()
            if (uri != null) main.post { if (lastShot == null) setLastShot(uri) }
        }
    }

    private fun openLastShot() {
        val uri = lastShot ?: return
        val show = Intent(MediaStore.ACTION_REVIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Back from the gallery in a moment: keep the lens out, as for the settings
        keepCameraWarm = true
        try {
            startActivity(show)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(show).setAction(Intent.ACTION_VIEW))
            } catch (_: ActivityNotFoundException) {
                keepCameraWarm = false
                Toast.makeText(this, R.string.toast_no_gallery, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideReview() {
        reviewToken++
        main.removeCallbacks(hideReviewRunnable)
        if (review.visibility != View.GONE) {
            review.visibility = View.GONE
            review.setImageDrawable(null)
        }
    }

    // --- Status bar sources ---

    private fun refreshStorage() {
        val t = storage.current(prefs.saveToInternal)
        target = t
        status.storageMissing = t == null
        status.storageLabel = getString(
            when {
                t == null -> R.string.status_no_sd
                t.removable -> R.string.status_sd
                else -> R.string.status_internal
            },
        )
        status.shotsLeft = t?.freeBytes()?.let { (it / shotBytes()).toInt() } ?: -1
        status.invalidate()
        refreshBanner()
    }

    /** Bytes one shot takes with the current format: the JPEG average, plus the DNG with RAW. */
    private fun shotBytes(): Long {
        // Before the camera answers, the size remembered from the last open
        val image = caps?.rawLayout?.let { it.width.toLong() * it.height * 2 }?.also { prefs.dngImageBytes = it }
            ?: prefs.dngImageBytes.takeIf { caps == null }
        // The DNG also embeds the ISP JPEG as its full-size preview
        val dng = image?.takeIf { shot.raw && it > 0 }?.let { it + DNG_OVERHEAD + prefs.averageShotBytes } ?: 0L
        return (prefs.averageShotBytes + dng).coerceAtLeast(1)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status.batteryPct = if (level >= 0) level * 100 / scale else -1
        status.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        status.invalidate()
    }

    private val clockTick: Runnable = object : Runnable {
        override fun run() {
            status.time = timeFormat.format(Date())
            status.invalidate()
            val now = System.currentTimeMillis()
            main.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    private fun registerWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateWifi(network, true)
            override fun onLost(network: Network) = updateWifi(network, false)
        }
        cm.registerNetworkCallback(request, cb, main)
        wifiCallback = cb
    }

    private fun unregisterWifi() {
        val cb = wifiCallback ?: return
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        wifiCallback = null
        wifiNetworks.clear()
    }

    private fun updateWifi(network: Network, up: Boolean) {
        if (up) wifiNetworks.add(network) else wifiNetworks.remove(network)
        status.wifi = wifiNetworks.isNotEmpty()
        status.invalidate()
    }

    private fun updateGps(s: GeoTagger.State) {
        status.gps = when (s) {
            GeoTagger.State.OFF -> StatusBarView.Gps.OFF
            GeoTagger.State.SEARCHING -> StatusBarView.Gps.SEARCHING
            GeoTagger.State.FIX -> StatusBarView.Gps.FIX
        }
        status.invalidate()
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val TAG = "GcCamera"
        private const val EXTRA_ZOOM = "zoom"
        private const val ACTION_TEST_SHOT = "com.simoneluconi.gccamera.TEST_SHOT"
        private const val LAST_THUMB_W = 160
        private const val LAST_THUMB_H = 120
        private const val REQ_CAMERA = 1
        private const val KEEP_WARM_MS = 30_000L
        private const val REOPEN_DELAY_MS = 1500L
        private const val UNDER_RANGE_EV = -10f
        private const val ROTATE_MS = 250L
        private const val HUD_FADE_MS = 120L
        /** The M9MO's compensation step, shown until the camera reports its own. */
        private const val DEFAULT_EV_STEP = 1f / 3f
        /** DNG headers and the 256-px preview, on top of the 16-bit CFA. */
        private const val DNG_OVERHEAD = 200_000L

        // gpio-keys scan codes of the lever's fast stage (keylayout gpio-keys.kl)
        private const val SCAN_TELE_FAST = 545
        private const val SCAN_WIDE_FAST = 546

        private val ASPECTS = listOf("4:3", "3:2", "16:9")
        private val WB_LABELS = mapOf(
            "auto" to R.string.wb_auto,
            "incandescent" to R.string.wb_incandescent,
            "fluorescent" to R.string.wb_fluorescent,
            "daylight" to R.string.wb_daylight,
            "cloudy-daylight" to R.string.wb_cloudy,
        )
        private val METERING_LABELS = mapOf(
            "center" to R.string.metering_center,
            "spot" to R.string.metering_spot,
            "matrix" to R.string.metering_matrix,
        )
        private val AF_AREA_LABELS = mapOf(
            "center" to R.string.af_area_center,
            "multi" to R.string.af_area_multi,
            "face" to R.string.af_area_face,
        )
        private val FOCUS_LABELS = mapOf(
            "auto" to R.string.focus_auto,
            "macro" to R.string.focus_macro,
            "infinity" to R.string.focus_infinity,
        )
    }
}
