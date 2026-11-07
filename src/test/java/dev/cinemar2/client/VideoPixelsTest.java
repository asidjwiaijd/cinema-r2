package dev.cinemar2.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VideoPixelsTest {
    @Test void undefinedPaddingBecomesOpaqueWithoutChangingRgb(){
        for(var order:new ByteOrder[]{ByteOrder.BIG_ENDIAN,ByteOrder.LITTLE_ENDIAN}){
            var pixels=ByteBuffer.allocateDirect(16).order(order);
            pixels.put(new byte[]{1,2,3,0,4,5,6,37,7,8,9,(byte)128,10,11,12,(byte)255}).flip();
            VideoPixels.makeOpaque(pixels);
            byte[] actual=new byte[16];pixels.get(actual);
            assertArrayEquals(new byte[]{1,2,3,-1,4,5,6,-1,7,8,9,-1,10,11,12,-1},actual);
        }
    }
    @Test void onlyTheSelectedFrameIsTouchedAndBufferStateIsPreserved(){
        var pixels=ByteBuffer.wrap(new byte[]{91,92,1,2,3,0,4,5,6,0,93,94});
        pixels.position(2).limit(10);
        VideoPixels.makeOpaque(pixels);
        assertEquals(2,pixels.position());assertEquals(10,pixels.limit());
        assertArrayEquals(new byte[]{91,92,1,2,3,-1,4,5,6,-1,93,94},pixels.array());
    }
}
