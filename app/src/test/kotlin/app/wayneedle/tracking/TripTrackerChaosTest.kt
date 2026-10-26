@file:OptIn(ExperimentalCoroutinesApi::class)

package app.wayneedle.tracking

import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripState
import app.wayneedle.testing.aSample
import app.wayneedle.testing.latitudeAfterNorth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test

/**
 * A simulated careless (and unlucky) user driving around with the app for a long time.
 *
 * Every seed plays a few hundred random steps: tapping commands in any order and several times in a row,
 * double-tapping Finish while the save is slow, hiding and showing the app, switching Location off and on,
 * revoking permission (which kills the process on Android), the process dying at any moment, storage
 * failing, and the GPS delivering glitches (speed spikes, stale, duplicated, future or malformed fixes,
 * position jumps) between honest fixes from a vehicle that accelerates, brakes and parks.
 *
 * After every step the tracker must still satisfy its invariants: no NaN or negative statistics; a trip's
 * statistics never go backwards and only change while RUNNING; never more distance, active time or top
 * speed than the simulated vehicle really produced while recording; rejected commands change nothing;
 * a finished trip is stored exactly once and its checkpoint never comes back; the foreground service runs
 * exactly while a trip needs it; at most one GPS listener exists.
 *
 * A failure prints the seed and the recent steps, so it can be replayed deterministically.
 */
class TripTrackerChaosTest {
    @Test
    fun `random real-world abuse never breaks the trip invariants`() {
        val coverage = Coverage()
        for (seed in 1..SEEDS) ChaosRun(seed.toLong(), coverage).play(STEPS)
        // Guard against a vacuous pass: the interesting situations must actually have happened.
        check(coverage.tripsSavedWithDistance >= 50) { "too few trips with distance were saved: $coverage" }
        check(coverage.failedSaves >= 10) { "storage failures were barely exercised: $coverage" }
        check(coverage.tripsRecovered >= 30) { "process death mid-trip was barely exercised: $coverage" }
        check(coverage.savesInterrupted >= 10) { "slow saves were barely exercised: $coverage" }
        check(coverage.glitchesWhileRecording >= 200) { "GPS glitches during trips were barely exercised: $coverage" }
    }

    private data class Coverage(
        var tripsSavedWithDistance: Int = 0,
        var failedSaves: Int = 0,
        var tripsRecovered: Int = 0,
        var savesInterrupted: Int = 0,
        var glitchesWhileRecording: Int = 0,
    )

    private class ChaosRun(private val seed: Long, private val coverage: Coverage) {
        private val random = Random(seed)
        private val log = ArrayDeque<String>()

        fun play(steps: Int) = runTest {
            World(this, random, coverage, ::note).run {
                bootProcess()
                repeat(steps) { step ->
                    try {
                        randomStep()
                        checkInvariants()
                    } catch (e: Throwable) {
                        throw AssertionError(
                            "Chaos seed $seed failed at step $step: ${e.message}\nRecent steps:\n" +
                                log.joinToString("\n"),
                            e,
                        )
                    }
                }
                killProcess()
            }
        }

        private fun note(step: String) {
            log.addLast(step)
            if (log.size > LOG_SIZE) log.removeFirst()
        }
    }

    /** The phone, the vehicle and the storage, which outlive any single app process. */
    private class World(
        private val test: TestScope,
        private val random: Random,
        private val coverage: Coverage,
        private val note: (String) -> Unit,
    ) {
        private val config = TrackerConfig()
        private val source = FakeLocationSource()
        private val permissions = FakePermissionChecker()
        private val repository = InMemoryTripRepository()
        private val checkpoints = InMemoryCheckpointStore()
        private val time = SchedulerTimeSource(test.testScheduler)

        private lateinit var process: AppProcess
        private val tracker get() = process.tracker

        // The simulated vehicle (always driving north) and what it really did while a trip was recording.
        private var latitude = 52.0
        private var speedMps = 0f
        private var trueRecordedMeters = 0.0
        private var trueRecordedMillis = 0L
        private var trueTopSpeedMps = 0f
        private var lastFixMillis = Long.MIN_VALUE

        // Per trip, what the tracker had shown before the current step.
        private var previous: TripState = TripState()
        private var stepStartMillis = 0L
        private val savedIds = mutableSetOf<String>()

        private inner class AppProcess {
            val job = Job(test.backgroundScope.coroutineContext[Job])
            val foreground = RecordingForegroundLauncher()
            val tracker = TripTracker(
                locationSource = source,
                permissionChecker = permissions,
                tripRepository = repository,
                checkpointStore = checkpoints,
                foreground = foreground,
                timeSource = time,
                scope = CoroutineScope(test.backgroundScope.coroutineContext + job),
                config = config,
            )
        }

        fun bootProcess() {
            process = AppProcess()
            test.runCurrent()
        }

        fun killProcess() {
            process.job.cancel()
            test.runCurrent()
        }

        suspend fun randomStep() {
            previous = tracker.state.value.trip
            stepStartMillis = time.nowMillis
            when (random.nextInt(100)) {
                in 0..6 -> command("start") { tracker.startTrip() }

                in 7..11 -> command("pause") { tracker.pauseTrip() }

                in 12..16 -> command("resume") { tracker.resumeTrip() }

                in 17..19 -> command("discard") { tracker.discardTrip() }

                in 20..22 -> finish()

                23 -> finishTwiceWhileSaving()

                24 -> mashCommands()

                25 -> command("dismissFinished") { tracker.dismissFinished() }

                in 26..29 -> setUiVisible(random.nextBoolean())

                30 -> {
                    note("refreshEnvironment")
                    tracker.refreshEnvironment()
                }

                31 -> setProvider(enabled = !source.providerEnabled)

                32 -> changePermission()

                33 -> if (random.nextBoolean()) processDies() else processDiesAndUserTapsStartAtOnce()

                34 -> {
                    repository.saveFailure = if (repository.saveFailure == null) IOException("disk full") else null
                    note("storage failing = ${repository.saveFailure != null}")
                }

                35 -> {
                    checkpoints.failSaves = !checkpoints.failSaves
                    note("checkpoint storage failing = ${checkpoints.failSaves}")
                }

                in 36..39 -> elapse(random.nextLong(5_000L, 180_000L), sendFixes = false)

                in 40..46 -> glitch()

                in 47..49 -> changeSpeed(if (random.nextInt(3) == 0) 0f else random.nextDouble(0.0, 45.0).toFloat())

                else -> drive(random.nextInt(1, 8))
            }
            test.runCurrent()
        }

        // ---- user actions ------------------------------------------------------------------------------------

        private fun command(name: String, action: () -> Any) {
            val before = tracker.state.value.trip
            val result = action()
            note("$name -> $result")
            if (result is CommandResult.InvalidTransition ||
                result == CommandResult.PermissionRequired ||
                result == CommandResult.LocationDisabled
            ) {
                check(tracker.state.value.trip == before) { "rejected $name changed the trip" }
            }
            if (result == CommandResult.Ok && name == "start") {
                trueRecordedMeters = 0.0
                trueRecordedMillis = 0L
                trueTopSpeedMps = 0f
            }
        }

        private suspend fun finish() {
            val before = tracker.state.value.trip
            val result = tracker.finishTrip()
            note("finish -> ${result::class.simpleName}")
            verifyFinish(before, result)
        }

        /** The user taps Finish, the save is slow, and they keep tapping everything. */
        private suspend fun finishTwiceWhileSaving() {
            val before = tracker.state.value.trip
            if (before.phase != TripPhase.RUNNING && before.phase != TripPhase.PAUSED) return finish()
            val gate = CompletableDeferred<Unit>()
            repository.saveGate = gate
            val first = test.async { tracker.finishTrip() }
            test.runCurrent()
            val frozen = tracker.state.value.trip
            val second = tracker.finishTrip()
            val others = listOf(tracker.startTrip(), tracker.pauseTrip(), tracker.resumeTrip(), tracker.discardTrip())
            check(tracker.state.value.trip == frozen) { "commands during a save changed the trip" }
            coverage.savesInterrupted++
            gate.complete(Unit)
            repository.saveGate = null
            test.runCurrent()
            val result = first.await()
            note("finish x2 while saving -> ${result::class.simpleName}, second=$second, others=$others")
            check(second is FinishResult.InvalidTransition) { "second Finish during a save was $second" }
            check(others.all { it is CommandResult.InvalidTransition }) { "commands during a save: $others" }
            verifyFinish(before, result)
        }

        /** Five quick taps on whatever is on screen. */
        private fun mashCommands() {
            val action = random.nextInt(3)
            val starts = process.foreground.calls.count { it == "start" }
            val results = List(5) {
                when (action) {
                    0 -> tracker.startTrip()
                    1 -> tracker.pauseTrip()
                    else -> tracker.resumeTrip()
                }
            }
            note("mash ${listOf("start", "pause", "resume")[action]} x5 -> $results")
            check(results.count { it == CommandResult.Ok } <= 1) { "a mashed command succeeded twice: $results" }
            val newStarts = process.foreground.calls.count { it == "start" } - starts
            check(newStarts <= 1) { "mashing started the service $newStarts times" }
            if (action == 0 && results.first() == CommandResult.Ok) {
                trueRecordedMeters = 0.0
                trueRecordedMillis = 0L
                trueTopSpeedMps = 0f
            }
        }

        private fun setUiVisible(visible: Boolean) {
            note("ui visible = $visible")
            tracker.setUiVisible(visible)
        }

        private fun setProvider(enabled: Boolean) {
            note("location switched ${if (enabled) "on" else "off"}")
            source.providerEnabled = enabled
            test.runCurrent()
            if (source.hasSubscriber) source.send(LocationSourceEvent.ProviderEnabledChanged(enabled))
        }

        /**
         * Revoking or downgrading a runtime permission kills the app process; granting it is noticed on the
         * next resume.
         */
        private suspend fun changePermission() {
            val next = LocationPermissionState.entries.random(random)
            val revoked = permissions.state == LocationPermissionState.GRANTED_PRECISE &&
                next != LocationPermissionState.GRANTED_PRECISE
            note("permission ${permissions.state} -> $next")
            permissions.state = next
            if (revoked) {
                processDies()
            } else {
                tracker.refreshEnvironment()
            }
        }

        /** Swiped away under memory pressure, battery died, app updated, permission revoked... */
        private suspend fun processDies() {
            val beforeDeath = tracker.state.value.trip
            killProcess()
            bootProcess()
            val recovered = tracker.recoverInterruptedTrip()
            test.runCurrent()
            val after = tracker.state.value.trip
            note("process died in ${beforeDeath.phase}; recovered=$recovered -> ${after.phase}")
            if (recovered) {
                coverage.tripsRecovered++
                check(after.phase == TripPhase.PAUSED && after.recovered) { "recovered trip is $after" }
                check(after.tripId !in savedIds) { "a trip that was already saved came back: ${after.tripId}" }
                check(beforeDeath.tripId == null || after.tripId == beforeDeath.tripId) {
                    "recovered a different trip (${after.tripId}) than the one in progress (${beforeDeath.tripId})"
                }
                if (beforeDeath.tripId == after.tripId) {
                    check(after.stats.distanceMeters <= beforeDeath.stats.distanceMeters + EPSILON_M) {
                        "recovery invented distance"
                    }
                    check(after.stats.activeDurationMillis <= beforeDeath.stats.activeDurationMillis) {
                        "recovery invented active time"
                    }
                }
            } else {
                check(after.phase == TripPhase.IDLE) { "no recovery, but the trip is $after" }
                if (beforeDeath.phase == TripPhase.RUNNING || beforeDeath.phase == TripPhase.PAUSED) {
                    // Losing an in-progress trip is acceptable only if none of its checkpoints reached storage.
                    check(checkpoints.writes.none { it.tripId == beforeDeath.tripId }) {
                        "an in-progress trip was lost although its checkpoint had been written"
                    }
                }
            }
            // A recovered trip starts a new truth baseline: what it already holds is accounted for.
            previous = after
        }

        /** The app is reopened after dying mid-trip and the user taps Start before recovery has run. */
        private suspend fun processDiesAndUserTapsStartAtOnce() {
            val beforeDeath = tracker.state.value.trip
            val interrupted = (beforeDeath.phase == TripPhase.RUNNING || beforeDeath.phase == TripPhase.PAUSED) &&
                checkpoints.checkpoint?.tripId == beforeDeath.tripId
            killProcess()
            bootProcess()
            tracker.setUiVisible(true)
            val started = tracker.startTrip()
            test.runCurrent()
            val recovered = tracker.recoverInterruptedTrip()
            test.runCurrent()
            note("process died in ${beforeDeath.phase}; Start at once -> $started; recovered=$recovered")
            if (started == CommandResult.Ok) {
                check(!recovered) { "recovered a trip on top of a new one" }
                trueRecordedMeters = 0.0
                trueRecordedMillis = 0L
                trueTopSpeedMps = 0f
                if (interrupted && repository.saveFailure == null) {
                    val kept = repository.stored[beforeDeath.tripId]
                    check(kept != null) { "the interrupted trip ${beforeDeath.tripId} was lost" }
                    check(kept.stats.distanceMeters <= beforeDeath.stats.distanceMeters + EPSILON_M) {
                        "the kept trip has more distance than was recorded"
                    }
                }
            }
            previous = tracker.state.value.trip
        }

        // ---- the vehicle and the GPS -----------------------------------------------------------------------

        private fun changeSpeed(target: Float) {
            note("vehicle speed ${"%.1f".format(speedMps)} -> ${"%.1f".format(target)} m/s")
            // Realistic acceleration: about 3 m/s per second.
            while (speedMps != target) {
                val delta = (target - speedMps).coerceIn(-3f, 3f)
                speedMps += delta
                elapse(1_000L, sendFixes = true)
            }
        }

        private fun drive(seconds: Int) {
            note("drive $seconds s at ${"%.1f".format(speedMps)} m/s")
            elapse(seconds * 1_000L, sendFixes = true)
        }

        /** Moves the world forward one second at a time; the GPS reports each second when asked to. */
        private fun elapse(millis: Long, sendFixes: Boolean) {
            if (!sendFixes) note("no GPS for ${millis / 1_000} s (tunnel, pocket, garage)")
            var left = millis
            while (left > 0) {
                val step = minOf(1_000L, left)
                val recording = tracker.state.value.trip.phase == TripPhase.RUNNING
                test.advanceTimeBy(step)
                test.runCurrent()
                latitude = latitudeAfterNorth(latitude, speedMps * step / 1_000.0)
                if (recording && tracker.state.value.trip.phase == TripPhase.RUNNING) {
                    trueRecordedMeters += speedMps * step / 1_000.0
                    trueRecordedMillis += step
                }
                left -= step
                if (sendFixes) honestFix()
            }
        }

        private fun honestFix() {
            val reported = (speedMps + random.nextDouble(-0.3, 0.3).toFloat()).coerceAtLeast(0f)
            if (tracker.state.value.trip.phase == TripPhase.RUNNING) {
                trueTopSpeedMps = maxOf(trueTopSpeedMps, reported)
            }
            sendFix(
                aSample(
                    latitude = latitudeAfterNorth(latitude, random.nextDouble(-1.5, 1.5)),
                    longitude = 4.0,
                    horizontalAccuracyM = random.nextDouble(3.0, 8.0).toFloat(),
                    speedMps = reported,
                    speedAccuracyMps = 0.5f,
                    elapsedRealtimeMillis = time.nowMillis,
                ),
            )
        }

        /** One bad reading, then the receiver recovers (glitches never come in pairs here). */
        private fun glitch() {
            val now = time.nowMillis
            val kind = random.nextInt(9)
            val sample = when (kind) {
                0 -> aSample(
                    latitude = latitude,
                    speedMps = speedMps + random.nextInt(30, 90),
                    elapsedRealtimeMillis = now,
                )

                1 -> aSample(
                    latitude = latitude,
                    speedMps = speedMps + random.nextInt(5, 12),
                    elapsedRealtimeMillis = now,
                )

                2 -> aSample(latitude = latitude, speedMps = speedMps, elapsedRealtimeMillis = now - 12_000L)

                3 -> aSample(latitude = latitude, speedMps = speedMps, elapsedRealtimeMillis = now + 6_000L)

                4 -> aSample(latitude = latitude, speedMps = Float.NaN, elapsedRealtimeMillis = now)

                5 -> aSample(latitude = latitude, speedMps = null, elapsedRealtimeMillis = now)

                6 -> aSample(
                    latitude = latitudeAfterNorth(latitude, 2_000.0),
                    speedMps = speedMps,
                    elapsedRealtimeMillis = now,
                )

                7 -> aSample(latitude = Double.NaN, speedMps = speedMps, elapsedRealtimeMillis = now)

                else -> aSample(
                    latitude = latitude,
                    horizontalAccuracyM = 80f,
                    speedMps = 60f,
                    elapsedRealtimeMillis = now,
                )
            }
            note("GPS glitch #$kind")
            if (tracker.state.value.trip.phase == TripPhase.RUNNING && source.hasSubscriber) {
                coverage.glitchesWhileRecording++
            }
            sendFix(sample)
            if (kind == 2 || kind == 3) return
            // The receiver recovers: honest fixes follow at the usual rate.
            elapse(1_000L, sendFixes = true)
        }

        private fun sendFix(sample: app.wayneedle.domain.model.LocationSample) {
            if (!source.providerEnabled) return
            test.runCurrent()
            if (!source.hasSubscriber) return
            lastFixMillis = time.nowMillis
            source.send(LocationSourceEvent.Fix(sample))
            test.runCurrent()
        }

        // ---- invariants --------------------------------------------------------------------------------------

        private fun verifyFinish(before: TripState, result: FinishResult) {
            val after = tracker.state.value.trip
            when (result) {
                is FinishResult.Saved -> {
                    check(before.phase == TripPhase.RUNNING || before.phase == TripPhase.PAUSED) {
                        "saved a trip from ${before.phase}"
                    }
                    check(result.trip.id !in savedIds) { "trip ${result.trip.id} saved twice" }
                    savedIds += result.trip.id
                    if (result.trip.stats.distanceMeters > 0.0) coverage.tripsSavedWithDistance++
                    check(repository.stored[result.trip.id] == result.trip) { "stored trip differs from result" }
                    check(after.phase == TripPhase.FINISHED && after.stats == result.trip.stats) {
                        "summary differs from the saved trip"
                    }
                    check(result.trip.endedAtEpochMillis >= result.trip.startedAtEpochMillis) { "ended before start" }
                    test.runCurrent()
                    check(checkpoints.checkpoint?.tripId != result.trip.id) { "checkpoint of a saved trip remains" }
                }

                is FinishResult.SaveFailed -> {
                    coverage.failedSaves++
                    check(after.phase == TripPhase.PAUSED && after.tripId == before.tripId) {
                        "failed save left the trip in $after"
                    }
                }

                is FinishResult.InvalidTransition -> check(after == before) { "rejected finish changed the trip" }
            }
        }

        fun checkInvariants() {
            val state = tracker.state.value
            val trip = state.trip
            val stats = trip.stats
            val elapsed = time.nowMillis - stepStartMillis

            check(stats.distanceMeters.isFinite() && stats.distanceMeters >= 0.0) { "bad distance $stats" }
            check(stats.activeDurationMillis >= 0L) { "negative active time $stats" }
            check(stats.maxSpeedMps.isFinite() && stats.maxSpeedMps in 0f..config.fixFilter.maxSpeedMps) {
                "bad top speed $stats"
            }
            check(stats.averageSpeedMps.isFinite() && stats.averageSpeedMps >= 0f) { "bad average $stats" }
            check(!trip.awaitingFix || trip.phase == TripPhase.RUNNING) { "awaitingFix while ${trip.phase}" }
            check(trip.phase == TripPhase.IDLE || trip.tripId != null) { "trip without id in ${trip.phase}" }
            (state.liveSpeed as? LiveSpeed.Available)?.let {
                check(it.smoothedMps.isFinite() && it.smoothedMps in 0f..config.fixFilter.maxSpeedMps) { "live $it" }
            }

            if (trip.tripId != null && trip.tripId == previous.tripId) {
                val before = previous.stats
                check(stats.distanceMeters >= before.distanceMeters - EPSILON_M) { "distance went backwards" }
                check(stats.activeDurationMillis >= before.activeDurationMillis) { "active time went backwards" }
                check(stats.maxSpeedMps >= before.maxSpeedMps) { "top speed went backwards" }
                // The published state can lag the clock by up to one tick.
                check(stats.activeDurationMillis - before.activeDurationMillis <= elapsed + config.tickIntervalMillis) {
                    "counted ${stats.activeDurationMillis - before.activeDurationMillis} ms of active time in $elapsed ms"
                }
                val wasRecording = previous.phase == TripPhase.RUNNING || trip.phase == TripPhase.RUNNING
                if (!wasRecording) {
                    check(stats == before) { "statistics changed while not recording: $before -> $stats" }
                }
            }

            if (trip.phase == TripPhase.RUNNING || trip.phase == TripPhase.PAUSED) {
                if (!trip.recovered) {
                    check(stats.distanceMeters <= trueRecordedMeters * 1.05 + 30.0) {
                        "recorded ${stats.distanceMeters} m but the vehicle only covered $trueRecordedMeters m"
                    }
                    check(stats.activeDurationMillis <= trueRecordedMillis + 1_000L) {
                        "active ${stats.activeDurationMillis} ms vs $trueRecordedMillis ms really recorded"
                    }
                    check(stats.maxSpeedMps <= trueTopSpeedMps + 0.5f) {
                        "top speed ${stats.maxSpeedMps} m/s but the vehicle never exceeded $trueTopSpeedMps m/s"
                    }
                }
            }

            // The foreground service runs exactly while an active trip needs it.
            val lastCall = process.foreground.calls.lastOrNull()
            val needsService = trip.phase == TripPhase.RUNNING || (trip.phase == TripPhase.PAUSED && !trip.recovered)
            if (needsService) {
                check(lastCall == "start") { "trip ${trip.phase} but the service was last told $lastCall" }
            } else {
                check(lastCall == null || lastCall == "stop") { "no active trip but the service was last told start" }
            }

            check(source.maxConcurrentCollectors <= 1) { "two GPS listeners at once" }
            val wanted = (trip.phase == TripPhase.RUNNING) && state.permission.canMeasureSpeed
            if (wanted) check(source.activeCollectors == 1) { "recording without a GPS listener" }

            if (trip.phase == TripPhase.IDLE || trip.phase == TripPhase.FINISHED) {
                check(checkpoints.checkpoint == null || checkpoints.checkpoint?.tripId !in savedIds) {
                    "a finished trip's checkpoint was resurrected"
                }
            }
        }
    }

    private companion object {
        const val SEEDS = 150
        const val STEPS = 250
        const val LOG_SIZE = 40
        const val EPSILON_M = 1e-6
    }
}
