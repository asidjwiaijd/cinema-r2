package dev.cinemar2.client;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class VodSegmentCacheTest {
    @TempDir Path temp;
    final byte[] content=new byte[]{0x47,1,2,3,4,5};
    HttpServer[] servers=new HttpServer[3];ExecutorService handlers;
    final AtomicIntegerArray counts=new AtomicIntegerArray(3);
    final AtomicBoolean r2Broken=new AtomicBoolean(),eoBroken=new AtomicBoolean();
    final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
    final Set<String> requested=ConcurrentHashMap.newKeySet();
    final AtomicLong bytes=new AtomicLong();
    final AtomicInteger coldDelayMs=new AtomicInteger();
    CountDownLatch release=new CountDownLatch(0);
    final AtomicBoolean permit=new AtomicBoolean(true);
    @BeforeEach void start()throws Exception{
        handlers=Executors.newVirtualThreadPerTaskExecutor();
        for(int i=0;i<3;i++){final int provider=i;servers[i]=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);servers[i].setExecutor(handlers);servers[i].createContext("/",e->{
            int n=provider==0?active.incrementAndGet():0;if(provider==0)peak.accumulateAndGet(n,Math::max);
            try{counts.incrementAndGet(provider);if(provider==0){requested.add(e.getRequestURI().getPath());release.await(5,TimeUnit.SECONDS);if(e.getRequestURI().getPath().endsWith("/segment_0.ts"))Thread.sleep(coldDelayMs.get());}
                if(provider==0&&r2Broken.get()||provider==1&&eoBroken.get()){e.sendResponseHeaders(503,-1);return;}
                e.sendResponseHeaders(200,content.length);e.getResponseBody().write(content);
            }catch(Exception ignored){}finally{e.close();if(provider==0)active.decrementAndGet();}
        });servers[i].start();}
    }
    @AfterEach void stop()throws Exception{release.countDown();for(var s:servers)s.stop(0);handlers.shutdownNow();until(()->{try(var paths=Files.list(temp)){return paths.findAny().isEmpty();}catch(Exception e){return false;}});}
    URI base(int i){return URI.create("http://127.0.0.1:"+servers[i].getAddress().getPort()+"/media/fixture/index.m3u8");}
    VodSegmentCache cache(boolean cdn)throws Exception{return new VodSegmentCache(temp,256L*1024*1024,base(0),base(1),base(2),HttpClient.newHttpClient(),100,1500,()->cdn&&permit.get(),()->cdn&&permit.get(),(p,n)->bytes.addAndGet(n),0);}
    void until(java.util.function.BooleanSupplier check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!check.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);assertTrue(check.getAsBoolean());}
    List<VodSegmentCache.Segment> list(int count){var result=new ArrayList<VodSegmentCache.Segment>();for(int i=0;i<count;i++)result.add(new VodSegmentCache.Segment("segment_"+i+".ts",i*12,12));return result;}
    @Test void sixR2DownloadsRunTogetherAndOnlyFuture600SecondsAreScheduled()throws Exception{
        release=new CountDownLatch(1);
        try(var cache=cache(false)){
            cache.register(list(60));until(()->active.get()==6);assertEquals(6,cache.peakR2());assertEquals(6,peak.get());assertEquals(0,counts.get(1));
            release.countDown();until(()->cache.cached("segment_49.ts"));Thread.sleep(200);
            assertEquals(50,counts.get(0));assertFalse(cache.cached("segment_50.ts"));assertEquals(0,counts.get(2));
            try(var stream=cache.open("segment_20.ts",cache.epoch())){assertArrayEquals(content,stream.readAllBytes());}
            assertEquals(50*content.length,cache.diskBytes());assertEquals(cache.diskBytes(),bytes.get());
        }
    }
    @Test void individualSegmentDeadlinesGateEoAndPaidEvenWhenEoFailsEarly()throws Exception{
        r2Broken.set(true);eoBroken.set(true);
        try(var cache=cache(true)){
            cache.register(List.of(new VodSegmentCache.Segment("late.ts",60,12)));until(()->counts.get(0)>0);Thread.sleep(200);
            assertEquals(0,counts.get(1));assertEquals(0,counts.get(2));
            cache.playback(20,false,false);Thread.sleep(200);assertEquals(0,counts.get(1),"Exactly 40s is outside EO window");
            cache.playback(25,false,false);until(()->counts.get(1)>0);Thread.sleep(200);assertEquals(0,counts.get(2),"EO failure must not advance paid deadline");
            cache.playback(40,false,false);Thread.sleep(200);assertEquals(0,counts.get(2),"Exactly 20s is outside paid window");
            cache.playback(41,false,false);until(()->cache.cached("late.ts"));assertEquals(1,counts.get(2));
            try(var stream=cache.open("late.ts",cache.epoch())){assertArrayEquals(content,stream.readAllBytes());}
            assertTrue(cache.route().contains("阿里云"));
        }
    }
    @Test void slowMissingSegmentDoesNotBlockLaterPrefetchAndDuplicateReadersReuseDisk()throws Exception{
        coldDelayMs.set(3000);
        try(var cache=cache(false)){
            cache.register(list(12));until(()->cache.cached("segment_11.ts"));assertFalse(cache.cached("segment_0.ts"));int fetched=counts.get(0);
            for(int i=0;i<3;i++)try(var stream=cache.open("segment_8.ts",cache.epoch())){assertArrayEquals(content,stream.readAllBytes());}
            Thread.sleep(200);assertEquals(fetched,counts.get(0));assertTrue(cache.peakR2()<=6);
        }
    }
    @Test void seekCancelsOldWaitersAndEvictsOutOfWindowFiles()throws Exception{
        r2Broken.set(true);
        try(var cache=cache(false)){
            cache.register(list(80));until(()->counts.get(0)>=6);long epoch=cache.epoch();
            var waiting=CompletableFuture.runAsync(()->{try(var ignored=cache.open("segment_0.ts",epoch)){fail("Old missing read must be canceled");}catch(Exception e){assertInstanceOf(java.io.InterruptedIOException.class,e);}});
            cache.seek(720);waiting.get(2,TimeUnit.SECONDS);r2Broken.set(false);
            until(()->cache.cached("segment_60.ts"));assertFalse(cache.cached("segment_0.ts"));assertTrue(cache.peakR2()<=6);
            try(var stream=cache.open("segment_60.ts",cache.epoch())){assertArrayEquals(content,stream.readAllBytes());}
        }
    }
    @Test void policyDisabledCdnNeverGetsMediaAndCloseRemovesOwnedSpool()throws Exception{
        r2Broken.set(true);permit.set(false);Path directory;
        try(var cache=cache(true)){
            directory=cache.directory();cache.register(list(6));until(()->counts.get(0)>=6);assertEquals(0,counts.get(1));assertEquals(0,counts.get(2));
            assertEquals(0,cache.diskBytes());
        }
        until(()->!Files.exists(directory));assertTrue(Files.exists(temp));
    }
    @Test void invalidTimelineDoesNotRegisterOrPublishPartialFiles()throws Exception{
        try(var cache=cache(false)){
            assertThrows(java.io.IOException.class,()->cache.register(List.of(new VodSegmentCache.Segment("../escape.ts",0,12))));
            assertThrows(java.io.IOException.class,()->cache.register(List.of(new VodSegmentCache.Segment("same.ts",0,12),new VodSegmentCache.Segment("same.ts",12,12))));
            Thread.sleep(200);assertEquals(0,counts.get(0));assertEquals(0,cache.diskBytes());
        }
    }
    @Test void explicitPastProbeCompletesButFutureReadsDoNotExpand600SecondWindow()throws Exception{
        try(var cache=cache(false)){
            cache.seek(180);cache.register(list(90));until(()->cache.cached("segment_64.ts"));
            assertFalse(cache.cached("segment_0.ts"));assertFalse(cache.cached("segment_65.ts"));
            var probe=CompletableFuture.supplyAsync(()->{try(var in=cache.open("segment_0.ts",cache.epoch())){return in.readAllBytes();}catch(Exception e){throw new RuntimeException(e);}});
            assertArrayEquals(content,probe.get(5,TimeUnit.SECONDS));assertTrue(requested.contains("/media/fixture/segment_0.ts"));
            long epoch=cache.epoch();var future=CompletableFuture.runAsync(()->{try(var in=cache.open("segment_65.ts",epoch)){fail("Future request must wait for the horizon");}catch(java.io.InterruptedIOException expected){}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(300);assertFalse(future.isDone());assertFalse(requested.contains("/media/fixture/segment_65.ts"));
            cache.seek(180);future.get(2,TimeUnit.SECONDS);
        }
    }
}
