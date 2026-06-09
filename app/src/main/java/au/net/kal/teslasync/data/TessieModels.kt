package au.net.kal.teslasync.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The one thing this whole app cares about: where the car is told to go. */
data class Destination(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** A car the Tessie account can see, for the VIN picker. */
data class VehicleSummary(
    val vin: String,
    val displayName: String,
)

// --- Tessie JSON DTOs --------------------------------------------------------
// Only the fields we need are declared; ignoreUnknownKeys handles the rest.
// VERIFY these against a real response (Step 0 curl) — Tessie's exact envelope
// can differ by endpoint/version. The /state CurrentState object carries
// drive_state at the top level.

@Serializable
data class TessieStateResponse(
    @SerialName("drive_state") val driveState: TessieDriveState? = null,
)

@Serializable
data class TessieDriveState(
    @SerialName("active_route_destination") val destination: String? = null,
    @SerialName("active_route_latitude") val latitude: Double? = null,
    @SerialName("active_route_longitude") val longitude: Double? = null,
    @SerialName("active_route_minutes_to_arrival") val minutesToArrival: Double? = null,
    @SerialName("shift_state") val shiftState: String? = null,
)

@Serializable
data class TessieVehiclesResponse(
    val results: List<TessieVehicle> = emptyList(),
)

@Serializable
data class TessieVehicle(
    val vin: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("last_state") val lastState: TessieLastState? = null,
)

@Serializable
data class TessieLastState(
    @SerialName("vin") val vin: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

/**
 * Parses Tessie response bodies. Pure (no Android deps) so it runs in JVM unit tests.
 */
object TessieParser {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** The active destination from a /state body, or null if no route is set. */
    fun parseDestination(body: String): Destination? {
        val ds = json.decodeFromString(TessieStateResponse.serializer(), body).driveState ?: return null
        val name = ds.destination?.takeIf { it.isNotBlank() } ?: return null
        val lat = ds.latitude ?: return null
        val lon = ds.longitude ?: return null
        return Destination(name = name, latitude = lat, longitude = lon)
    }

    /** The vehicle list from a /vehicles body, for the VIN picker. */
    fun parseVehicles(body: String): List<VehicleSummary> {
        val resp = json.decodeFromString(TessieVehiclesResponse.serializer(), body)
        return resp.results.mapNotNull { v ->
            val vin = v.vin ?: v.lastState?.vin ?: return@mapNotNull null
            val name = v.displayName ?: v.lastState?.displayName ?: vin
            VehicleSummary(vin = vin, displayName = name)
        }
    }
}
