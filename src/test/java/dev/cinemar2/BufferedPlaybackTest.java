package dev.cinemar2;

import com.sun.net.httpserver.HttpServer;
import dev.cinemar2.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="cinema.integration",matches="true")
class BufferedPlaybackTest {
    private static Path dir;
    private static HttpServer server;
    private static ExecutorService executor;
    private static final AtomicBoolean delayed=new AtomicBoolean();
    @BeforeAll static void start()throws Exception{
        dir=Files.createTempDirectory("cinema-buffer-");
        var process=new ProcessBuilder("ffmpeg","-y","-hide_banner","-loglevel","error","-stream_loop","11","-i",Path.of(".tools/test-pattern.mp4").toAbsolutePath().toString(),"-t","48","-c:v","libx264","-preset","ultrafast","-threads","2","-pix_fmt","yuv420p","-r","24","-g","24","-sc_threshold","0","-c:a","aac","-ar","48000","-f","hls","-hls_time","1","-hls_playlist_type","vod","-hls_segment_filename",dir.resolve("segment_%03d.ts").toString(),dir.resolve("index.m3u8").toString()).redirectErrorStream(true).start();
        String log=new String(process.getInputStream().readAllBytes());assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),log);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",exchange->{
            try{
                String name=Path.of(exchange.getRequestURI().getPath()).getFileName().toString();
                if(name.equals("segment_008.ts")&&delayed.compareAndSet(false,true))Thread.sleep(4000);
                byte[] data=Files.readAllBytes(dir.resolve(name));exchange.sendResponseHeaders(200,data.length);
                try(var out=exchange.getResponseBody()){out.write(data);}
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception ignored){exchange.close();}
        });server.start();
    }
    @AfterAll static void stop()throws Exception{
        if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();
        if(dir!=null&&dir.toAbsolutePath().normalize().getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())&&dir.getFileName().toString().startsWith("cinema-buffer-")){
            try(var files=Files.walk(dir)){for(var f:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}
        }
    }
    private ClientSettings settings(){var s=new ClientSettings();s.mpvLibraryPath=System.getProperty("cinema.mpv");s.cacheDirectory=dir.resolve("cache").toString();return s;}
    @Test void cachedPauseResumeAndSeekKeepTheSameDecoderAndRespondQuickly()throws Exception{
        try(var p=new MpvCore(settings(),"http://127.0.0.1:"+server.getAddress().getPort()+"/index.m3u8",0,true,0,false,12,true,720,1200,20)){
            long deadline=System.currentTimeMillis()+25000;
            while((!p.hasFrames()||p.stats().buffering()||p.stats().cacheSeconds()<25)&&System.currentTimeMillis()<deadline)Thread.sleep(30);
            assertEquals("",p.error());assertTrue(p.stats().cacheSeconds()>25);
            var field=MpvCore.class.getDeclaredField("handle");field.setAccessible(true);Object handle=field.get(p);
            long bytes=p.stats().cacheDiskBytes();
            p.transport(p.stats().positionMs(),false,false);Thread.sleep(500);
            long paused=p.stats().positionMs();Thread.sleep(500);assertEquals(paused,p.stats().positionMs(),"Pause must stop advancing");
            long resume=System.currentTimeMillis();p.transport(paused,true,false);
            while(p.stats().positionMs()<paused+400&&System.currentTimeMillis()-resume<1500)Thread.sleep(20);
            assertTrue(p.stats().positionMs()>=paused+400,"Resume should use existing buffered packets");
            long resumedMs=System.currentTimeMillis()-resume;
            long seek=System.currentTimeMillis();p.transport(15000,false,true);
            while((Math.abs(p.stats().positionMs()-15000)>150||p.stats().buffering())&&System.currentTimeMillis()-seek<1500)Thread.sleep(20);
            assertTrue(Math.abs(p.stats().positionMs()-15000)<=150,"Cached paused seek: "+p.stats());
            long seekMs=System.currentTimeMillis()-seek;
            assertEquals(handle,field.get(p));assertTrue(p.stats().cacheDiskBytes()>=bytes,"Transport must preserve the disk cache");
            Thread.sleep(400);assertTrue(Math.abs(p.stats().positionMs()-15000)<=150);assertEquals("",p.error());
            System.out.println("Cached controls: resume advanced 400ms in "+resumedMs+"ms, paused seek "+seekMs+"ms, unchanged native handle and disk cache");
        }
    }
    @Test void libmpvCachesDelayedHlsAndKeepsAudioSynchronizedAtBothQualities()throws Exception{
        for(int quality:new int[]{720,1080}){
            try(var p=new MpvCore(settings(),"http://127.0.0.1:"+server.getAddress().getPort()+"/index.m3u8",0,true,0,false,12,true,quality,1200,12)){
                long deadline=System.currentTimeMillis()+25000;while((!p.hasFrames()||p.stats().buffering()||p.stats().positionMs()<200||p.stats().audioDevice().isEmpty())&&System.currentTimeMillis()<deadline)Thread.sleep(50);
                assertTrue(p.hasFrames(),p.error());assertEquals("",p.error());assertEquals(quality,p.height);
                long previous=p.stats().positionMs(),maxGap=0,lastChange=System.currentTimeMillis();double maxCache=0;
                long end=System.currentTimeMillis()+18000;
                while(System.currentTimeMillis()<end){
                    var stats=p.stats();long now=System.currentTimeMillis();maxCache=Math.max(maxCache,stats.cacheSeconds());
                    if(stats.positionMs()!=previous){maxGap=Math.max(maxGap,now-lastChange);lastChange=now;previous=stats.positionMs();}
                    assertFalse(stats.audioDevice().isEmpty(),"Audio output must stay present");
                    assertTrue(Math.abs(stats.avSyncSeconds())<0.25,"AV drift: "+stats);assertEquals("",p.error());Thread.sleep(50);
                }
                assertTrue(maxGap<1000,"Playback stalled "+maxGap+" ms");assertTrue(maxCache>20,"Compressed prefetch: "+maxCache);
                assertTrue(p.stats().cacheDiskBytes()>0,"Disk cache must actually be used");assertTrue(p.stats().renderedFrames()>200);
                System.out.println("libmpv "+quality+"p: max sampled gap "+maxGap+"ms, cache "+maxCache+"s, AV "+p.stats().avSyncSeconds()+"s, audio "+p.stats().audioDevice());
                p.freeze();Thread.sleep(300);long frozen=p.stats().positionMs();Thread.sleep(300);assertEquals(frozen,p.stats().positionMs());assertTrue(p.hasFrames());
            }
        }
        assertTrue(delayed.get());
    }
}
