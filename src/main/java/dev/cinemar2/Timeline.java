package dev.cinemar2;

public final class Timeline {
    private long positionMs;
    private long anchorMs;
    private boolean playing;
    public Timeline(long positionMs, long anchorMs, boolean playing) {
        this.positionMs = Math.max(0, positionMs); this.anchorMs = anchorMs; this.playing = playing;
    }
    public long at(long now) { return positionMs + (playing ? Math.max(0, now - anchorMs) : 0); }
    public void pause(long now) { positionMs = at(now); anchorMs = now; playing = false; }
    public void play(long now) { if (!playing) { anchorMs = now; playing = true; } }
    public void seek(long position, long now) { positionMs = Math.max(0, Math.min(position, 86_400_000)); anchorMs = now; }
    public boolean playing() { return playing; }
}
