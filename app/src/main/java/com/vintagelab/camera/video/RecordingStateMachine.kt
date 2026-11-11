package com.vintagelab.camera.video

enum class RecordingPhase { Idle, Starting, Recording, Stopping, Saved, Error }

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.Idle,
    val elapsedMs: Long = 0L,
    val stopReason: StopReason? = null,
)

enum class StopReason { User, Cap, Background, CameraError }

sealed interface RecordingEvent {
    data object Start : RecordingEvent
    data object Started : RecordingEvent
    data class Tick(val elapsedMs: Long) : RecordingEvent
    data class Stop(val reason: StopReason) : RecordingEvent
    data object Saved : RecordingEvent
    data object Failed : RecordingEvent
    data object Reset : RecordingEvent
}

/** Pure transition reducer used by the ViewModel and JVM tests. */
object RecordingStateMachine {
    fun reduce(state: RecordingState, event: RecordingEvent): RecordingState = when (event) {
        RecordingEvent.Start -> if (state.phase == RecordingPhase.Idle || state.phase == RecordingPhase.Saved || state.phase == RecordingPhase.Error) {
            RecordingState(RecordingPhase.Starting)
        } else state
        RecordingEvent.Started -> if (state.phase == RecordingPhase.Starting) state.copy(phase = RecordingPhase.Recording) else state
        is RecordingEvent.Tick -> if (state.phase == RecordingPhase.Recording) {
            val elapsed = event.elapsedMs.coerceAtLeast(state.elapsedMs)
            if (elapsed >= VideoLimits.MAX_DURATION_MS) state.copy(
                phase = RecordingPhase.Stopping,
                elapsedMs = VideoLimits.MAX_DURATION_MS,
                stopReason = StopReason.Cap,
            ) else state.copy(elapsedMs = elapsed)
        } else state
        is RecordingEvent.Stop -> if (state.phase == RecordingPhase.Starting || state.phase == RecordingPhase.Recording) {
            state.copy(phase = RecordingPhase.Stopping, stopReason = event.reason)
        } else state
        RecordingEvent.Saved -> if (state.phase == RecordingPhase.Stopping) state.copy(phase = RecordingPhase.Saved) else state
        RecordingEvent.Failed -> if (state.phase != RecordingPhase.Idle) state.copy(phase = RecordingPhase.Error) else state
        RecordingEvent.Reset -> RecordingState()
    }
}
