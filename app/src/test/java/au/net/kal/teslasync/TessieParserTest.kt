package au.net.kal.teslasync

import au.net.kal.teslasync.data.TessieParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TessieParserTest {

    @Test
    fun parses_active_route_destination() {
        val body = """
            {"drive_state":{
              "active_route_destination":"Home",
              "active_route_latitude":-27.46,
              "active_route_longitude":153.02,
              "active_route_minutes_to_arrival":12.5,
              "shift_state":"D"
            }}
        """.trimIndent()
        val d = TessieParser.parseDestination(body)
        assertNotNull(d)
        assertEquals("Home", d!!.name)
        assertEquals(-27.46, d.latitude, 1e-9)
        assertEquals(153.02, d.longitude, 1e-9)
    }

    @Test
    fun returns_null_when_no_route_set() {
        assertNull(TessieParser.parseDestination("""{"drive_state":{"shift_state":"P"}}"""))
    }

    @Test
    fun returns_null_when_destination_blank() {
        val body = """{"drive_state":{"active_route_destination":"","active_route_latitude":1.0,"active_route_longitude":2.0}}"""
        assertNull(TessieParser.parseDestination(body))
    }

    @Test
    fun returns_null_when_drive_state_missing() {
        assertNull(TessieParser.parseDestination("""{"charge_state":{"battery_level":80}}"""))
    }

    @Test
    fun ignores_unknown_keys() {
        val body = """
            {"unexpected":123,"drive_state":{
              "active_route_destination":"Work",
              "active_route_latitude":1.5,
              "active_route_longitude":2.5,
              "some_future_field":"ignored"
            }}
        """.trimIndent()
        assertEquals("Work", TessieParser.parseDestination(body)?.name)
    }

    @Test
    fun parses_vehicle_list() {
        val body = """{"results":[{"vin":"5YJ3E1EA1KF000000","last_state":{"display_name":"My Tesla"}}]}"""
        val vehicles = TessieParser.parseVehicles(body)
        assertEquals(1, vehicles.size)
        assertEquals("5YJ3E1EA1KF000000", vehicles[0].vin)
        assertEquals("My Tesla", vehicles[0].displayName)
    }
}
