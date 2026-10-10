package dev.cinemar2;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TimelineTest {
    @Test void pauseAndResumePreservePositionForLateJoiners() {
        var t = new Timeline(1000, 10_000, true);
        assertEquals(6000, t.at(15_000)); t.pause(15_000);
        assertEquals(6000, t.at(100_000)); t.play(100_000);
        assertEquals(8000, t.at(102_000));
    }
    @Test void seekKeepsPlaybackStateAndClampsInputs() {
        var t = new Timeline(0, 0, true); t.seek(90_000, 30_000);
        assertEquals(92_000, t.at(32_000)); assertTrue(t.playing());
        t.pause(32_000); t.seek(-1000, 40_000); assertEquals(0, t.at(50_000)); assertFalse(t.playing());
        t.seek(Long.MAX_VALUE, 40_000); assertEquals(86_400_000, t.at(50_000));
    }
    @Test void duplicatePlayDoesNotResetClockAndBackwardClockDoesNotRewind() {
        var t = new Timeline(0, 10000, true); t.play(11000);
        assertEquals(2000, t.at(12000)); assertEquals(0, t.at(9000));
    }
}
