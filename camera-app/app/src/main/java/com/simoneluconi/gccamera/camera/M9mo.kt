@file:Suppress("DEPRECATION")

package com.simoneluconi.gccamera.camera

import android.hardware.Camera
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** The HAL's exposure programs (`pasm-mode`). AUTO leaves the ISP's own program untouched. */
enum class Pasm(val value: String, val label: String) {
    AUTO("auto", "AUTO"),
    PROGRAM("program", "P"),
    APERTURE("aperture", "A"),
    SHUTTER("shutter", "S"),
    MANUAL("manual", "M");

    val userShutter get() = this == SHUTTER || this == MANUAL
    val userAperture get() = this == APERTURE || this == MANUAL
    val userIso get() = this != AUTO
    val userEv get() = this != MANUAL && this != AUTO

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.value == v } ?: AUTO
    }
}

/** APEX conversions for the HAL's x100 values (docs/CAMERA-APP.md §3.2). */
object Apex {
    fun tvToSeconds(tv100: Int) = 2.0.pow(-tv100 / 100.0)
    fun avToFNumber(av100: Int) = 2.0.pow(av100 / 200.0)
    fun tv(seconds: Double) = -log2(seconds)
    fun av(fNumber: Double) = 2 * log2(fNumber)
    fun sv(iso: Double) = log2(iso / 3.125)

    /** "1/250" → 0.004, "1.5" → 1.5. */
    fun parseShutter(s: String): Double {
        val slash = s.indexOf('/')
        return if (slash < 0) s.toDouble() else s.substring(0, slash).toDouble() / s.substring(slash + 1).toDouble()
    }

    /** Shutter values as cameras print them: 1/250, 0.7", 4". */
    fun formatShutter(s: String) = if (s.contains('/')) s else "$s\""

    fun formatAperture(fx10: Int) = "f/%.1f".format(fx10 / 10.0)
}

/** What the HAL offers, read once per open. */
class CameraCaps(p: Camera.Parameters) {
    val pasmModes: List<Pasm> = p.list("pasm-mode-values").map(Pasm::of).ifEmpty { listOf(Pasm.AUTO) }
    val shutterSpeeds: List<String> = p.list("shutter-speed-values")
    val apertures: List<Int> = p.list("aperture-values").mapNotNull { it.toIntOrNull() }
    val isoValues: List<String> = p.list("iso-values").ifEmpty { listOf("auto") }
    val zoomRatios: List<Int> = (if (p.isZoomSupported) p.zoomRatios else null) ?: listOf(100)
    val maxZoom: Int = if (p.isZoomSupported) p.maxZoom else 0
    /** Continuous lever drive (tele-fast, wide-slow, stop…), once the HAL has it (§6.2). */
    val zoomActions: List<String> = p.list("zoom-action-values")
    val evMin = p.minExposureCompensation
    val evMax = p.maxExposureCompensation
    val evStep = p.exposureCompensationStep.takeIf { it > 0f } ?: (1f / 3f)
    val flashModes: List<String> = p.supportedFlashModes ?: emptyList()
    val focusModes: List<String> = p.supportedFocusModes ?: emptyList()
    val whiteBalances: List<String> = p.supportedWhiteBalance ?: emptyList()
    /** Empty until the HAL exposes the ISP's metering modes. */
    val meteringModes: List<String> = p.list("metering-values")
    val hasOis = p.list("image-stabilization-values").contains("on")
    /** The HAL's raw frame layout; null when the HAL cannot deliver RAW. */
    val rawLayout: RawLayout? = RawLayout.from(p)
    /** Per-shot RAW switch (§6.1). Without it RAW follows persist.vendor.camera.raw. */
    val hasRawSwitch = p.get("raw-capture-values") != null
    /** single, ae-bracket, hdr (the ISP's bracket modes, §6.8). */
    val captureModes: List<String> = p.list("capture-mode-values")
    val bracketEvs: List<String> = p.list("bracket-ev-values")
    /** Touch AF through focus-areas. */
    val touchAf = p.maxNumFocusAreas > 0
    /** center, multi, face: where the ISP looks when no point is touched. */
    val afAreas: List<String> = p.list("af-area-values")
    /** The HAL reports the ISP's live exposure during the preview (planned, §6.3). */
    val hasLiveExposure = p.get(LIVE_BV) != null
    /** Countdowns the ISP can blink the front lamp for by itself (seconds: 2, 5, 10). */
    val timerLeds: List<String> = p.list("timer-led-values").filter { it != "off" }
    val hasAfLamp = p.list("af-lamp-values").contains("on")
    private val pictureSizes = p.supportedPictureSizes.map { it.width to it.height }

    fun isoFor(pasm: Pasm) = if (pasm == Pasm.MANUAL) isoValues.filter { it != "auto" } else isoValues

    fun focal35mm(zoom: Int) = BASE_35MM_MM * zoomRatios[zoom.coerceIn(0, zoomRatios.lastIndex)] / 100.0

    fun nearestShutter(seconds: Double): String? =
        shutterSpeeds.minByOrNull { abs(ln(Apex.parseShutter(it) / seconds)) }

    /**
     * The ISP's readback as the time a camera prints: TV 800 is 1/256 s, which is the ISP's
     * 1/250 step. Snaps within 1/6 EV of a step; a time between steps (P/A) stays as read.
     */
    fun nominalSeconds(seconds: Double): Double {
        val step = nearestShutter(seconds)?.let(Apex::parseShutter) ?: return seconds
        return if (abs(log2(step / seconds)) <= 1.0 / 6) step else seconds
    }

    /** The largest picture size with the given aspect, e.g. "3:2" → 4608x3072. */
    fun pictureSizeFor(aspect: String): Pair<Int, Int>? {
        val (aw, ah) = aspect.split(':').map { it.toInt() }
        return pictureSizes.filter { (w, h) -> w * ah == h * aw }.maxByOrNull { it.first * it.second }
    }

    companion object {
        /** The lens at its widest is 23 mm in 35mm terms (4.1 mm real). */
        const val BASE_35MM_MM = 23.0
    }
}

/**
 * The raw-* parameters: a raw10be (GC100) or raw12be (GC200) frame with margins
 * (docs/CAMERA-APP.md §3.2).
 */
data class RawLayout(
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val black: Double,
    val white: Double,
    val cfa: String,
    /** Bits per pixel: 10 or 12. */
    val bits: Int,
) {
    val frameBytes get() = width.toLong() * height * bits / 8

    companion object {
        private val FORMAT_BITS = mapOf("raw10be" to 10, "raw12be" to 12)

        fun from(p: Camera.Parameters): RawLayout? {
            val bits = FORMAT_BITS[p.get("raw-format")] ?: return null
            val size = p.get("raw-size")?.split('x')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            val area = p.get("raw-active-area")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            if (size.size != 2 || area.size != 4) return null
            return RawLayout(
                width = size[0],
                height = size[1],
                left = area[0],
                top = area[1],
                right = area[2],
                bottom = area[3],
                black = p.get("raw-black-level")?.toDoubleOrNull() ?: 64.0,
                white = p.get("raw-white-level")?.toDoubleOrNull() ?: 1023.0,
                cfa = p.get("raw-cfa") ?: "grbg",
                bits = bits,
            )
        }
    }
}

/** The user's shooting setup. Immutable: the UI hands copies to the camera thread. */
data class ShotSettings(
    val pasm: Pasm = Pasm.AUTO,
    val shutter: String = "1/125",
    /** f-number x10, as the HAL's `aperture`. */
    val aperture: Int = 28,
    val iso: String = "auto",
    /** Exposure compensation in HAL steps (1/3 EV). */
    val ev: Int = 0,
    val flash: String = Camera.Parameters.FLASH_MODE_OFF,
    val focus: String = Camera.Parameters.FOCUS_MODE_AUTO,
    val whiteBalance: String = Camera.Parameters.WHITE_BALANCE_AUTO,
    val metering: String = "center",
    val ois: Boolean = true,
    val aspect: String = "4:3",
    /** single, or aeb / hdr (the ISP's bracket modes). */
    val drive: String = "single",
    /** Self-timer seconds before the shot (0 = off); combines with any drive mode. */
    val timer: Int = 0,
    /** EV step between bracket frames, one of bracket-ev-values. */
    val bracketEv: String = "1.0",
    val jpegQuality: Int = 95,
    /** Also save the RAW frame as a DNG. */
    val raw: Boolean = false,
    /** af-area when no point is touched: center, multi or face. */
    val afArea: String = "center",
    /** Let the ISP light the AF assist lamp when it focuses in low light (a setting, not a shot choice). */
    val afLamp: Boolean = true,
)

/** What the ISP used for the last shot (the exif-* parameters). */
data class ShotInfo(
    val iso: Int?,
    val exposureSeconds: Double?,
    val fNumber: Double?,
    val focalMm: Double?,
    val focal35mm: Int?,
    val biasEv: Double?,
    val ispFlash: Int?,
) {
    companion object {
        fun from(p: Camera.Parameters) = ShotInfo(
            iso = p.int("exif-iso"),
            exposureSeconds = p.int("exif-tv")?.let(Apex::tvToSeconds),
            fNumber = p.int("exif-av")?.let(Apex::avToFNumber),
            focalMm = p.int("exif-fl")?.let { it / 1000.0 },
            focal35mm = p.int("exif-fl-35mm")?.let { (it / 100.0).roundToInt() },
            biasEv = p.int("exif-ebv")?.let { it / 100.0 },
            ispFlash = p.int("exif-flash"),
        )

        /**
         * Frame [index] of a bracket, from `bracket-exif` ("iso,tv,av,bv,ebv|…" per frame, in
         * delivery order; APEX x100 like exif-*; the HAL's parameter strings cannot hold ';').
         * Null when the HAL does not provide it.
         */
        fun fromBracket(p: Camera.Parameters, index: Int): ShotInfo? {
            val frame = p.get("bracket-exif")?.split('|')?.getOrNull(index) ?: return null
            val v = frame.split(',').map { it.trim().toIntOrNull() }
            if (v.size < 5) return null
            val all = from(p)
            return all.copy(
                iso = v[0],
                exposureSeconds = v[1]?.let(Apex::tvToSeconds),
                fNumber = v[2]?.let(Apex::avToFNumber),
                biasEv = v[4]?.let { it / 100.0 },
            )
        }
    }
}

/** Where a JPEG sits in a bracket sequence; a single shot is frame 0 of 1. */
data class FrameInfo(val index: Int, val count: Int, val ev: String?) {
    companion object {
        val SINGLE = FrameInfo(0, 1, null)
    }
}

/** Polled from the HAL during the preview. */
data class LiveStatus(
    val flashUp: Boolean?,
    /** The exposure the ISP is choosing now, when the HAL reports it (APEX). */
    val liveTv: Double?,
    val liveAv: Double?,
    val liveIso: Int?,
    val liveBv: Double?,
    /** ae (the ISP's exposure), lv (its scene meter, used in M, 1 EV steps) or none (too dark). */
    val bvSource: String?,
    /** Faces the ISP sees, in face AF mode only (no positions). */
    val faceCount: Int?,
    /**
     * The exposure the ISP plans for the still (APEX TV, f-number, ISO): unlike the preview's
     * (capped at 1/30 by its 30 fps, and 0 when too dark) it holds in the dark too.
     */
    val shotTv: Double? = null,
    val shotFNumber: Double? = null,
    val shotIso: Int? = null,
) {
    val tooDark get() = bvSource == "none"

    companion object {
        fun from(p: Camera.Parameters): LiveStatus {
            val smart = SmartRead1.parse(p)
            return LiveStatus(
                flashUp = p.get("flash-popup")?.let { it == "up" },
                liveTv = p.int("live-tv")?.let { it / 100.0 },
                liveAv = p.int("live-av")?.let { it / 100.0 },
                liveIso = p.int("live-iso"),
                liveBv = p.int(LIVE_BV)?.let { it / 100.0 },
                bvSource = p.get("live-bv-source"),
                faceCount = p.int("face-count"),
                shotTv = p.int("live-shot-tv")?.let { it / 100.0 } ?: smart?.tv,
                shotFNumber = p.int("live-shot-av")?.let { 2.0.pow(it / 200.0) } ?: smart?.fNumber,
                shotIso = p.int("live-shot-iso") ?: smart?.iso,
            )
        }
    }
}

/**
 * The M9MO's SMART_READ1 register, as Samsung's camera app decodes it (deodexed, docs/
 * CAMERA-APP.md §3.2), read from the HAL's m9mo-live-raw until the HAL publishes live-shot-*.
 * Checked on the device 2026-11-03: 1/40 f/2.8 ISO 200 in room light, and 1 s f/2.8 ISO 400 in
 * the dark, matching the shot's EXIF.
 */
private class SmartRead1(val tv: Double?, val fNumber: Double?, val iso: Int?) {
    companion object {
        private val ISO_STEPS = intArrayOf(100, 200, 400, 800, 1600, 3200)

        fun parse(p: Camera.Parameters): SmartRead1? {
            val v = p.get("m9mo-live-raw")?.split(',')?.getOrNull(4)?.trim()?.toLongOrNull() ?: return null
            if (v == 0L) return null
            val r = v and 0xffffffffL
            // Shutter: 1/3-stop steps from 16 s (index 1)
            val index = ((r shr 24) and 0x3f).toInt() + 1
            val tv = if (index > 1) (index - 1) / 3.0 - 4 else null
            // Aperture: f-number × 10 as two BCD digits
            val bcd = ((r shr 16) and 0xff).toInt()
            val f10 = (bcd shr 4) * 10 + (bcd and 0xf)
            val fNumber = if (f10 in 10..99) f10 / 10.0 else null
            val iso = ISO_STEPS.getOrNull(((r shr 13) and 0x7).toInt())
            return SmartRead1(tv, fNumber, iso)
        }
    }
}

private const val LIVE_BV = "live-bv"

private fun Camera.Parameters.list(key: String): List<String> =
    get(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

private fun Camera.Parameters.int(key: String): Int? = get(key)?.trim()?.toIntOrNull()
