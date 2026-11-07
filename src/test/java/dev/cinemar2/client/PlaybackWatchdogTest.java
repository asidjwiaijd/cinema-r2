package dev.cinemar2.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackWatchdogTest {
    @Test void ordinaryBufferingKeepsItsCacheButAnIndefiniteStallIsDetected(){
        var watchdog=new PlaybackWatchdog();
        assertFalse(watchdog.stalled(1000,true,583958,7584));
        assertFalse(watchdog.stalled(180999,true,583958,7584));
        assertTrue(watchdog.stalled(181000,true,583958,7584));
    }
    @Test void VideoFramesOrAudioTimelineProgressResetTheGracePeriod(){
        var watchdog=new PlaybackWatchdog(1000);
        assertFalse(watchdog.stalled(0,true,0,0));
        assertFalse(watchdog.stalled(900,true,0,1));
        assertFalse(watchdog.stalled(1800,true,500,1));
        assertFalse(watchdog.stalled(2799,true,500,1));
        assertTrue(watchdog.stalled(2800,true,500,1));
    }
    @Test void PausesFrozenFramesAndExplicitSeeksMustNotTriggerRecovery(){
        var watchdog=new PlaybackWatchdog(1000);
        assertFalse(watchdog.stalled(0,true,0,0));
        assertFalse(watchdog.stalled(100000,false,0,0));
        assertFalse(watchdog.stalled(100001,true,0,0));
        watchdog.reset();
        assertFalse(watchdog.stalled(500000,true,0,0));
    }
    @Test void BackwardClockAdjustmentsStartANewGracePeriod(){
        var watchdog=new PlaybackWatchdog(1000);
        assertFalse(watchdog.stalled(10000,true,0,0));
        assertFalse(watchdog.stalled(1,true,0,0));
        assertFalse(watchdog.stalled(1000,true,0,0));
        assertTrue(watchdog.stalled(1001,true,0,0));
    }
}
