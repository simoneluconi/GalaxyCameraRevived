package com.simoneluconi.gccamera.settings

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import com.simoneluconi.gccamera.camera.CameraModel
import com.simoneluconi.gccamera.camera.Pasm
import com.simoneluconi.gccamera.camera.ShotSettings

/**
 * Typed access to the settings. The file is the one androidx.preference uses by default,
 * so SettingsActivity edits the same values without the viewfinder loading AndroidX.
 */
class Prefs(private val context: Context) {
    val sp: SharedPreferences =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    val saveToInternal get() = sp.getString(STORAGE_LOCATION, "sd") == "internal"
    val folderName get() = sp.getString(FOLDER_NAME, null)?.trim()?.ifEmpty { null } ?: CameraModel.shortName
    val geotag get() = sp.getBoolean(GEOTAG, false)
    val geotagMaxAgeMs get() = (sp.getString(GEOTAG_MAX_AGE, "5")?.toLongOrNull() ?: 5L) * 60_000L
    val jpegQuality get() = sp.getString(JPEG_QUALITY, "95")?.toIntOrNull() ?: 95
    val raw get() = sp.getBoolean(RAW, false)
    val grid get() = sp.getString(GRID, "thirds") ?: "thirds"
    val level get() = sp.getBoolean(LEVEL, true)
    /** The live histogram over the viewfinder. */
    val histogram get() = sp.getBoolean(HISTOGRAM, false)
    val reviewMs get() = (sp.getString(REVIEW, "2")?.toLongOrNull() ?: 2L) * 1000L
    /** Idle time after which the viewfinder lets the screen go off (and the lens in); 0 = never. */
    val autoOffMs get() = (sp.getString(AUTO_OFF, "2")?.toLongOrNull() ?: 2L) * 60_000L
    val shutterSound get() = sp.getBoolean(SHUTTER_SOUND, true)
    val afBeep get() = sp.getBoolean(AF_BEEP, false)
    val afLamp get() = sp.getBoolean(AF_LAMP, true)
    val artist get() = sp.getString(ARTIST, null)?.trim()?.ifEmpty { null }
    val copyright get() = sp.getString(COPYRIGHT, null)?.trim()?.ifEmpty { null }

    /** The flash mode used whenever the pop-up flash is raised: auto or on. */
    var flashUpMode: String
        get() = sp.getString(FLASH_UP_MODE, "auto") ?: "auto"
        set(v) = sp.edit().putString(FLASH_UP_MODE, v).apply()

    /** Average size of a saved shot, for the "shots remaining" estimate. */
    var averageShotBytes: Long
        get() = sp.getLong(AVG_SHOT_BYTES, 5_500_000L)
        set(v) = sp.edit().putLong(AVG_SHOT_BYTES, v).apply()

    /** Takes the next file number, 1..9999, wrapping like a camera's DCF counter. */
    fun nextFileNumber(): Int {
        val n = sp.getInt(FILE_COUNTER, 0) % 9999 + 1
        sp.edit().putInt(FILE_COUNTER, n).apply()
        return n
    }

    fun peekFileNumber() = sp.getInt(FILE_COUNTER, 0) % 9999 + 1

    fun loadShot(): ShotSettings {
        val d = ShotSettings()
        return ShotSettings(
            pasm = Pasm.of(sp.getString(S_PASM, d.pasm.value)),
            shutter = sp.getString(S_SHUTTER, d.shutter)!!,
            aperture = sp.getInt(S_APERTURE, d.aperture),
            iso = sp.getString(S_ISO, d.iso)!!,
            ev = sp.getInt(S_EV, d.ev),
            flash = sp.getString(S_FLASH, d.flash)!!,
            focus = sp.getString(S_FOCUS, d.focus)!!,
            whiteBalance = sp.getString(S_WB, d.whiteBalance)!!,
            metering = sp.getString(S_METERING, d.metering)!!,
            ois = sp.getBoolean(S_OIS, d.ois),
            aspect = sp.getString(S_ASPECT, d.aspect)!!,
            // "timer2"/"timer10" were drive values before the self-timer had its own setting
            drive = sp.getString(S_DRIVE, d.drive)!!.let { if (it.startsWith("timer")) "single" else it },
            timer = sp.getInt(S_TIMER, when (sp.getString(S_DRIVE, null)) {
                "timer2" -> 2
                "timer10" -> 10
                else -> d.timer
            }),
            bracketEv = sp.getString(S_BRACKET_EV, d.bracketEv)!!,
            afArea = sp.getString(S_AF_AREA, d.afArea)!!,
            jpegQuality = jpegQuality,
            raw = raw,
            afLamp = afLamp,
        )
    }

    fun saveShot(s: ShotSettings) {
        sp.edit()
            .putString(S_PASM, s.pasm.value)
            .putString(S_SHUTTER, s.shutter)
            .putInt(S_APERTURE, s.aperture)
            .putString(S_ISO, s.iso)
            .putInt(S_EV, s.ev)
            .putString(S_FLASH, s.flash)
            .putString(S_FOCUS, s.focus)
            .putString(S_WB, s.whiteBalance)
            .putString(S_METERING, s.metering)
            .putBoolean(S_OIS, s.ois)
            .putString(S_ASPECT, s.aspect)
            .putString(S_DRIVE, s.drive)
            .putInt(S_TIMER, s.timer)
            .putString(S_BRACKET_EV, s.bracketEv)
            .putString(S_AF_AREA, s.afArea)
            .putBoolean(RAW, s.raw)
            .apply()
    }

    /** The app's own day/night choice; Android keeps it per app and recreates activities. */
    fun applyTheme() {
        val mode = when (sp.getString(THEME, "light")) {
            "dark" -> UiModeManager.MODE_NIGHT_YES
            "system" -> UiModeManager.MODE_NIGHT_AUTO
            else -> UiModeManager.MODE_NIGHT_NO
        }
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    companion object {
        const val STORAGE_LOCATION = "storage_location"
        const val STORAGE_INFO = "storage_info"
        const val FORMAT_SD = "format_sd"
        const val FOLDER_NAME = "folder_name"
        const val FILE_COUNTER = "file_counter"
        const val FILE_COUNTER_RESET = "file_counter_reset"
        const val GEOTAG = "geotag"
        const val GEOTAG_MAX_AGE = "geotag_max_age"
        const val JPEG_ENGINE = "jpeg_engine"
        const val RAW = "raw"
        const val JPEG_QUALITY = "jpeg_quality"
        const val DEVELOP_WHEN = "develop_when"
        const val THEME = "theme"
        const val GRID = "grid"
        const val LEVEL = "level"
        // A new key, off by default: the old "histogram" (a panel widget, on by default) is dropped
        const val HISTOGRAM = "histogram_overlay"
        const val REVIEW = "review"
        const val AUTO_OFF = "auto_off"
        const val SHUTTER_SOUND = "shutter_sound"
        const val AF_BEEP = "af_beep"
        const val AF_LAMP = "af_lamp"
        const val ARTIST = "artist"
        const val COPYRIGHT = "copyright"
        const val ANDROID_SETTINGS = "android_settings"
        const val RESET = "reset"
        const val ABOUT = "about"
        private const val AVG_SHOT_BYTES = "avg_shot_bytes"
        private const val FLASH_UP_MODE = "flash_up_mode"

        // The last shooting setup, restored at the next start like a real camera
        private const val S_PASM = "s_pasm"
        private const val S_SHUTTER = "s_shutter"
        private const val S_APERTURE = "s_aperture"
        private const val S_ISO = "s_iso"
        private const val S_EV = "s_ev"
        private const val S_FLASH = "s_flash"
        private const val S_FOCUS = "s_focus"
        private const val S_WB = "s_wb"
        private const val S_METERING = "s_metering"
        private const val S_OIS = "s_ois"
        private const val S_ASPECT = "s_aspect"
        private const val S_DRIVE = "s_drive"
        private const val S_BRACKET_EV = "s_bracket_ev"
        private const val S_TIMER = "s_timer"
        private const val S_AF_AREA = "s_af_area"
    }
}
