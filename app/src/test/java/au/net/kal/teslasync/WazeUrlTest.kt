package au.net.kal.teslasync

import au.net.kal.teslasync.service.WazeUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class WazeUrlTest {

    @Test
    fun builds_lat_lon_in_correct_order() {
        // Times Square. lat THEN lon — reversing routes to the wrong place.
        assertEquals(
            "https://waze.com/ul?ll=40.758895,-73.985131&navigate=yes",
            WazeUrl.build(40.758895, -73.985131),
        )
    }

    @Test
    fun handles_southern_hemisphere_coordinates() {
        // Brisbane — negative latitude, positive longitude.
        assertEquals(
            "https://waze.com/ul?ll=-27.4698,153.0251&navigate=yes",
            WazeUrl.build(-27.4698, 153.0251),
        )
    }
}
