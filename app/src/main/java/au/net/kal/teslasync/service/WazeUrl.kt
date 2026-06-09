package au.net.kal.teslasync.service

/**
 * Builds the Waze universal deep link. Kept pure (returns a String) so it's unit-testable
 * without an Android device.
 */
object WazeUrl {
    /**
     * `ll` is LATITUDE,LONGITUDE — reversing the order silently routes to the wrong place
     * (often the ocean) with no error. `navigate=yes` asks Waze to jump to the route
     * preview; note that since Waze Android 5.3.0.2 (Jan 2025) it no longer auto-starts, so
     * the user still taps GO.
     *
     * String templating uses Double.toString(), which is locale-independent (always '.'),
     * so this is safe in comma-decimal locales — do NOT switch to String.format without a
     * fixed Locale.
     */
    fun build(latitude: Double, longitude: Double): String =
        "https://waze.com/ul?ll=$latitude,$longitude&navigate=yes"
}
