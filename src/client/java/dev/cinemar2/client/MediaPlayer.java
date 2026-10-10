package dev.cinemar2.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/** Minecraft texture adapter. All playback and sound belong to libmpv. */
public final class MediaPlayer implements AutoCloseable {
    private final MpvCore core;
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
    public void transport(long position,boolean play,boolean seek){if(!frozen){playing=play;core.transport(position,play,seek);}}
    public void setVolume(float volume){core.setVolume(volume);}
    public long position(){return core.stats().positionMs();}
    public boolean hasFrames(){return core.hasFrames();}
    public String error(){return core.error();}
    // A network wait must not destroy libmpv's cache. Retry on actual errors.
    public boolean stalled(long now){return false;}
    public MpvCore.Stats bufferStats(){return core.stats();}
    public long uploadMicros(){return uploadMicros;}
    public void freeze(){frozen=true;playing=false;core.freeze();}
    public void freezeLive(){if(live)freeze();}
    public Identifier uploadFrame(){
        core.copyFrame(pixels->{
            long start=System.nanoTime();
            if(texture==null){texture=new VideoTexture();textureId=Identifier.of("cinemar2","mpv/"+Long.toUnsignedString(System.nanoTime()));MinecraftClient.getInstance().getTextureManager().registerTexture(textureId,texture);}
            int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);RenderSystem.bindTexture(texture.getGlId());
            try{
                if(!texture.allocated){
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                    // RGB8 discards rgb0's undefined padding byte, producing opaque alpha.
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGB8,core.width,core.height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);texture.allocated=true;
                }else GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,core.width,core.height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            }finally{RenderSystem.bindTexture(previous);}
            uploadMicros=(System.nanoTime()-start)/1000;
        });return textureId;
    }
    private static final class VideoTexture extends AbstractTexture {
        boolean allocated;
        @Override public void load(ResourceManager manager){}
    }
    @Override public void close(){core.close();if(textureId!=null)MinecraftClient.getInstance().getTextureManager().destroyTexture(textureId);}
}
