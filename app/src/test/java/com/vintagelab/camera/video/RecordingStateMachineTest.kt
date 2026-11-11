package com.vintagelab.camera.video

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStateMachineTest {
    @Test fun idleToRecordingToStoppingToSaved() {
        var s = RecordingStateMachine.reduce(RecordingState(), RecordingEvent.Start)
        assertEquals(RecordingPhase.Starting, s.phase)
        s = RecordingStateMachine.reduce(s, RecordingEvent.Started)
        assertEquals(RecordingPhase.Recording, s.phase)
        s = RecordingStateMachine.reduce(s, RecordingEvent.Tick(5_100))
        assertEquals(5_100, s.elapsedMs)
        s = RecordingStateMachine.reduce(s, RecordingEvent.Stop(StopReason.User))
        assertEquals(RecordingPhase.Stopping, s.phase)
        s = RecordingStateMachine.reduce(s, RecordingEvent.Saved)
        assertEquals(RecordingPhase.Saved, s.phase)
    }

    @Test fun capAndBackgroundStopAreDeterministic() {
        val recording = RecordingState(RecordingPhase.Recording, 179_900)
        val capped = RecordingStateMachine.reduce(recording, RecordingEvent.Tick(VideoLimits.MAX_DURATION_MS + 1))
        assertEquals(RecordingPhase.Stopping, capped.phase)
        assertEquals(VideoLimits.MAX_DURATION_MS, capped.elapsedMs)
        assertEquals(StopReason.Cap, capped.stopReason)

        val background = RecordingStateMachine.reduce(recording, RecordingEvent.Stop(StopReason.Background))
        assertEquals(RecordingPhase.Stopping, background.phase)
        assertEquals(StopReason.Background, background.stopReason)
        assertEquals(RecordingPhase.Error, RecordingStateMachine.reduce(background, RecordingEvent.Failed).phase)
    }
}
