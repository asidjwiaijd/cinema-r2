package dev.cinemar2.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinemaAudioTest {
    @Test void screenMuteOverridesPersonalVolumeAtEveryDistance(){
        assertEquals(0,CinemaAudio.gain(.7f,0,0));
        assertEquals(0,CinemaAudio.gain(1,0,32));
    }
    @Test void cinemaSeatsKeepTheirLevelBeyondTheOld64BlockBoundary(){
        assertEquals(1,CinemaAudio.gain(.7f,100,0),.0001);
        assertEquals(.525f,CinemaAudio.gain(.7f,50,32),.0001);
        assertEquals(1,CinemaAudio.gain(.7f,100,64),.0001);
        assertEquals(1,CinemaAudio.gain(.7f,100,128),.0001);
        assertEquals(.525f,CinemaAudio.gain(.7f,100,192),.0001);
        assertEquals(0,CinemaAudio.gain(.7f,100,256));
        assertEquals(0,CinemaAudio.gain(0,100,0));
    }
    @Test void largerCinemaKeepsAudioAcrossItsSeatsAndFadesContinuouslyAtTheEdge(){
        double full=dev.cinemar2.CinemaCoverage.fullVolumeDistance(100,256);
        assertEquals(192,full);
        assertEquals(.7f,CinemaAudio.gain(.7f,100,180,256,full,1),.0001);
        float previous=1;
        for(int distance=0;distance<=260;distance++){
            float current=CinemaAudio.gain(.7f,100,distance,256,full,1.5f);
            assertTrue(current<=previous+.0001);previous=current;
        }
        assertEquals(0,CinemaAudio.gain(1,0,192,256,full,3));
    }
    @Test void unsafeLevelsNeverAmplifyOrReachNativeCodeAsNan(){
        assertEquals(1,CinemaAudio.gain(10,999,-1));
        assertEquals(0,CinemaAudio.gain(Float.NaN,100,0));
        assertEquals(0,CinemaAudio.gain(1,100,Double.NaN));
    }
}
