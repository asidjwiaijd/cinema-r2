package dev.cinemar2.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;

/** Minecraft texture adapter. All playback and sound belong to libmpv. */
public final class MediaPlayer implements AutoCloseable {
    private final MpvCore core;
    private final PlaybackWatchdog watchdog=new PlaybackWatchdog();
    private Identifier textureId;
    private VideoTexture texture;
    public final String url;
    public volatile boolean playing;
    private final boolean live;
    private final int quality,cacheSeconds,startSeconds,latency;
    private final boolean hasAudio;
    private volatile boolean frozen;
    private long uploadMicros;
    public MediaPlayer(String url,long position,boolean playing,float volume){this(url,position,playing,volume,false,12,true,720,1200,20);}
    public MediaPlayer(String url,long position,boolean playing,float volume,boolean live,int latency,boolean hasAudio){this(url,position,playing,volume,live,latency,hasAudio,720,1200,20);}
    public MediaPlayer(String url,long position,boolean playing,float volume,boolean live,int latency,boolean hasAudio,int quality,int cacheSeconds,int startSeconds){
        this.url=url;this.playing=playing;this.live=live;
        this.quality=quality;this.cacheSeconds=cacheSeconds;this.startSeconds=startSeconds;this.latency=latency;this.hasAudio=hasAudio;
        core=new MpvCore(CinemaClient.settings,url,position,playing,volume,live,latency,hasAudio,quality,cacheSeconds,startSeconds);
    }
    public void setTarget(long position){if(!frozen)core.setTarget(position);}
    public boolean matches(dev.cinemar2.Packets.State s){return url.equals(s.url())&&live==s.live()&&quality==s.quality()&&hasAudio==s.hasAudio()&&cacheSeconds==s.cacheSeconds()&&startSeconds==s.cacheStartSeconds()&&(!live||latency==s.liveLatencySeconds())&&!(frozen&&s.playing());}
    public void transport(long position,boolean play,boolean seek){if(!frozen){if(seek||play!=playing)watchdog.reset();playing=play;core.transport(position,play,seek);}}
    public void setVolume(float volume){core.setVolume(volume);}
    public long position(){return core.stats().positionMs();}
    public boolean hasFrames(){return core.hasFrames();}
    public String error(){return core.error();}
    public boolean stalled(long now){var stats=core.stats();return watchdog.stalled(now,playing&&!frozen,stats.positionMs(),stats.renderedFrames());}
    public MpvCore.Stats bufferStats(){return core.stats();}
    public long uploadMicros(){return uploadMicros;}
    public void freeze(){frozen=true;playing=false;core.freeze();}
    public void freezeLive(){if(live)freeze();}
    public Identifier uploadFrame(){
        core.copyFrame(pixels->{
            long start=System.nanoTime();
            if(texture==null){texture=new VideoTexture(core.width,core.height);textureId=Identifier.fromNamespaceAndPath("cinemar2","mpv/"+Long.toUnsignedString(System.nanoTime()));Minecraft.getInstance().getTextureManager().register(textureId,texture);}
            // Keep one bulk upload per decoded frame through Minecraft's graphics API.
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(),pixels,NativeImage.Format.RGBA,0,0,0,0,core.width,core.height);
            uploadMicros=(System.nanoTime()-start)/1000;
        });return textureId;
    }
    private static final class VideoTexture extends DynamicTexture {
        VideoTexture(int width,int height){super("Cinema R2 libmpv",width,height,false);}
    }
    @Override public void close(){core.close();if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);}
}
