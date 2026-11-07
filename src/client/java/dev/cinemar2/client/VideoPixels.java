package dev.cinemar2.client;

import java.nio.ByteBuffer;

/** Convert libmpv's rgb0 padding into real opaque RGBA without changing RGB. */
final class VideoPixels {
    static void makeOpaque(ByteBuffer pixels){
        for(int i=pixels.position()+3;i<pixels.limit();i+=4)pixels.put(i,(byte)255);
    }
}
