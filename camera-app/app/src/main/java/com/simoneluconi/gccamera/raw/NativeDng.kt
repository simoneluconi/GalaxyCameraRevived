package com.simoneluconi.gccamera.raw

/** gccore's DNG writer (camera-app/gccore). The library loads on first use, not at start-up. */
object NativeDng {
    init {
        System.loadLibrary("gcjni")
    }

    /**
     * Writes [raw] (the HAL's raw10be or raw12be frame) as a DNG to [fd]. Returns null on success, or
     * the error. See gcjni.cpp for the array layouts.
     */
    @JvmStatic
    external fun write(
        raw: ByteArray,
        layout: IntArray,
        levels: DoubleArray,
        cfa: String,
        exposure: DoubleArray,
        dateTime: String,
        offsetTime: String,
        software: String,
        artist: String?,
        copyright: String?,
        /** TIFF Model, "EK-GC100"; null keeps gccore's default. */
        model: String?,
        /** XMP rdf:Description namespaces and attributes, the same as the JPEG's; null for just a rating. */
        xmp: String?,
        /**
         * The geotag: latitude, longitude, has altitude (0/1), altitude m, fix time ms UTC,
         * accuracy m (0 = unknown); null for none.
         */
        gps: DoubleArray?,
        /** The ISP JPEG of the same shot, embedded as the full-size preview; null for none. */
        preview: ByteArray?,
        fd: Int,
    ): String?
}
