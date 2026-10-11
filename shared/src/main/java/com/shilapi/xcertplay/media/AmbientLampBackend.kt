package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.LmAmbientMode

/**
 * Which interior-lamp transport this build drives.
 *
 * The two car lines need different transports for the same feature: BYD exposes the lamp through a
 * privileged ADB shell, Leapmotor consumes a broadcast on its shared CAN action. Both cars run the
 * same source tree, so the transport is fixed per build channel rather than probed at runtime —
 * probing would start the ADB worker on a car that never answers it ([AmbientMusicController.lampChannel]).
 */
internal enum class AmbientLampChannel { BYD_ADB, LEAPMOTOR_BROADCAST }

/**
 * The lamp state the Leapmotor protocol understands. The BYD path works in
 * `(colorIndex 1..31, brightness 1..6, area 1..3)`; the Leapmotor path works in
 * `(mode, angleDeg 0..10, true RGB)`. `tick()` produces a BYD-shaped triple for the first and a
 * [LeapmotorLamp] for the second, and each backend ignores the shape it does not use.
 */
internal data class LeapmotorLamp(val mode: LmAmbientMode, val deg: Int, val rgb: Int)

/** What the controller hands its lamp transport on every tick. */
internal data class AmbientLampFrame(val area: Int, val colorIndex: Int, val brightness: Int,
    val leapmotor: LeapmotorLamp?)

/**
 * One lamp transport. Deliberately small: the controller owns pacing and decision-making, the
 * transport owns talking to the car.
 */
internal interface AmbientLampSink {
    /** Called at most once per tick, already paced by the controller. */
    fun render(frame: AmbientLampFrame)

    /** Hands the lamps back to the car's own control. */
    fun restore()

    /**
     * True once control is established, so [AmbientMusicController.checkSupport] can answer without
     * re-probing. A broadcast needs no handshake, so the Leapmotor sink is always ready.
     */
    fun isReady(): Boolean = true

    /** Rebuilds the transport after a failed tick. A stateless broadcast has nothing to rebuild. */
    fun recover() {}
}
