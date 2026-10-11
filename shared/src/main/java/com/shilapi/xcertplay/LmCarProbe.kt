package com.shilapi.xcertplay

import android.util.Log

/**
 * Tells a Leapmotor head unit apart from any other car at runtime, so one APK can drive both lamp
 * transports without a build-time flag and without ever starting the BYD ADB worker on a car that
 * does not answer it.
 *
 * Evidence (reverse-engineered from the head unit's own `VehicleSetting.apk`,
 * `setting/system/SystemFragment.smali` — `getCarType()`): the OEM app identifies the car by reading
 * the file `/data/leapmotor/CarType` (`util/CommonTools.smali:789` — `ReadBytesFromFile`) and taking
 * the byte at offset 7 as the model code (1..5 = the S01 variants). The path and its `leapmotor`
 * owner are what we key on; the model code is a bonus we only log.
 *
 * The OEM's own reader does `array-length` on the result without a null guard, so it would crash on
 * a missing file. We therefore never read first: [isLeapmotor] only checks that the file exists and
 * is a regular file, which is exactly the guard `ReadBytesFromFile` applies internally, and a
 * missing file simply means "not a Leapmotor".
 *
 * `/data/leapmotor/` is world-readable on these units, so no permission is needed. The secondary
 * AIDL route (`LpDeviceCtrlService` → `Leapmotor.DeviceCtrlAidlService` in
 * `com.leapmotor.systemupdate` → `IDeviceCtrlInterface.getCarType()`, transaction 0x15) is
 * deliberately not used: it is asynchronous, needs a `<queries>` package-visibility entry, and
 * answers the same question.
 */
internal object LmCarProbe {
    /** File the OEM app reads to identify the car. */
    const val CAR_TYPE_FILE = "/data/leapmotor/CarType"

    /** Directory that only a Leapmotor head unit carries. */
    const val CAR_TYPE_DIR = "/data/leapmotor"

    /** Model codes at byte 7 of [CAR_TYPE_FILE]; logged for diagnostics only. */
    private val MODELS = mapOf(
        1 to "S01 380 standard", 2 to "S01 380 PRO",
        3 to "S01 460 standard", 4 to "S01 460 PRO",
        5 to "S01 460 standard (smart option)",
    )

    @Volatile private var cached: Boolean? = null

    /** True when this head unit is a Leapmotor car. Cached: the file does not appear at runtime. */
    fun isLeapmotor(): Boolean = cached ?: probe().also { cached = it }

    private fun probe(): Boolean {
        val found = runCatching { java.io.File(CAR_TYPE_FILE).isFile }.getOrDefault(false)
        val model = if (found) runCatching { modelCode() }.getOrNull() else null
        Log.i("DiPlay-Ambient", "leapmotor probe file=$found model=${model ?: 0} path=$CAR_TYPE_FILE")
        return found
    }

    /** Model code at offset 7, or null when the file is shorter than the OEM expects. */
    private fun modelCode(): Int? {
        val bytes = java.io.File(CAR_TYPE_FILE).readBytes()
        return if (bytes.size >= 16) bytes[7].toInt() and 0xff else null
    }

    /** Human-readable model, for the diagnostic line only. */
    fun modelName(): String? = runCatching { MODELS[modelCode()] }.getOrNull()

    /** Test seam: forget the cached answer. */
    internal fun resetForTests() { cached = null }
}
