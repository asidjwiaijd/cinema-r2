package dev.cinemar2.client;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaRelayTest {
    HttpServer primary,cdn,paid;
    ExecutorService executor;
    final Map<String,byte[]> files=new ConcurrentHashMap<>();
    final Set<String> broken=ConcurrentHashMap.newKeySet();
    final AtomicBoolean allowed=new AtomicBoolean(true),rangeFailure=new AtomicBoolean();
    final AtomicInteger cdnMedia=new AtomicInteger(),r2Media=new AtomicInteger();
    final AtomicInteger paidMedia=new AtomicInteger();
    final Set<String> eoBrokenFiles=ConcurrentHashMap.newKeySet();
    final AtomicInteger paidDeadlineSegment=new AtomicInteger();
    final AtomicBoolean eoMediaBroken=new AtomicBoolean(),eoPolicyBroken=new AtomicBoolean(),backupAllowed=new AtomicBoolean(true);
    final AtomicInteger startupSeconds=new AtomicInteger(60),emergencySeconds=new AtomicInteger(30);
    final AtomicInteger primaryDelayMs=new AtomicInteger();
    ScheduledExecutorService feedback;
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void start()throws Exception{
        executor=Executors.newVirtualThreadPerTaskExecutor();
        feedback=Executors.newSingleThreadScheduledExecutor();
        primary=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);cdn=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);paid=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        primary.setExecutor(executor);cdn.setExecutor(executor);paid.setExecutor(executor);
        primary.createContext("/",e->serve(e,0));cdn.createContext("/",e->serve(e,1));paid.createContext("/",e->serve(e,2));primary.start();cdn.start();paid.start();
        files.put("index.m3u8","#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nsegment_001.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8));
        files.put("segment_001.ts",new byte[]{1,2,3,4,5});
    }
    @AfterEach void stop(){feedback.shutdownNow();primary.stop(0);cdn.stop(0);paid.stop(0);executor.shutdownNow();}
    void serve(HttpExchange e,int origin){
        boolean fallback=origin!=0;
        try{
            if(e.getRequestURI().getPath().startsWith("/api/")){
                if(origin==1&&eoPolicyBroken.get()){e.sendResponseHeaders(503,-1);return;}
                e.getRequestBody().readAllBytes();byte[] json=(e.getRequestURI().getPath().endsWith("policy")?"{\"cdnEnabled\":"+allowed.get()+",\"backupEnabled\":"+backupAllowed.get()+",\"vodFallback\":true,\"sessionLimitMB\":0,\"vodStartupCdnSeconds\":"+startupSeconds.get()+",\"vodEmergencyCdnSeconds\":"+emergencySeconds.get()+",\"reportToken\":\"fixture\"}":"{\"ok\":true}").getBytes();e.sendResponseHeaders(200,json.length);e.getResponseBody().write(json);return;
            }
            if(origin==2)paidMedia.incrementAndGet();else if(fallback)cdnMedia.incrementAndGet();else r2Media.incrementAndGet();
            if(!fallback&&primaryDelayMs.get()>0)Thread.sleep(primaryDelayMs.get());
            String name=e.getRequestURI().getPath().substring(e.getRequestURI().getPath().lastIndexOf('/')+1);
            if(origin==2&&name.equals("segment_015.ts"))paidDeadlineSegment.incrementAndGet();
            byte[] data=files.get(name);String range=e.getRequestHeaders().getFirst("Range");
            if(data==null||(origin==1&&(eoMediaBroken.get()||eoBrokenFiles.contains(name)))||(!fallback&&(broken.contains(name)||(rangeFailure.get()&&range!=null&&Long.parseLong(range.substring(6,range.indexOf('-')))>=1024*1024)))){e.sendResponseHeaders(503,-1);return;}
            e.getResponseHeaders().set("Content-Length",Integer.toString(data.length));
            if(e.getRequestMethod().equals("HEAD")){e.sendResponseHeaders(200,-1);return;}
            if(range!=null){String[] parts=range.substring(6).split("-",-1);int start=Integer.parseInt(parts[0]),end=parts[1].isEmpty()?data.length-1:Math.min(data.length-1,Integer.parseInt(parts[1]));e.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+data.length);e.sendResponseHeaders(206,end-start+1);e.getResponseBody().write(data,start,end-start+1);}
            else{e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);}
        }catch(Exception ignored){}finally{e.close();}
    }
    URI base(HttpServer s){return URI.create("http://127.0.0.1:"+s.getAddress().getPort());}
    MediaRelay relay(String name)throws Exception{return new MediaRelay(base(primary).resolve("/media/fixture/"+name),base(cdn).resolve("/media/fixture/"+name),base(paid).resolve("/media/fixture/"+name),base(cdn),base(paid),false,1500,1500);}
    HttpResponse<byte[]> read(String url)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(url)).build(),HttpResponse.BodyHandlers.ofByteArray());}
    void pump(MediaRelay relay,AtomicInteger seconds){feedback.scheduleWithFixedDelay(()->relay.updateBuffer(seconds.get(),false,false),0,50,TimeUnit.MILLISECONDS);relay.updateBuffer(seconds.get(),false,false);}
    void awaitR2(int count)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(r2Media.get()<count&&System.nanoTime()<end)Thread.sleep(20);assertTrue(r2Media.get()>=count);}
    @Test void cacheAwareStartupUsesCdnThenReturnsToR2EvenBeforeFirstStatsTick()throws Exception{
        startupSeconds.set(4);
        try(var relay=relay("index.m3u8")){
            relay.enableCacheAware(1,600);var uri=URI.create(relay.url());
            assertEquals(200,read(uri.toString()).statusCode());
            assertEquals(200,read(uri.resolve("segment_001.ts").toString()).statusCode());
            assertEquals(0,r2Media.get());assertEquals(2,cdnMedia.get());assertEquals("CDN 快速起播",relay.route());
            assertEquals(200,read(uri.resolve("segment_001.ts").toString()).statusCode());
            assertEquals(1,r2Media.get());assertEquals(2,cdnMedia.get());assertEquals("R2",relay.route());
        }
    }
    @Test void longCacheRetriesR2AndDoesNotSpendCdnBytesBeforeRecovery()throws Exception{
        startupSeconds.set(0);broken.add("segment_001.ts");
        try(var relay=relay("index.m3u8")){
            relay.enableCacheAware(1,600);pump(relay,new AtomicInteger(600));
            var result=client.sendAsync(HttpRequest.newBuilder(URI.create(relay.url()).resolve("segment_001.ts")).build(),HttpResponse.BodyHandlers.ofByteArray());
            awaitR2(2);assertFalse(result.isDone());assertEquals(0,cdnMedia.get());broken.clear();
            assertArrayEquals(files.get("segment_001.ts"),result.get(5,TimeUnit.SECONDS).body());
            assertTrue(r2Media.get()>=3);assertEquals(0,cdnMedia.get());assertEquals(0,paidMedia.get());assertEquals("R2",relay.route());
        }
    }
    @Test void slowR2FinishesWithinCacheDeadlineDespiteShortConfiguredTimeout()throws Exception{
        startupSeconds.set(0);primaryDelayMs.set(400);
        try(var relay=new MediaRelay(base(primary).resolve("/media/fixture/index.m3u8"),base(cdn).resolve("/media/fixture/index.m3u8"),base(paid).resolve("/media/fixture/index.m3u8"),base(cdn),base(paid),false,100,1500)){
            relay.enableCacheAware(1,600);pump(relay,new AtomicInteger(120));
            assertArrayEquals(files.get("segment_001.ts"),read(URI.create(relay.url()).resolve("segment_001.ts").toString()).body());
            assertEquals(1,r2Media.get());assertEquals(0,cdnMedia.get());assertEquals("R2",relay.route());
        }
    }
    @Test void inFlightSlowR2YieldsAsSoonAsCacheEntersEmergencyWindow()throws Exception{
        startupSeconds.set(0);primaryDelayMs.set(5000);var seconds=new AtomicInteger(120);
        try(var relay=relay("index.m3u8")){
            relay.enableCacheAware(1,600);pump(relay,seconds);
            var result=client.sendAsync(HttpRequest.newBuilder(URI.create(relay.url()).resolve("segment_001.ts")).build(),HttpResponse.BodyHandlers.ofByteArray());
            awaitR2(1);assertFalse(result.isDone());assertEquals(0,cdnMedia.get());seconds.set(25);
            assertArrayEquals(files.get("segment_001.ts"),result.get(2,TimeUnit.SECONDS).body());
            assertEquals(1,cdnMedia.get());assertEquals("CDN 临近播放兜底",relay.route());
        }
    }
    @Test void cdnWaitsUntilCacheFallsIntoDeadlineWindowAndPaidIsLast()throws Exception{
        startupSeconds.set(0);broken.add("segment_001.ts");eoMediaBroken.set(true);var seconds=new AtomicInteger(600);
        try(var relay=relay("index.m3u8")){
            relay.enableCacheAware(1,600);pump(relay,seconds);
            var result=client.sendAsync(HttpRequest.newBuilder(URI.create(relay.url()).resolve("segment_001.ts")).build(),HttpResponse.BodyHandlers.ofByteArray());
            awaitR2(2);assertFalse(result.isDone());assertEquals(0,cdnMedia.get());assertEquals(0,paidMedia.get());seconds.set(25);
            assertArrayEquals(files.get("segment_001.ts"),result.get(2,TimeUnit.SECONDS).body());
            assertEquals(1,cdnMedia.get());assertEquals(1,paidMedia.get());assertEquals("备用 CDN 临近播放兜底",relay.route());
        }
    }
    @Test void seekCancelsObsoleteRetriesAndNewPositionUsesQuickStart()throws Exception{
        startupSeconds.set(4);broken.add("segment_001.ts");
        try(var relay=relay("index.m3u8")){
            relay.enableCacheAware(1,600);pump(relay,new AtomicInteger(600));
            var uri=URI.create(relay.url()).resolve("segment_001.ts");
            var old=client.sendAsync(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofByteArray());awaitR2(1);
            feedback.shutdownNow();relay.prepareSeek();
            assertEquals(503,old.get(2,TimeUnit.SECONDS).statusCode());assertEquals(0,cdnMedia.get());
            assertEquals(200,read(uri.toString()).statusCode());assertEquals("CDN 快速起播",relay.route());assertEquals(1,cdnMedia.get());
        }
    }
    @Test void quickStartRespectsCdnDisableAndStillPlaysHealthyR2()throws Exception{
        allowed.set(false);
        try(var relay=relay("index.m3u8")){
            relay.enableCacheAware(1,600);assertEquals(200,read(relay.url()).statusCode());assertTrue(r2Media.get()>0);assertEquals(0,cdnMedia.get());assertEquals(0,paidMedia.get());
        }
    }
    @Test void healthyR2UsesNoCdnMediaAndRewritesEverySegment()throws Exception{
        try(var relay=relay("index.m3u8")){
            var uri=URI.create(relay.url());String manifest=client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString()).body();
            String segment=manifest.lines().filter(s->!s.isEmpty()&&!s.startsWith("#")).findFirst().orElseThrow();
            var result=client.send(HttpRequest.newBuilder(uri.resolve(segment)).build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,result.statusCode());assertArrayEquals(files.get("segment_001.ts"),result.body());assertEquals(0,cdnMedia.get());assertEquals(0,paidMedia.get());assertEquals("R2",relay.route());assertTrue(relay.r2Bytes()>5);
        }
    }
    @Test void failedSegmentSwitchesToIdenticalCdnReplicaWithoutChangingPlayerUrl()throws Exception{
        broken.add("segment_001.ts");
        try(var relay=relay("index.m3u8")){
            var uri=URI.create(relay.url());String original=relay.url();String text=client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString()).body();
            String segment=text.lines().filter(s->!s.isEmpty()&&!s.startsWith("#")).findFirst().orElseThrow();
            var result=client.send(HttpRequest.newBuilder(uri.resolve(segment)).build(),HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(files.get("segment_001.ts"),result.body());assertEquals(original,relay.url());assertEquals("CDN 回退",relay.route());assertEquals(1,cdnMedia.get());assertTrue(relay.cdnBytes()>=5);
        }
    }
    @Test void mp4CanChangeOriginBetweenBoundedRangesAndKeepOneContinuousResponse()throws Exception{
        byte[] bytes=new byte[3*1024*1024+123];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);files.put("original.mp4",bytes);rangeFailure.set(true);
        try(var relay=relay("original.mp4")){
            var result=client.send(HttpRequest.newBuilder(URI.create(relay.url())).header("Range","bytes=500000-2500000").build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206,result.statusCode());assertEquals("bytes 500000-2500000/"+bytes.length,result.headers().firstValue("Content-Range").orElseThrow());assertArrayEquals(Arrays.copyOfRange(bytes,500000,2500001),result.body());assertTrue(cdnMedia.get()>0);assertTrue(relay.r2Bytes()>=1024*1024);
        }
    }
    @Test void disabledCdnNeverReceivesMediaWhenPrimaryFails()throws Exception{
        allowed.set(false);broken.add("index.m3u8");
        try(var relay=relay("index.m3u8")){var response=client.send(HttpRequest.newBuilder(URI.create(relay.url())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(503,response.statusCode());assertEquals(0,cdnMedia.get());assertEquals(0,paidMedia.get());}
    }
    @Test void paidCdnIsLastAndPolicySurvivesEoOutage()throws Exception{
        broken.add("segment_001.ts");eoMediaBroken.set(true);eoPolicyBroken.set(true);
        try(var relay=relay("index.m3u8")){
            var uri=URI.create(relay.url());String manifest=client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString()).body();
            String segment=manifest.lines().filter(s->!s.isEmpty()&&!s.startsWith("#")).findFirst().orElseThrow();
            var response=client.send(HttpRequest.newBuilder(uri.resolve(segment)).build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,response.statusCode());assertArrayEquals(files.get("segment_001.ts"),response.body());assertEquals(1,paidMedia.get());assertEquals(1,cdnMedia.get());assertEquals("备用 CDN 回退",relay.route());
        }
    }
    @Test void disabledPaidBackupIsNeverUsed()throws Exception{
        broken.add("index.m3u8");eoMediaBroken.set(true);backupAllowed.set(false);
        try(var relay=relay("index.m3u8")){var response=client.send(HttpRequest.newBuilder(URI.create(relay.url())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(503,response.statusCode());assertEquals(0,paidMedia.get());}
    }
    @Test void liveNeverUsesR2AndFallsBackFromEoToPaid()throws Exception{
        eoMediaBroken.set(true);
        try(var relay=new MediaRelay(base(cdn).resolve("/live/fixture/index.m3u8"),null,base(paid).resolve("/live/fixture/index.m3u8"),base(cdn),base(paid),true,1500,1500)){
            var response=client.send(HttpRequest.newBuilder(URI.create(relay.url())).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().startsWith("#EXTM3U"));assertEquals(0,r2Media.get());assertEquals(1,paidMedia.get());assertEquals("备用 CDN 直播",relay.route());
        }
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cinema.integration",matches="true")
    void nativeParallelSpoolStartsMidMovieBothPlayingAndPaused()throws Exception{
        var dir=java.nio.file.Files.createTempDirectory("cinema-midmovie-native-");
        try{
            var process=new ProcessBuilder("ffmpeg","-y","-hide_banner","-loglevel","error","-stream_loop","11","-i",java.nio.file.Path.of(".tools/test-pattern.mp4").toAbsolutePath().toString(),"-t","48","-c:v","libx264","-preset","ultrafast","-threads","2","-pix_fmt","yuv420p","-r","24","-g","48","-sc_threshold","0","-c:a","aac","-ar","48000","-f","hls","-hls_time","2","-hls_playlist_type","vod","-hls_segment_filename",dir.resolve("segment_%03d.ts").toString(),dir.resolve("index.m3u8").toString()).redirectErrorStream(true).start();
            String log=new String(process.getInputStream().readAllBytes());assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),log);
            try(var stream=java.nio.file.Files.list(dir)){for(var path:stream.toList())files.put(path.getFileName().toString(),java.nio.file.Files.readAllBytes(path));}
            var settings=new ClientSettings();settings.mpvLibraryPath=System.getProperty("cinema.mpv");settings.cacheDirectory=dir.resolve("cache").toString();
            for(boolean play:new boolean[]{true,false}){
                try(var relay=relay("index.m3u8")){
                    relay.enableParallelCache(settings,18000/1000.0);
                    try(var player=new MpvCore(settings,relay.url(),18000,play,0,false,12,true,720,600,1,relay)){
                        long end=System.currentTimeMillis()+12000;
                        while((!player.hasFrames()||player.stats().positionMs()<17500||player.stats().audioDevice().isEmpty())&&System.currentTimeMillis()<end)Thread.sleep(30);
                        assertEquals("",player.error());assertTrue(player.hasFrames(),"Mid-movie startup must decode its probe TS");assertTrue(player.stats().positionMs()>=17500);assertFalse(player.stats().audioDevice().isEmpty());
                        long before=player.stats().renderedFrames(),pos=player.stats().positionMs();Thread.sleep(1000);
                        if(play){assertTrue(player.stats().renderedFrames()>before+10);assertTrue(player.stats().positionMs()>pos+500);}else assertTrue(Math.abs(player.stats().positionMs()-pos)<100);
                        System.out.println("Native mid-movie start: playing="+play+", position="+player.stats().positionMs()+", frames/audio available");
                    }
                }
            }
        }finally{long deadline=System.currentTimeMillis()+3000;while(System.currentTimeMillis()<deadline){try(var paths=java.nio.file.Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(p);break;}catch(java.nio.file.DirectoryNotEmptyException e){Thread.sleep(50);}}}
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cinema.integration",matches="true")
    void nativeParallelSpoolUsesIndividualPaidDeadlineAndKeepsPlayback()throws Exception{
        var dir=java.nio.file.Files.createTempDirectory("cinema-parallel-native-");
        try{
            var process=new ProcessBuilder("ffmpeg","-y","-hide_banner","-loglevel","error","-stream_loop","11","-i",java.nio.file.Path.of(".tools/test-pattern.mp4").toAbsolutePath().toString(),"-t","48","-c:v","libx264","-preset","ultrafast","-threads","2","-pix_fmt","yuv420p","-r","24","-g","48","-sc_threshold","0","-c:a","aac","-ar","48000","-f","hls","-hls_time","2","-hls_playlist_type","vod","-hls_segment_filename",dir.resolve("segment_%03d.ts").toString(),dir.resolve("index.m3u8").toString()).redirectErrorStream(true).start();
            String log=new String(process.getInputStream().readAllBytes());assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),log);
            try(var stream=java.nio.file.Files.list(dir)){for(var path:stream.toList())files.put(path.getFileName().toString(),java.nio.file.Files.readAllBytes(path));}
            broken.add("segment_015.ts");eoBrokenFiles.add("segment_015.ts");
            var settings=new ClientSettings();settings.mpvLibraryPath=System.getProperty("cinema.mpv");settings.cacheDirectory=dir.resolve("cache").toString();
            try(var relay=relay("index.m3u8")){
                relay.enableParallelCache(settings,0);
                try(var player=new MpvCore(settings,relay.url(),0,true,0,false,12,true,720,600,1,relay)){
                    long end=System.currentTimeMillis()+25000,previous=0,last=System.currentTimeMillis(),gap=0;boolean started=false;
                    var field=MpvCore.class.getDeclaredField("handle");field.setAccessible(true);Object handle=null;
                    while(System.currentTimeMillis()<end&&(player.stats().positionMs()<12000||paidDeadlineSegment.get()==0)){
                        var s=player.stats();assertEquals("",player.error());long now=System.currentTimeMillis();
                        if(player.hasFrames()&&!s.audioDevice().isEmpty()){
                            if(!started){started=true;last=now;handle=field.get(player);}
                            if(s.positionMs()!=previous){gap=Math.max(gap,now-last);previous=s.positionMs();last=now;}
                            assertTrue(Math.abs(s.avSyncSeconds())<0.25);
                        }
                        if(s.positionMs()<9500)assertEquals(0,paidDeadlineSegment.get(),"Paid media fetched before missing TS reaches its 20s window");
                        Thread.sleep(30);
                    }
                    assertTrue(started,player.error());assertTrue(paidDeadlineSegment.get()>0);assertTrue(player.stats().positionMs()>=12000);
                    assertEquals(handle,field.get(player));assertFalse(player.stats().audioDevice().isEmpty());assertTrue(gap<1500,"Position sample stall "+gap);assertEquals(6,relay.prefetchPeakR2());assertTrue(relay.segmentDiskBytes()>0);
                    System.out.println("Native parallel TS: peak R2="+relay.prefetchPeakR2()+", paid only after segment deadline, position sample gap="+gap+"ms, same decoder/cache/audio");
                }
            }
        }finally{try(var paths=java.nio.file.Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(p);}}
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cinema.integration",matches="true")
    void nativeCacheDeadlineWaitsForR2AndOnlyThenUsesPaidBackup()throws Exception{
        var dir=java.nio.file.Files.createTempDirectory("cinema-cache-deadline-");
        try{
            var process=new ProcessBuilder("ffmpeg","-y","-hide_banner","-loglevel","error","-stream_loop","11","-i",java.nio.file.Path.of(".tools/test-pattern.mp4").toAbsolutePath().toString(),"-t","48","-c:v","libx264","-preset","ultrafast","-threads","2","-pix_fmt","yuv420p","-r","24","-g","48","-sc_threshold","0","-c:a","aac","-ar","48000","-f","hls","-hls_time","2","-hls_playlist_type","vod","-hls_segment_filename",dir.resolve("segment_%03d.ts").toString(),dir.resolve("index.m3u8").toString()).redirectErrorStream(true).start();
            String log=new String(process.getInputStream().readAllBytes());assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),log);
            try(var stream=java.nio.file.Files.list(dir)){for(var path:stream.toList())files.put(path.getFileName().toString(),java.nio.file.Files.readAllBytes(path));}
            startupSeconds.set(8);emergencySeconds.set(4);broken.add("segment_016.ts");
            var settings=new ClientSettings();settings.mpvLibraryPath=System.getProperty("cinema.mpv");settings.cacheDirectory=dir.resolve("cache").toString();
            try(var relay=relay("index.m3u8");var player=new MpvCore(settings,relay.url(),0,true,0,false,12,true,720,600,1,relay)){
                long deadline=System.currentTimeMillis()+15000;
                while((!player.hasFrames()||player.stats().cacheSeconds()<20||!relay.route().equals("R2 重试")||player.stats().audioDevice().isEmpty())&&System.currentTimeMillis()<deadline)Thread.sleep(30);
                assertTrue(player.hasFrames(),player.error());assertEquals("",player.error());assertTrue(player.stats().cacheSeconds()>20,"Cache "+player.stats().cacheSeconds());assertEquals("R2 重试",relay.route());assertTrue(relay.r2Bytes()>0);assertTrue(relay.cdnBytes()>0);
                int startupRequests=cdnMedia.get(),r2Requests=r2Media.get();eoMediaBroken.set(true);
                var field=MpvCore.class.getDeclaredField("handle");field.setAccessible(true);Object handle=field.get(player);
                long previous=player.stats().positionMs(),last=System.currentTimeMillis(),maxGap=0,end=last+35000;
                while(paidMedia.get()==0&&System.currentTimeMillis()<end){
                    var stats=player.stats();long now=System.currentTimeMillis();if(stats.positionMs()!=previous){maxGap=Math.max(maxGap,now-last);last=now;previous=stats.positionMs();}
                    if(stats.cacheSeconds()>6)assertEquals(startupRequests,cdnMedia.get(),"CDN used before cache deadline");
                    assertFalse(stats.audioDevice().isEmpty());assertTrue(Math.abs(stats.avSyncSeconds())<0.25);assertEquals("",player.error());Thread.sleep(30);
                }
                assertTrue(r2Media.get()>r2Requests,"R2 must keep retrying");assertTrue(paidMedia.get()>0,"Paid CDN should rescue imminent segment");assertTrue(maxGap<1000,"Playback stall "+maxGap);assertEquals(handle,field.get(player));assertTrue(player.stats().cacheDiskBytes()>0);
                long frameCount=player.stats().renderedFrames();Thread.sleep(1500);assertTrue(player.stats().renderedFrames()>frameCount+10);assertFalse(player.stats().audioDevice().isEmpty());
                System.out.println("Native cache deadline: startup CDN requests="+startupRequests+", R2 attempts="+r2Media.get()+", backup="+paidMedia.get()+", max gap="+maxGap+"ms; same decoder/cache/audio");
            }
            // Seek away while an R2 request is still retrying. The old request must
            // not hold the demuxer or turn the replaced read into a playback error.
            eoMediaBroken.set(false);
            try(var relay=relay("index.m3u8");var player=new MpvCore(settings,relay.url(),0,true,0,false,12,true,720,600,1,relay)){
                long end=System.currentTimeMillis()+15000;
                while((player.stats().cacheSeconds()<20||!relay.route().equals("R2 重试")||player.stats().audioDevice().isEmpty())&&System.currentTimeMillis()<end)Thread.sleep(30);
                assertTrue(player.stats().cacheSeconds()>20);assertEquals("R2 重试",relay.route());
                var field=MpvCore.class.getDeclaredField("handle");field.setAccessible(true);Object handle=field.get(player);
                int before=cdnMedia.get();long start=System.currentTimeMillis();player.transport(40000,true,true);
                while((player.stats().positionMs()<39000||player.stats().buffering())&&System.currentTimeMillis()<start+6000)Thread.sleep(30);
                assertTrue(player.stats().positionMs()>=39000,"Uncached seek blocked behind old R2 request");assertEquals("",player.error());assertFalse(player.stats().audioDevice().isEmpty());assertEquals(handle,field.get(player));assertTrue(cdnMedia.get()>before,"New position should quick-start from CDN");
                long frames=player.stats().renderedFrames();Thread.sleep(1000);assertTrue(player.stats().renderedFrames()>frames+10);
                System.out.println("Native uncached seek: old retries canceled; quick-start="+(System.currentTimeMillis()-start)+"ms; same decoder/audio");
            }
        }finally{try(var paths=java.nio.file.Files.walk(dir)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(path);}}
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cinema.integration",matches="true")
    void nativeDecoderKeepsAudioAndCacheAcrossSegmentFailure()throws Exception{
        var dir=java.nio.file.Files.createTempDirectory("cinema-failover-");
        try{
            var process=new ProcessBuilder("ffmpeg","-y","-hide_banner","-loglevel","error","-stream_loop","7","-i",java.nio.file.Path.of(".tools/test-pattern.mp4").toAbsolutePath().toString(),"-t","32","-c:v","libx264","-preset","ultrafast","-threads","2","-pix_fmt","yuv420p","-r","24","-g","24","-sc_threshold","0","-c:a","aac","-ar","48000","-f","hls","-hls_time","1","-hls_playlist_type","vod","-hls_segment_filename",dir.resolve("segment_%03d.ts").toString(),dir.resolve("index.m3u8").toString()).redirectErrorStream(true).start();
            String log=new String(process.getInputStream().readAllBytes());assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),log);
            try(var stream=java.nio.file.Files.list(dir)){for(var path:stream.toList())files.put(path.getFileName().toString(),java.nio.file.Files.readAllBytes(path));}
            broken.add("segment_008.ts");eoMediaBroken.set(true);var settings=new ClientSettings();settings.mpvLibraryPath=System.getProperty("cinema.mpv");settings.cacheDirectory=dir.resolve("cache").toString();
            try(var relay=relay("index.m3u8");var player=new MpvCore(settings,relay.url(),0,true,0,false,12,true,1080,1200,12)){
                long deadline=System.currentTimeMillis()+25000;
                while((!player.hasFrames()||player.stats().buffering()||player.stats().cacheSeconds()<20||player.stats().audioDevice().isEmpty())&&System.currentTimeMillis()<deadline)Thread.sleep(30);
                assertTrue(player.hasFrames(),player.error());assertEquals("",player.error());assertTrue(player.stats().cacheSeconds()>20);assertTrue(relay.r2Bytes()>0);assertTrue(relay.cdnBytes()>0);assertTrue(paidMedia.get()>0);
                var field=MpvCore.class.getDeclaredField("handle");field.setAccessible(true);Object handle=field.get(player);
                long previous=player.stats().positionMs(),last=System.currentTimeMillis(),maxGap=0,end=last+10000;
                while(System.currentTimeMillis()<end){var stats=player.stats();long now=System.currentTimeMillis();if(stats.positionMs()!=previous){maxGap=Math.max(maxGap,now-last);last=now;previous=stats.positionMs();}assertFalse(stats.audioDevice().isEmpty());assertTrue(Math.abs(stats.avSyncSeconds())<0.25);assertEquals("",player.error());Thread.sleep(30);}
                assertTrue(maxGap<1000,"Failover stall "+maxGap);assertEquals(handle,field.get(player));assertTrue(player.stats().cacheDiskBytes()>0);
                System.out.println("Native failover: R2 bytes="+relay.r2Bytes()+", CDN bytes="+relay.cdnBytes()+", max gap="+maxGap+"ms, same decoder/cache, audio="+player.stats().audioDevice());
            }
        }finally{try(var paths=java.nio.file.Files.walk(dir)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(path);}}
    }
}
