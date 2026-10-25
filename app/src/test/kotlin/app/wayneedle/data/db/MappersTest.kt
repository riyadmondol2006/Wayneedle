package app.wayneedle.data.db

import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MappersTest {
    // region Trip

    @Test
    fun `trip round-trips through the entity unchanged`() {
        val trip = aTrip(id = "7d1c", distanceMeters = 12_345.678, maxSpeedMps = 33.25f)

        assertEquals(trip, trip.toEntity().toDomainOrNull())
    }

    @Test
    fun `entity stores base measurements and the average is derived not stored`() {
        val trip = aTrip(durationMillis = 100_000L, distanceMeters = 1_000.0)

        val entity = trip.toEntity()
        val back = checkNotNull(entity.toDomainOrNull())

        assertEquals(1_000.0, entity.distanceMeters)
        assertEquals(100_000L, entity.activeDurationMillis)
        assertEquals(10f, back.stats.averageSpeedMps, 1e-4f)
    }

    @Test
    fun `trip boundary values survive`() {
        val zero = Trip("z", 0L, 0L, TripStats(0.0, 0L, 0f))
        val huge = Trip(
            id = "h",
            startedAtEpochMillis = Long.MAX_VALUE - 1,
            endedAtEpochMillis = Long.MAX_VALUE,
            stats = TripStats(
                distanceMeters = 40_075_000.0,
                activeDurationMillis = Long.MAX_VALUE,
                maxSpeedMps = Float.MAX_VALUE,
            ),
        )

        assertEquals(zero, zero.toEntity().toDomainOrNull())
        assertEquals(huge, huge.toEntity().toDomainOrNull())
    }

    @Test
    fun `negative and non-finite measurements are cleaned to zero when saving`() {
        val dirty = aTrip().copy(
            stats = TripStats(distanceMeters = -5.0, activeDurationMillis = -1L, maxSpeedMps = -3f),
        )
        val entity = dirty.toEntity()

        assertEquals(0.0, entity.distanceMeters)
        assertEquals(0L, entity.activeDurationMillis)
        assertEquals(0f, entity.maxSpeedMps)

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertEquals(0.0, aTrip().copy(stats = TripStats(distanceMeters = bad)).toEntity().distanceMeters)
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            assertEquals(0f, aTrip().copy(stats = TripStats(maxSpeedMps = bad)).toEntity().maxSpeedMps)
        }
    }

    @Test
    fun `a corrupt stored trip is repaired on read instead of crashing`() {
        val corrupt = aTripEntity(distanceMeters = Double.NaN, activeDurationMillis = -9L, maxSpeedMps = Float.NaN)

        val trip = checkNotNull(corrupt.toDomainOrNull())

        assertEquals(TripStats(0.0, 0L, 0f), trip.stats)
        assertEquals(0f, trip.stats.averageSpeedMps)
    }

    @Test
    fun `end time before start time is raised to the start time`() {
        val entity = aTripEntity(startedAtEpochMillis = 5_000L, endedAtEpochMillis = 1_000L)

        assertEquals(5_000L, entity.toDomainOrNull()?.endedAtEpochMillis)
        assertEquals(
            5_000L,
            aTrip().copy(startedAtEpochMillis = 5_000L, endedAtEpochMillis = 1_000L).toEntity().endedAtEpochMillis,
        )
    }

    @Test
    fun `negative timestamps are clamped to zero`() {
        val trip = checkNotNull(aTripEntity(startedAtEpochMillis = -10L, endedAtEpochMillis = -5L).toDomainOrNull())

        assertEquals(0L, trip.startedAtEpochMillis)
        assertEquals(0L, trip.endedAtEpochMillis)
    }

    @Test
    fun `saving a trip with a blank id is rejected and a stored blank id is skipped`() {
        assertFailsWith<IllegalArgumentException> { aTrip(id = "").toEntity() }
        assertFailsWith<IllegalArgumentException> { aTrip(id = "  ").toEntity() }
        assertNull(aTripEntity(id = "").toDomainOrNull())
        assertNull(aTripEntity(id = " ").toDomainOrNull())
    }

    // endregion

    // region Checkpoint

    @Test
    fun `checkpoint round-trips for running and paused`() {
        listOf(TripPhase.RUNNING, TripPhase.PAUSED).forEach { phase ->
            val checkpoint = aCheckpoint(phase = phase)

            assertEquals(checkpoint, checkpoint.toEntity().toDomainOrNull())
        }
    }

    @Test
    fun `checkpoint entity always targets the single slot`() {
        assertEquals(TripCheckpointEntity.SLOT, aCheckpoint().toEntity().slot)
        assertEquals(TripCheckpointEntity.SLOT, aCheckpointEntity().slot)
    }

    @Test
    fun `checkpoint boundary values survive`() {
        val zero = aCheckpoint(
            startedAtEpochMillis = 0L,
            savedAtEpochMillis = 0L,
            stats = TripStats(0.0, 0L, 0f),
        )

        assertEquals(zero, zero.toEntity().toDomainOrNull())
    }

    @Test
    fun `only running and paused checkpoints can be written`() {
        listOf(TripPhase.IDLE, TripPhase.FINISHED).forEach { phase ->
            assertFailsWith<IllegalArgumentException> { aCheckpoint(phase = phase).toEntity() }
        }
        assertFailsWith<IllegalArgumentException> { aCheckpoint(tripId = "").toEntity() }
    }

    @Test
    fun `odd live values are cleaned when writing a checkpoint`() {
        val entity = aCheckpoint(
            stats = TripStats(
                distanceMeters = Double.NaN,
                activeDurationMillis = -1L,
                maxSpeedMps = Float.POSITIVE_INFINITY,
            ),
            startedAtEpochMillis = -1L,
            savedAtEpochMillis = -1L,
        ).toEntity()

        assertEquals(0.0, entity.distanceMeters)
        assertEquals(0L, entity.activeDurationMillis)
        assertEquals(0f, entity.maxSpeedMps)
        assertEquals(0L, entity.startedAtEpochMillis)
        assertEquals(0L, entity.savedAtEpochMillis)
        assertEquals(entity.toDomainOrNull()?.stats, TripStats.Empty)
    }

    @Test
    fun `corrupt stored checkpoints are rejected`() {
        val corrupt = listOf(
            "blank id" to aCheckpointEntity(tripId = ""),
            "unknown phase" to aCheckpointEntity(phase = "SPRINTING"),
            "lowercase phase" to aCheckpointEntity(phase = "running"),
            "empty phase" to aCheckpointEntity(phase = ""),
            "idle phase" to aCheckpointEntity(phase = "IDLE"),
            "finished phase" to aCheckpointEntity(phase = "FINISHED"),
            "NaN distance" to aCheckpointEntity(distanceMeters = Double.NaN),
            "infinite distance" to aCheckpointEntity(distanceMeters = Double.POSITIVE_INFINITY),
            "negative distance" to aCheckpointEntity(distanceMeters = -1.0),
            "negative duration" to aCheckpointEntity(activeDurationMillis = -1L),
            "NaN max speed" to aCheckpointEntity(maxSpeedMps = Float.NaN),
            "negative max speed" to aCheckpointEntity(maxSpeedMps = -0.5f),
            "negative start" to aCheckpointEntity(startedAtEpochMillis = -1L),
            "negative saved at" to aCheckpointEntity(savedAtEpochMillis = -1L),
        )

        corrupt.forEach { (name, entity) -> assertNull(entity.toDomainOrNull(), name) }
    }

    @Test
    fun `a valid stored checkpoint decodes`() {
        val checkpoint = checkNotNull(aCheckpointEntity(phase = "PAUSED").toDomainOrNull())

        assertEquals(TripPhase.PAUSED, checkpoint.phase)
        assertEquals(1_234.5, checkpoint.stats.distanceMeters)
    }

    // endregion
}
