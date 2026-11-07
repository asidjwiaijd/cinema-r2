package dev.cinemar2;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CinemaCoverageTest {
    @Test void existingSmallServerRangesMigrateBeyondTheOldBlackScreenBoundary(){
        assertEquals(256,CinemaCoverage.distance(64));
        assertEquals(256,CinemaCoverage.distance(96));
        assertEquals(384,CinemaCoverage.distance(384));
        assertEquals(512,CinemaCoverage.distance(Integer.MAX_VALUE));
    }
}
