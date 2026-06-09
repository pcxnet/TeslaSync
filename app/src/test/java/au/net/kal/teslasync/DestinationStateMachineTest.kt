package au.net.kal.teslasync

import au.net.kal.teslasync.data.Destination
import au.net.kal.teslasync.service.DestinationStateMachine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationStateMachineTest {

    private fun dest(lat: Double, lon: Double, name: String = "X") = Destination(name, lat, lon)

    @Test
    fun fires_on_new_destination() {
        val sm = DestinationStateMachine()
        assertNotNull(sm.onPoll(dest(1.0, 2.0)))
    }

    @Test
    fun does_not_refire_same_destination() {
        val sm = DestinationStateMachine()
        sm.onPoll(dest(1.0, 2.0))
        assertNull(sm.onPoll(dest(1.0, 2.0)))
    }

    @Test
    fun fires_again_on_changed_destination() {
        val sm = DestinationStateMachine()
        sm.onPoll(dest(1.0, 2.0))
        assertNotNull(sm.onPoll(dest(3.0, 4.0)))
    }

    @Test
    fun cleared_destination_resets_so_same_place_fires_again() {
        val sm = DestinationStateMachine()
        sm.onPoll(dest(1.0, 2.0))
        assertNull(sm.onPoll(null))             // route cleared
        assertNotNull(sm.onPoll(dest(1.0, 2.0))) // re-selected -> fires
    }

    @Test
    fun prime_suppresses_the_first_fire() {
        val sm = DestinationStateMachine()
        sm.prime(dest(1.0, 2.0))
        assertNull(sm.onPoll(dest(1.0, 2.0)))    // already-set destination not fired
        assertNotNull(sm.onPoll(dest(3.0, 4.0))) // a change still fires
    }

    @Test
    fun ignores_sub_precision_jitter() {
        val sm = DestinationStateMachine(precision = 5)
        sm.onPoll(dest(1.000000, 2.000000))
        assertNull(sm.onPoll(dest(1.0000001, 2.0000001))) // below 5-dp precision
    }
}
