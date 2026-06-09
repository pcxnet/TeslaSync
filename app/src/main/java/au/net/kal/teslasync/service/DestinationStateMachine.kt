package au.net.kal.teslasync.service

import au.net.kal.teslasync.data.Destination
import kotlin.math.roundToLong

/**
 * Decides when to fire Waze. Pure logic, no Android deps -> unit-testable on the JVM.
 *
 * Rules (from the design):
 *  - Fire when the active destination CHANGES to a new, non-null value.
 *  - Do NOT re-fire while the same destination stays set (we'd nag the user every poll).
 *  - When the route is cleared (null), reset state, so re-selecting the same place later
 *    fires again.
 *  - On arm: if the caller does NOT want to fire an already-set destination, it calls
 *    [prime] first to seed the current destination without firing.
 *
 * Coordinates are compared at a fixed decimal [precision] so floating-point jitter from
 * the API doesn't look like a "new" destination.
 */
class DestinationStateMachine(
    private val precision: Int = 5,
) {
    private var lastFiredKey: String? = null

    /** Seed state without firing (used on arm when fireOnArm == false). */
    fun prime(destination: Destination?) {
        lastFiredKey = destination?.let(::keyOf)
    }

    /** Returns the destination to launch this poll, or null if nothing should fire. */
    fun onPoll(destination: Destination?): Destination? {
        if (destination == null) {
            lastFiredKey = null
            return null
        }
        val key = keyOf(destination)
        if (key == lastFiredKey) return null
        lastFiredKey = key
        return destination
    }

    private fun keyOf(d: Destination): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val lat = (d.latitude * factor).roundToLong()
        val lon = (d.longitude * factor).roundToLong()
        return "$lat,$lon"
    }
}
