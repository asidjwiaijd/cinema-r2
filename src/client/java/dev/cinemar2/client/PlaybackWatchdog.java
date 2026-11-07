package dev.cinemar2.client;

/** Preserve slow-network buffers; recover only after a sustained absence of playback progress. */
final class PlaybackWatchdog {
    private final long timeoutMs;
    private boolean tracking;
    private long progressAt,lastNow,position,frames;
    PlaybackWatchdog(){this(180000);}
    PlaybackWatchdog(long timeoutMs){
        if(timeoutMs<=0)throw new IllegalArgumentException("Stall timeout must be positive");
        this.timeoutMs=timeoutMs;
    }
    void reset(){tracking=false;}
    boolean stalled(long now,boolean playing,long position,long frames){
        if(!playing){reset();return false;}
        if(!tracking||now<lastNow||position!=this.position||frames!=this.frames){
            tracking=true;progressAt=now;this.position=position;this.frames=frames;
        }
        lastNow=now;
        return now-progressAt>=timeoutMs;
    }
}
