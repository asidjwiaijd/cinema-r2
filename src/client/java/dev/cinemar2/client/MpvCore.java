package dev.cinemar2.client;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** libmpv owns media IO, compressed packet cache, decoding, AV clock and audio. */
public final class MpvCore implements AutoCloseable {
    public record Stats(long positionMs,double cacheSeconds,long cacheDiskBytes,boolean buffering,
                        double avSyncSeconds,String audioDevice,String hardwareDecoder,long renderedFrames) {}
    private final ClientSettings settings;
    private final String url;
    private final long origin;
    private final boolean live,hasAudio;
    private final int latency,cacheSeconds,startSeconds;
    public final int width,height;
    private final Object pixelsLock=new Object();
    private final AtomicBoolean update=new AtomicBoolean();
    private volatile boolean closed,frozen,frames,dirty;
    private volatile float volume;
    private volatile boolean playing;
    private volatile long target=-1,lastSync,rendered;
    private volatile Stats stats;
    private volatile String error="";
    private NativeMpv api;
    private Pointer handle,render,pixels;
    private Memory allocation;
    private NativeMpv.Update callback;
    private final Thread worker;
    private volatile MediaRelay relay;
    private final MediaRelay suppliedRelay;
    private record Transport(long position,boolean playing,boolean seek) {}
    private final ConcurrentLinkedQueue<Transport> controls=new ConcurrentLinkedQueue<>();
    public void transport(long position,boolean play,boolean seek){controls.add(new Transport(position,play,seek));}
    public MpvCore(ClientSettings settings,String url,long origin,boolean playing,float volume,boolean live,int latency,
                   boolean hasAudio,int quality,int cacheSeconds,int startSeconds){
        this(settings,url,origin,playing,volume,live,latency,hasAudio,quality,cacheSeconds,startSeconds,null);
    }
    MpvCore(ClientSettings settings,String url,long origin,boolean playing,float volume,boolean live,int latency,
            boolean hasAudio,int quality,int cacheSeconds,int startSeconds,MediaRelay suppliedRelay){
        this.settings=settings;this.url=url;this.origin=origin;this.playing=playing;this.volume=volume;
        this.suppliedRelay=suppliedRelay;
        this.live=live;this.latency=latency;this.hasAudio=hasAudio;this.cacheSeconds=cacheSeconds;this.startSeconds=startSeconds;
        height=quality==1080?1080:720;width=height*16/9;
        stats=new Stats(origin,0,0,true,0,"","",0);
        worker=new Thread(this::run,"cinema-libmpv");worker.setDaemon(true);worker.start();
    }
    private static Memory string(String value){byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);var m=new Memory(bytes.length+1);m.write(0,bytes,0,bytes.length);m.setByte(bytes.length,(byte)0);return m;}
    private static Memory params(int[] types,Pointer[] data){var m=new Memory((types.length+1)*16L);m.clear();for(int i=0;i<types.length;i++){m.setInt(i*16L,types[i]);m.setPointer(i*16L+8,data[i]);}return m;}
    private void check(int code){if(code<0)throw new IllegalStateException(api.mpv_error_string(code));}
    private void option(String name,String value){check(api.mpv_set_option_string(handle,name,value));}
    private void property(String name,String value){check(api.mpv_set_property_string(handle,name,value));}
    private double number(String name,double fallback){try(var m=new Memory(8)){return api.mpv_get_property(handle,name,5,m)<0?fallback:m.getDouble(0);}}
    private boolean flag(String name){try(var m=new Memory(4)){return api.mpv_get_property(handle,name,3,m)>=0&&m.getInt(0)!=0;}}
    private String text(String name){var p=api.mpv_get_property_string(handle,name);if(p==null)return "";try{return p.getString(0,"UTF-8");}finally{api.mpv_free(p);}}
    private record CacheInfo(long diskBytes,double forwardSeconds) {}
    private CacheInfo cacheInfo(double position){try(var node=new Memory(16)){
        node.clear();if(api.mpv_get_property(handle,"demuxer-cache-state",6,node)<0)return new CacheInfo(0,-1);
        try{if(node.getInt(8)!=8)return new CacheInfo(0,-1);var list=node.getPointer(0);int count=list.getInt(0);var values=list.getPointer(8);var keys=list.getPointer(16);
            long bytes=0;double forward=-1;
            for(int i=0;i<count;i++){
                String key=keys.getPointer(i*8L).getString(0,"UTF-8");long offset=i*16L;
                if(key.equals("file-cache-bytes")&&values.getInt(offset+8)==4)bytes=values.getLong(offset);
                if(key.equals("seekable-ranges")&&values.getInt(offset+8)==7){
                    forward=0;var ranges=values.getPointer(offset);var nodes=ranges.getPointer(8);
                    for(int r=0;r<ranges.getInt(0);r++){
                        if(nodes.getInt(r*16L+8)!=8)continue;var range=nodes.getPointer(r*16L);var rv=range.getPointer(8);var rk=range.getPointer(16);double start=Double.NaN,end=Double.NaN;
                        for(int j=0;j<range.getInt(0);j++)if(rv.getInt(j*16L+8)==5){String k=rk.getPointer(j*8L).getString(0,"UTF-8");if(k.equals("start"))start=rv.getDouble(j*16L);if(k.equals("end"))end=rv.getDouble(j*16L);}
                        if(position>=start-0.5&&position<=end)forward=Math.max(forward,end-position);
                    }
                }
            }
            return new CacheInfo(bytes,forward);
        }finally{api.mpv_free_node_contents(node);}
    }}
    private void command(String... args){Memory[] strings=new Memory[args.length];try(var pointers=new Memory((args.length+1L)*Native.POINTER_SIZE)){
        pointers.clear();for(int i=0;i<args.length;i++){strings[i]=string(args[i]);pointers.setPointer((long)i*Native.POINTER_SIZE,strings[i]);}check(api.mpv_command(handle,pointers));
    }finally{for(var s:strings)if(s!=null)s.close();}}
    private void run(){
        try{
            relay=suppliedRelay!=null?suppliedRelay:MediaRelay.open(settings,url,live);
            if(relay!=null){relay.enableCacheAware(startSeconds,cacheSeconds);if(suppliedRelay==null)relay.enableParallelCache(settings,origin/1000.0);}
            api=NativeMpv.load(settings.mpvLibraryPath);handle=api.mpv_create();if(handle==null)throw new IllegalStateException("mpv_create failed");
            option("config","no");option("terminal","no");option("vo","libmpv");option("hwdec","auto-copy");
            option("tls-verify","yes");if(!settings.tlsCaFile.isBlank())option("tls-ca-file",settings.tlsCaFile);
            option("video-sync","audio");option("keep-open","yes");option("audio-display","no");option("sub","no");
            option("pause",playing?"no":"yes");option("volume",Float.toString(volume*100));
            if(!hasAudio)option("aid","no");if(!live)option("start",String.format(Locale.ROOT,"%.3f",origin/1000.0));
            option("cache","yes");option("cache-secs",Integer.toString(live?30:Math.max(30,Math.min(3600,cacheSeconds))));
            option("demuxer-max-bytes",settings.cacheMemoryMB+"MiB");option("demuxer-max-back-bytes",settings.cacheMemoryMB/2+"MiB");
            option("cache-pause","yes");option("cache-pause-initial","yes");option("cache-pause-wait",Integer.toString(live?4:Math.max(1,Math.min(120,startSeconds))));
            var dir=Path.of(settings.cacheDirectory).toAbsolutePath();Files.createDirectories(dir);
            boolean disk=settings.diskCache&&Files.getFileStore(dir).getUsableSpace()>(settings.cacheDiskGB+2L)*1024*1024*1024;
            option("cache-on-disk",disk?"yes":"no");if(disk)option("demuxer-cache-dir",dir.toString());
            // The loopback request may wait for R2 while cached video keeps playing.
            // Actual HTTPS attempts remain bounded inside MediaRelay.
            option("network-timeout",relay!=null&&!live?"3660":"30");option("stream-lavf-o","reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_on_http_error=500,502,503,504,reconnect_delay_max=5");
            if(live)option("demuxer-lavf-o","live_start_index="+(-Math.max(1,latency/4)));
            check(api.mpv_initialize(handle));
            try(var kind=string("sw");var create=params(new int[]{1},new Pointer[]{kind})){
                var out=new PointerByReference();check(api.mpv_render_context_create(out,handle,create));render=out.getValue();
            }
            callback=opaque->update.set(true);api.mpv_render_context_set_update_callback(render,callback,null);
            allocation=new Memory((long)width*height*4+64);long address=Pointer.nativeValue(allocation);pixels=allocation.share((64-address%64)%64);
            try(var size=new Memory(8);var format=string("rgb0");var stride=new Memory(Native.SIZE_T_SIZE);var timing=new Memory(4)){
                size.setInt(0,width);size.setInt(4,height);stride.setLong(0,width*4L);timing.setInt(0,0);
                try(var draw=params(new int[]{17,18,19,20,12},new Pointer[]{size,format,stride,pixels,timing})){
                    command("loadfile",relay==null?url:relay.url(),"replace");long nextStats=0;float lastVolume=volume;boolean diskLimited=false,initialBuffered=false;
                    while(!closed){
                        for(int i=0;i<30;i++){var event=api.mpv_wait_event(handle,0);int id=event.getInt(0);if(id==0)break;
                            if(id==7){var detail=event.getPointer(16);if(detail!=null&&detail.getInt(4)<0)error="libmpv: "+api.mpv_error_string(detail.getInt(4));}
                        }
                        if(frozen){property("pause","yes");property("mute","yes");break;}
                        Transport control;
                        while((control=controls.poll())!=null){
                            playing=control.playing();target=control.position();property("pause",playing?"no":"yes");
                            property("speed","1.0");
                            if(!live&&control.seek()){
                                // Keep this demuxer and its disk cache. Cached seeks do not wait
                                // for the first-load prebuffer threshold again.
                                property("cache-pause-initial","no");
                                if(relay!=null)relay.prepareSeek(target/1000.0);
                                command("seek",String.format(Locale.ROOT,"%.3f",target/1000.0),"absolute+exact");
                                lastSync=System.currentTimeMillis();
                            }
                        }
                        if(lastVolume!=volume){property("volume",Float.toString(volume*100));lastVolume=volume;}
                        if(update.getAndSet(false)&&(api.mpv_render_context_update(render)&1)!=0){
                            synchronized(pixelsLock){check(api.mpv_render_context_render(render,draw));dirty=true;frames=true;rendered++;}
                        }
                        long now=System.currentTimeMillis();if(now>=nextStats){
                            long position=(long)(number("time-pos",origin/1000.0)*1000);
                            double cache=number("demuxer-cache-duration",0);String audio=text("current-ao");
                            boolean buffering=!frames||flag("paused-for-cache")||flag("seeking")||(playing&&hasAudio&&audio.isEmpty());
                            var cached=cacheInfo(position/1000.0);long diskBytes=cached.diskBytes();
                            if(relay!=null){double usable=cached.forwardSeconds()<0?Math.max(0,cache-2):Math.min(cache,cached.forwardSeconds());relay.updateBuffer(usable,playing&&!buffering,flag("seeking"));relay.updatePlayback(position/1000.0,playing&&!buffering,flag("seeking"));}
                            stats=new Stats(position,cache,diskBytes,buffering,number("avsync",0),audio,text("hwdec-current"),rendered);
                            if(!initialBuffered&&frames&&!buffering){property("cache-pause-initial","no");initialBuffered=true;}
                            if(disk&&!diskLimited&&diskBytes>settings.cacheDiskGB*1024L*1024*1024){property("cache-on-disk","no");diskLimited=true;}
                            // Never seek while buffering: it would discard the cache being filled.
                            if(!live&&playing&&frames&&!buffering&&target>=0){
                                long diff=target-position;
                                if(Math.abs(diff)>1200&&now-lastSync>3000&&cache>2&&(diff<0||diff<cache*1000-1000)){if(relay!=null)relay.prepareSeek(target/1000.0);command("seek",String.format(Locale.ROOT,"%.3f",target/1000.0),"absolute+exact");lastSync=now;}
                                else property("speed",Math.abs(diff)>200?(diff>0?"1.05":"0.95"):"1.0");
                            }
                            nextStats=now+250;
                        }
                        Thread.sleep(5);
                    }
                }
            }
        }catch(Throwable e){if(!closed)error="libmpv 启动/播放失败："+e.getMessage();}
        finally{
            if(relay!=null)relay.close();
            // Release the playback core (including disk cache) but retain a frozen frame.
            if(render!=null)api.mpv_render_context_free(render);
            if(handle!=null)api.mpv_terminate_destroy(handle);
            synchronized(pixelsLock){if(closed&&allocation!=null){allocation.close();allocation=null;}}
        }
    }
    public Stats stats(){return stats;}
    public boolean hasFrames(){return frames;}
    public String error(){return error;}
    public void setTarget(long position){target=position;}
    public void setVolume(float value){volume=Math.max(0,Math.min(1,value));}
    public void copyFrame(Consumer<ByteBuffer> upload){synchronized(pixelsLock){if(dirty&&pixels!=null&&allocation!=null){upload.accept(pixels.getByteBuffer(0,(long)width*height*4));dirty=false;}}}
    public void freeze(){playing=false;frozen=true;}
    @Override public void close(){closed=true;if(relay!=null)relay.close();worker.interrupt();synchronized(pixelsLock){if(!worker.isAlive()&&allocation!=null){allocation.close();allocation=null;}}}
}
