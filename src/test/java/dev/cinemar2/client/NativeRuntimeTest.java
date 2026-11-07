package dev.cinemar2.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Integration matrix for a changed native runtime, independent of the public media library. */
@EnabledIfEnvironmentVariable(named="CINEMA_NATIVE_MATRIX",matches="true")
class NativeRuntimeTest {
    private static Path dir;
    private static HttpServer fixtureServer;
    private static ExecutorService fixtureExecutor;
    private static void ffmpeg(String... args)throws Exception{
        var command=new ArrayList<>(List.of("ffmpeg","-y","-hide_banner","-loglevel","error"));command.addAll(List.of(args));
        var p=new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String log=new String(p.getInputStream().readAllBytes());assertTrue(p.waitFor(90,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),log);
    }
    @BeforeAll static void fixtures()throws Exception{
        dir=Files.createTempDirectory(Path.of(".tools").toAbsolutePath(),"cinema-native-matrix-");
        String input=Path.of(".tools/test-pattern.mp4").toAbsolutePath().toString();
        ffmpeg("-i",input,"-t","3","-vf","scale=320:180","-c:v","libx264","-threads","2","-pix_fmt","yuv420p","-c:a","aac",dir.resolve("h264-aac.mp4").toString());
        ffmpeg("-i",input,"-t","3","-vf","scale=320:180","-c:v","libx265","-threads","2","-x265-params","pools=2:frame-threads=2:log-level=error","-pix_fmt","yuv420p10le","-c:a","aac",dir.resolve("hevc10-aac.mkv").toString());
        ffmpeg("-i",input,"-t","3","-vf","scale=320:180","-c:v","libvpx-vp9","-threads","2","-deadline","realtime","-cpu-used","8","-c:a","libopus",dir.resolve("vp9-opus.webm").toString());
        ffmpeg("-i",input,"-t","3","-vf","scale=320:180","-c:v","libaom-av1","-threads","2","-cpu-used","8","-c:a","aac",dir.resolve("av1-aac.mp4").toString());
        ffmpeg("-i",input,"-an","-frames:v","1",dir.resolve("still.png").toString());
        ffmpeg("-i",input,"-vn","-c:a","libmp3lame",dir.resolve("audio.mp3").toString());
        ffmpeg("-i",input,"-vn","-c:a","flac",dir.resolve("audio.flac").toString());
        ffmpeg("-stream_loop","4","-i",input,"-t","16","-vf","scale=320:180","-c:v","libx264","-threads","2","-r","24","-g","24","-sc_threshold","0","-c:a","aac","-f","hls","-hls_time","1","-hls_segment_filename",dir.resolve("segment_%03d.ts").toString(),"-hls_playlist_type","vod",dir.resolve("index.m3u8").toString());
        ffmpeg("-i",dir.resolve("h264-aac.mp4").toString(),"-c","copy","-f","hls","-hls_segment_type","fmp4","-hls_time","1","-hls_playlist_type","vod",dir.resolve("fmp4.m3u8").toString());
        fixtureServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);fixtureExecutor=Executors.newCachedThreadPool();fixtureServer.setExecutor(fixtureExecutor);
        fixtureServer.createContext("/",exchange->{try{
            String name=Path.of(exchange.getRequestURI().getPath()).getFileName().toString();byte[] data=Files.readAllBytes(dir.resolve(name));
            exchange.sendResponseHeaders(200,data.length);try(var out=exchange.getResponseBody()){out.write(data);}
        }catch(Exception e){exchange.close();}});fixtureServer.start();
    }
    @AfterAll static void stop(){if(fixtureServer!=null)fixtureServer.stop(0);if(fixtureExecutor!=null)fixtureExecutor.shutdownNow();}
    private String url(String name){return "http://127.0.0.1:"+fixtureServer.getAddress().getPort()+"/"+name;}
    private ClientSettings settings(){var s=new ClientSettings();s.mpvLibraryPath=System.getProperty("cinema.mpv");s.cacheDirectory=dir.resolve("cache").toString();return s;}
    private void awaitReady(MpvCore p,boolean audio)throws Exception{
        long end=System.currentTimeMillis()+15000;
        while((!p.hasFrames()||p.stats().buffering()||(audio&&p.stats().audioDevice().isEmpty()))&&p.error().isEmpty()&&System.currentTimeMillis()<end)Thread.sleep(30);
        assertEquals("",p.error());assertTrue(p.hasFrames());assertFalse(p.stats().buffering(),p.stats().toString());if(audio)assertEquals("wasapi",p.stats().audioDevice());
    }
    @Test void commonVideoFormatsStillImageAndFragmentedMp4Decode()throws Exception{
        for(String name:List.of("h264-aac.mp4","hevc10-aac.mkv","vp9-opus.webm","av1-aac.mp4","fmp4.m3u8","still.png")){
            try(var p=new MpvCore(settings(),url(name),0,true,0,false,12,!name.endsWith("png"),720,600,1)){
                awaitReady(p,!name.endsWith("png"));boolean[] copied={false};p.copyFrame(pixels->{
                    copied[0]=pixels.remaining()==p.width*p.height*4;
                    for(int i=3;i<pixels.limit();i+=4096)assertEquals(255,Byte.toUnsignedInt(pixels.get(i)),"Decoded video alpha must be opaque");
                });assertTrue(copied[0]);
                System.out.println("Native format OK: "+name+"; hwdec="+p.stats().hardwareDecoder()+"; audio="+p.stats().audioDevice());
            }
        }
    }
    @Test void audioFormatsAndDynamicVolumeKeepTheNativeHandle()throws Exception{
        for(String name:List.of("audio.mp3","audio.flac")){
            try(var p=new MpvCore(settings(),url(name),0,true,0,false,12,true,720,600,1)){
                long end=System.currentTimeMillis()+10000;
                while((p.stats().audioDevice().isEmpty()||p.stats().positionMs()<100)&&p.error().isEmpty()&&System.currentTimeMillis()<end)Thread.sleep(30);
                assertEquals("",p.error());assertEquals("wasapi",p.stats().audioDevice());assertTrue(p.stats().positionMs()>=100);
                var field=MpvCore.class.getDeclaredField("handle");field.setAccessible(true);Object handle=field.get(p);
                var number=MpvCore.class.getDeclaredMethod("number",String.class,double.class);number.setAccessible(true);
                p.setVolume(.5f);Thread.sleep(100);assertEquals(50d,(double)number.invoke(p,"volume",-1),.1);
                p.setVolume(0);Thread.sleep(100);assertEquals(0d,(double)number.invoke(p,"volume",-1),.1);
                assertEquals(handle,field.get(p));System.out.println("Native audio/volume OK: "+name);
            }
        }
    }
    @Test void growingObsStyleLivePlaylistKeepsFramesAndWasapiAudio()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        long start=System.currentTimeMillis();
        server.createContext("/",exchange->{try{
            String name=Path.of(exchange.getRequestURI().getPath()).getFileName().toString();byte[] bytes;
            if(name.equals("live.m3u8")){
                int count=(int)Math.min(16,6+(System.currentTimeMillis()-start)/1000);
                var text=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n");
                for(int i=0;i<count;i++)text.append("#EXTINF:1.0,\nsegment_%03d.ts\n".formatted(i));
                if(count==16)text.append("#EXT-X-ENDLIST\n");bytes=text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/vnd.apple.mpegurl");
            }else bytes=Files.readAllBytes(dir.resolve(name));
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        }catch(Exception e){exchange.close();}});
        server.start();
        try(var p=new MpvCore(settings(),"http://127.0.0.1:"+server.getAddress().getPort()+"/live.m3u8",0,true,0,true,4,true,720,30,1)){
            awaitReady(p,true);long frames=p.stats().renderedFrames(),position=p.stats().positionMs();Thread.sleep(2000);
            assertTrue(p.stats().renderedFrames()>frames+10);assertTrue(p.stats().positionMs()>position+500);assertEquals("",p.error());
            System.out.println("Native growing live HLS OK: "+p.stats());
        }finally{server.stop(0);executor.shutdownNow();}
    }
}
