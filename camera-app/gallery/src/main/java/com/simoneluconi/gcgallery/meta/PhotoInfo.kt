package com.simoneluconi.gcgallery.meta

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.exifinterface.media.ExifInterface
import com.simoneluconi.gcgallery.R
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.image.Fd
import com.simoneluconi.gcgallery.image.Orientation
import com.simoneluconi.gcgallery.image.Tiff
import com.simoneluconi.gcgallery.image.Xmp
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the info panel shows for one photo, read from its EXIF, XMP and (for a pair) its DNG. */
class PhotoInfo(
    /** "1/250  f/4.0  ISO 100  +0.3 EV": the playback screen's top line, or null without exposure tags. */
    val exposureLine: String?,
    /** The viewer bar's subtitle: shutter, aperture, ISO only. */
    val barLine: String?,
    val sections: List<Section>,
    val latitude: Double?,
    val longitude: Double?,
    val rating: Int?,
) {
    class Row(val label: String, val value: String)
    class Section(val title: String, val rows: List<Row>, val isLocation: Boolean = false)

    companion object {

        fun read(context: Context, p: Photo): PhotoInfo? {
            val resolver = context.contentResolver
            // GPS tags of other apps' photos are blanked unless the original is asked for
            val canLocate = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
            val uri = if (canLocate && p.inMediaStore) MediaStore.setRequireOriginal(p.uri) else p.uri
            val exif = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
            }.getOrNull() ?: return null
            val raw = p.raw?.let { readRaw(context, it) }
            return Builder(context, p, exif, raw).build()
        }

        /** DNG facts ExifInterface does not expose. */
        private fun readRaw(context: Context, dng: Photo): List<Row>? = runCatching {
            context.contentResolver.openFileDescriptor(dng.uri, "r")?.use { pfd ->
                val tiff = Tiff.at(Fd(pfd.fileDescriptor), 0) ?: return null
                val rawIfd = tiff.allIfds().firstOrNull { ifd ->
                    ifd.find(Tiff.TAG_PHOTOMETRIC)?.let { tiff.ints(it, 1).firstOrNull() } == Tiff.PHOTOMETRIC_CFA.toLong()
                } ?: return null
                val rows = ArrayList<Row>()
                rows.add(Row(context.getString(R.string.info_file_size), Formatter.formatShortFileSize(context, dng.size)))
                val w = rawIfd.find(Tiff.TAG_IMAGE_WIDTH)?.let { tiff.ints(it, 1).firstOrNull() }
                val h = rawIfd.find(Tiff.TAG_IMAGE_LENGTH)?.let { tiff.ints(it, 1).firstOrNull() }
                if (w != null && h != null) rows.add(Row(context.getString(R.string.info_sensor), "$w × $h"))
                rawIfd.find(Tiff.TAG_CFA_PATTERN)?.let { e ->
                    val names = "RGBCMY"
                    val pattern = tiff.bytes(e, 4).joinToString("") { b -> names.getOrNull(b.toInt())?.toString() ?: "?" }
                    rows.add(Row(context.getString(R.string.info_cfa), pattern))
                }
                val black = rawIfd.find(Tiff.TAG_BLACK_LEVEL)?.let { tiff.doubles(it, 1).firstOrNull() }
                val white = rawIfd.find(Tiff.TAG_WHITE_LEVEL)?.let { tiff.ints(it, 1).firstOrNull() }
                if (black != null || white != null) {
                    rows.add(Row(context.getString(R.string.info_levels), "${black?.roundToInt() ?: "?"} / ${white ?: "?"}"))
                }
                rawIfd.find(Tiff.TAG_ACTIVE_AREA)?.let { e ->
                    val a = tiff.ints(e, 4)
                    if (a.size == 4) rows.add(Row(context.getString(R.string.info_active_area), "${a[1]},${a[0]} – ${a[3]},${a[2]}"))
                }
                rows
            }
        }.getOrNull()
    }

    private class Builder(val c: Context, val p: Photo, val exif: ExifInterface, val raw: List<Row>?) {

        fun s(id: Int) = c.getString(id)
        fun tag(t: String) = exif.getAttribute(t)?.trim()?.takeIf { it.isNotEmpty() }
        fun dbl(t: String) = exif.getAttributeDouble(t, Double.NaN).takeIf { !it.isNaN() }
        fun int(t: String) = exif.getAttributeInt(t, -1).takeIf { it >= 0 }

        fun build(): PhotoInfo {
            val xmp = tag(ExifInterface.TAG_XMP)
            val gc = Xmp.gcFields(xmp)
            val sections = ArrayList<Section>()

            val exposure = dbl(ExifInterface.TAG_EXPOSURE_TIME)?.takeIf { it > 0 }
            val fNumber = dbl(ExifInterface.TAG_F_NUMBER)?.takeIf { it > 0 }
            val iso = int(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.takeIf { it > 0 }
            val bias = dbl(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)
            val focal = dbl(ExifInterface.TAG_FOCAL_LENGTH)?.takeIf { it > 0 }
            val focal35 = int(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it > 0 }
            val wideFocal = lensWide()

            val top = listOfNotNull(
                exposure?.let(::shutter),
                fNumber?.let { "f/%.1f".format(Locale.US, it) },
                iso?.let { "ISO $it" },
                bias?.takeIf { abs(it) >= 0.05 }?.let { "%+.1f EV".format(Locale.US, it) },
            ).joinToString("   ").ifEmpty { null }

            // Exposure
            rows {
                exposure?.let { add(s(R.string.info_shutter), shutter(it)) }
                fNumber?.let { add(s(R.string.info_aperture), "f/%.1f".format(Locale.US, it)) }
                iso?.let { add(s(R.string.info_iso), it.toString()) }
                bias?.let { add(s(R.string.info_bias), "%+.1f EV".format(Locale.US, it)) }
                if (exposure != null && fNumber != null && iso != null) {
                    val ev = ln(fNumber * fNumber / exposure) / ln(2.0) - ln(iso / 100.0) / ln(2.0)
                    add(s(R.string.info_ev100), "%.1f".format(Locale.US, ev))
                }
                (gc["Pasm"]?.let(::pasmName) ?: int(ExifInterface.TAG_EXPOSURE_PROGRAM)?.let(::programName))?.let { add(s(R.string.info_mode), it) }
                int(ExifInterface.TAG_METERING_MODE)?.let(::meteringName)?.let { add(s(R.string.info_metering), it) }
                int(ExifInterface.TAG_WHITE_BALANCE)?.let { add(s(R.string.info_wb), s(if (it == 0) R.string.value_auto else R.string.value_manual)) }
                flash()?.let { add(s(R.string.info_flash), it) }
            }?.let { sections.add(Section(s(R.string.section_exposure), it)) }

            // Lens
            rows {
                focal?.let { f ->
                    val eq = focal35?.let { " ($it mm eq.)" } ?: ""
                    add(s(R.string.info_focal), "%.1f mm".format(Locale.US, f) + eq)
                    wideFocal?.let { w -> add(s(R.string.info_zoom), "%.1f×".format(Locale.US, f / w)) }
                }
                tag(ExifInterface.TAG_LENS_MODEL)?.let { add(s(R.string.info_lens), it) }
                dbl(ExifInterface.TAG_MAX_APERTURE_VALUE)?.let { apex ->
                    add(s(R.string.info_max_aperture), "f/%.1f".format(Locale.US, Math.pow(2.0, apex / 2)))
                }
            }?.let { sections.add(Section(s(R.string.section_lens), it)) }

            // Shot: GC Camera's XMP fields
            rows {
                gc["OIS"]?.let { add(s(R.string.info_ois), s(if (it.equals("true", true)) R.string.value_on else R.string.value_off)) }
                gc["AFMode"]?.let { add(s(R.string.info_af_mode), it) }
                gc["AFArea"]?.let { add(s(R.string.info_af_area), it) }
                gc["Drive"]?.let { d ->
                    val ev = gc["BracketEV"]?.let { " ±$it EV" } ?: ""
                    add(s(R.string.info_drive), d + ev)
                }
                val frame = gc["BracketFrame"]
                if (frame != null) add(s(R.string.info_bracket_frame), frame + (gc["BracketCount"]?.let { " / $it" } ?: ""))
                gc["Timer"]?.takeIf { it != "0" }?.let { add(s(R.string.info_timer), "$it s") }
                gc["FlashPopup"]?.let { add(s(R.string.info_flash_popup), it) }
                gc["ZoomStep"]?.let { z -> add(s(R.string.info_zoom_step), z + (gc["ZoomMax"]?.let { " / $it" } ?: "")) }
                add(s(R.string.info_developed), gc["Developed"] ?: s(R.string.value_isp_jpeg))
                gc["DevelopParams"]?.let { add(s(R.string.info_develop_params), it.replace(";", "  ")) }
            }?.takeIf { gc.isNotEmpty() }?.let { sections.add(Section(s(R.string.section_shot), it)) }

            // Location
            val latLong = exif.latLong
            rows {
                if (latLong != null) {
                    add(s(R.string.info_coordinates), "%.6f, %.6f".format(Locale.US, latLong[0], latLong[1]))
                    add(s(R.string.info_dms), dms(latLong[0], "N", "S") + "  " + dms(latLong[1], "E", "W"))
                    exif.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { add(s(R.string.info_altitude), "%.0f m".format(Locale.US, it)) }
                    dbl(ExifInterface.TAG_GPS_H_POSITIONING_ERROR)?.let { add(s(R.string.info_accuracy), "± %.0f m".format(Locale.US, it)) }
                        ?: dbl(ExifInterface.TAG_GPS_DOP)?.let { add(s(R.string.info_dop), "%.1f".format(Locale.US, it)) }
                    dbl(ExifInterface.TAG_GPS_IMG_DIRECTION)?.let { d ->
                        val ref = if (tag(ExifInterface.TAG_GPS_IMG_DIRECTION_REF) == "T") s(R.string.value_true_north) else s(R.string.value_magnetic)
                        add(s(R.string.info_direction), "%.0f° %s (%s)".format(Locale.US, d, compass(d), ref))
                    }
                    gpsTime()?.let { add(s(R.string.info_gps_time), it) }
                }
            }?.let { sections.add(Section(s(R.string.section_location), it, isLocation = true)) }

            // File
            rows {
                add(s(R.string.info_name), p.name)
                if (p.folder.isNotEmpty()) add(s(R.string.info_folder), p.folder.trimEnd('/'))
                if (p.volume.isNotEmpty()) {
                    add(s(R.string.info_volume), s(if (p.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) R.string.value_internal else R.string.value_sd))
                }
                taken()?.let { add(s(R.string.info_taken), it) }
                if (p.dateModified > 0) add(s(R.string.info_modified), stamp(p.dateModified * 1000))
                if (p.size > 0) add(s(R.string.info_file_size), Formatter.formatShortFileSize(c, p.size))
                val w = int(ExifInterface.TAG_PIXEL_X_DIMENSION)?.takeIf { it > 0 } ?: int(ExifInterface.TAG_IMAGE_WIDTH)?.takeIf { it > 0 } ?: p.width
                val h = int(ExifInterface.TAG_PIXEL_Y_DIMENSION)?.takeIf { it > 0 } ?: int(ExifInterface.TAG_IMAGE_LENGTH)?.takeIf { it > 0 } ?: p.height
                if (w > 0 && h > 0) add(s(R.string.info_dimensions), "$w × $h  (%.1f MP)".format(Locale.US, w * h / 1e6))
                val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                add(s(R.string.info_orientation), orientationName(o))
                int(ExifInterface.TAG_COLOR_SPACE)?.let { add(s(R.string.info_colour_space), if (it == 1) "sRGB" else s(R.string.value_uncalibrated)) }
                listOfNotNull(tag(ExifInterface.TAG_MAKE), tag(ExifInterface.TAG_MODEL)).joinToString(" ").ifEmpty { null }
                    ?.let { add(s(R.string.info_camera), it) }
                tag(ExifInterface.TAG_SOFTWARE)?.let { add(s(R.string.info_software), it) }
                tag(ExifInterface.TAG_ARTIST)?.let { add(s(R.string.info_artist), it) }
                tag(ExifInterface.TAG_COPYRIGHT)?.let { add(s(R.string.info_copyright), it) }
            }?.let { sections.add(Section(s(R.string.section_file), it)) }

            raw?.takeIf { it.isNotEmpty() }?.let { sections.add(Section(s(R.string.section_raw), it)) }

            val bar = listOfNotNull(
                exposure?.let(::shutter),
                fNumber?.let { "f/%.1f".format(Locale.US, it) },
                iso?.let { "ISO $it" },
            ).joinToString("  ").ifEmpty { null }

            return PhotoInfo(top, bar, sections, latLong?.get(0), latLong?.get(1), Xmp.rating(xmp))
        }

        /** The wide end of the zoom, from LensSpecification (GC Camera writes 4.1-86.1mm). */
        fun lensWide(): Double? {
            val spec = tag(ExifInterface.TAG_LENS_SPECIFICATION) ?: return null
            val first = spec.split(',').firstOrNull() ?: return null
            val parts = first.split('/')
            val v = if (parts.size == 2) parts[0].toDoubleOrNull()?.div(parts[1].toDoubleOrNull() ?: return null) else first.toDoubleOrNull()
            return v?.takeIf { it > 0 }
        }

        fun shutter(t: Double): String = when {
            t >= 0.5 -> "%.1f s".format(Locale.US, t).replace(".0 s", " s")
            else -> "1/${(1 / t).roundToLong()}"
        }

        fun flash(): String? {
            val v = int(ExifInterface.TAG_FLASH) ?: return null
            if (v and 0x20 != 0) return s(R.string.value_no_flash)
            val fired = v and 1 != 0
            val mode = when ((v shr 3) and 3) {
                1 -> s(R.string.value_on)
                2 -> s(R.string.value_off)
                3 -> s(R.string.value_auto)
                else -> null
            }
            val state = s(if (fired) R.string.value_fired else R.string.value_not_fired)
            return if (mode != null) "$state ($mode)" else state
        }

        fun taken(): String? {
            val dt = tag(ExifInterface.TAG_DATETIME_ORIGINAL) ?: tag(ExifInterface.TAG_DATETIME) ?: return p.dateTaken.takeIf { it > 0 }?.let(::stamp)
            val parsed = runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dt) }.getOrNull()
            val text = parsed?.let { stamp(it.time) } ?: dt
            val offset = tag(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: tag(ExifInterface.TAG_OFFSET_TIME)
            return if (offset != null) "$text (UTC$offset)" else text
        }

        /** GPS date and time are UTC, shown in local time. */
        fun gpsTime(): String? {
            val date = tag(ExifInterface.TAG_GPS_DATESTAMP) ?: return null
            val time = tag(ExifInterface.TAG_GPS_TIMESTAMP) ?: return null
            val f = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            return runCatching { f.parse("$date $time") }.getOrNull()?.let { stamp(it.time) }
        }

        fun stamp(ms: Long): String {
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMMyyyyHHmmss")
            return DateFormat.format(pattern, ms).toString()
        }

        fun dms(v: Double, pos: String, neg: String): String {
            val a = abs(v)
            val d = a.toInt()
            val m = ((a - d) * 60).toInt()
            val sec = ((a - d) * 60 - m) * 60
            return "%d°%02d'%04.1f\"%s".format(Locale.US, d, m, sec, if (v >= 0) pos else neg)
        }

        fun compass(deg: Double): String {
            val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            return names[(((deg % 360) + 360 + 22.5) / 45).toInt() % 8]
        }

        fun programName(v: Int) = when (v) {
            1 -> "M"
            2 -> "P"
            3 -> "A"
            4 -> "S"
            else -> null
        }

        fun pasmName(v: String) = when (v.lowercase()) {
            "program" -> "P"
            "aperture" -> "A"
            "shutter" -> "S"
            "manual" -> "M"
            "auto" -> s(R.string.value_auto)
            else -> v
        }

        fun meteringName(v: Int) = when (v) {
            1 -> s(R.string.value_metering_average)
            2 -> s(R.string.value_metering_centre)
            3 -> s(R.string.value_metering_spot)
            5 -> s(R.string.value_metering_matrix)
            else -> null
        }

        fun orientationName(o: Int): String {
            val deg = Orientation.degrees(o)
            val turn = if (deg == 0) s(R.string.value_upright) else "$deg°"
            return if (Orientation.mirrored(o)) "$turn, ${s(R.string.value_mirrored)}" else turn
        }

        /** Collects rows; null when there are none, so empty sections are hidden. */
        inline fun rows(block: RowList.() -> Unit): List<Row>? = RowList().apply(block).takeIf { it.isNotEmpty() }
    }

    class RowList : ArrayList<Row>() {
        fun add(label: String, value: String) = add(Row(label, value))
    }
}
