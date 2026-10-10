package dev.cinemar2.client;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CINEMA_PUBLIC_TEST",matches="true")
class PublicDeliveryTest {
    ClientSettings settings(){var s=new ClientSettings();s.mpvLibraryPath=System.getProperty("cinema.mpv");s.cacheDirectory=Path.of(".tools/public-cache").toAbsolutePath().toString();return s;}
    @Test void realPaidBackupDecodesWhenBothPreferredRoutesAreUnreachable()throws Exception{
        String path="/media/14250248-d8c9-492e-be66-d9e6ff70e548/index.m3u8";
        URI paid=URI.create("https://live.al.mc.acgzjd.cn"),unreachable=URI.create("https://127.0.0.1:1");
        try(var relay=new MediaRelay(unreachable.resolve(path),unreachable.resolve(path),paid.resolve(path),unreachable,paid,false,1500,1500);
            var player=new MpvCore(settings(),relay.url(),0,true,0,false,12,true,1080,1200,1)){
            long deadline=System.currentTimeMillis()+30000;
            while((!player.hasFrames()||player.stats().audioDevice().isEmpty())&&System.currentTimeMillis()<deadline)Thread.sleep(30);
            assertTrue(player.hasFrames(),player.error());assertEquals("",player.error());assertFalse(player.stats().audioDevice().isEmpty());
            assertEquals(0,relay.r2Bytes());assertTrue(relay.cdnBytes()>0);assertEquals("备用 CDN 回退",relay.route());
            System.out.println("Public paid backup: native frames/audio, preferred routes unreachable; CDN="+relay.cdnBytes()+" bytes");
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="CINEMA_LIVE_TEST",matches="true")
    void realPaidLiveSurvivesEoFailureWithoutR2()throws Exception{
        String path="/live/4ff0f29a-860a-4387-8527-b61c5bb09180/index.m3u8";
        URI paid=URI.create("https://live.al.mc.acgzjd.cn"),unreachable=URI.create("https://127.0.0.1:1");
        try(var relay=new MediaRelay(unreachable.resolve(path),null,paid.resolve(path),unreachable,paid,true,1500,1500);
            var player=new MpvCore(settings(),relay.url(),0,true,0,true,4,true,720,30,1)){
            long deadline=System.currentTimeMillis()+30000;
            while((!player.hasFrames()||player.stats().audioDevice().isEmpty()||player.stats().buffering())&&System.currentTimeMillis()<deadline)Thread.sleep(30);
            assertTrue(player.hasFrames(),player.error());assertEquals("",player.error());assertFalse(player.stats().audioDevice().isEmpty());
            long frames=player.stats().renderedFrames();Thread.sleep(3000);assertTrue(player.stats().renderedFrames()>frames+10);
            assertEquals(0,relay.r2Bytes());assertTrue(relay.cdnBytes()>0);assertEquals("备用 CDN 直播",relay.route());
            System.out.println("Public paid live: R2=0; CDN="+relay.cdnBytes()+" bytes; native frames/audio advancing");
        }
    }
    @Test void realShortVodUsesParallelSpoolWithNativeAudioAndFrames()throws Exception{
        try(var player=new MpvCore(settings(),"https://video.mc.cxmlbt-tec.top/media/14250248-d8c9-492e-be66-d9e6ff70e548/index.m3u8",0,true,0,false,12,true,1080,1200,1)){
            long deadline=System.currentTimeMillis()+30000;
            while((!player.hasFrames()||player.stats().audioDevice().isEmpty())&&System.currentTimeMillis()<deadline)Thread.sleep(30);
            assertTrue(player.hasFrames(),player.error());assertEquals("",player.error());assertFalse(player.stats().audioDevice().isEmpty());
            var field=MpvCore.class.getDeclaredField("relay");field.setAccessible(true);var relay=(MediaRelay)field.get(player);
            assertNotNull(relay);assertTrue(relay.prefetchPeakR2()>0);assertTrue(relay.r2Bytes()+relay.cdnBytes()>0);assertTrue(relay.segmentDiskBytes()>0);
            System.out.println("Public short VOD: native frames/audio, parallel disk spool; peak R2="+relay.prefetchPeakR2()+", R2="+relay.r2Bytes()+", CDN="+relay.cdnBytes()+" bytes");
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="CINEMA_LIVE_TEST",matches="true")
    void realPublicLiveUsesOnlyCdnWithAudioAndAdvancingFrames()throws Exception{
        try(var player=new MpvCore(settings(),"https://live.mc.acgzjd.cn/live/4ff0f29a-860a-4387-8527-b61c5bb09180/index.m3u8",0,true,0,true,4,true,720,30,1)){
            long deadline=System.currentTimeMillis()+25000;
            while((!player.hasFrames()||player.stats().audioDevice().isEmpty()||player.stats().buffering())&&System.currentTimeMillis()<deadline)Thread.sleep(30);
            assertTrue(player.hasFrames(),player.error());assertEquals("",player.error());assertFalse(player.stats().audioDevice().isEmpty());
            long frames=player.stats().renderedFrames();Thread.sleep(3000);assertTrue(player.stats().renderedFrames()>frames+10);assertTrue(Math.abs(player.stats().avSyncSeconds())<0.25);
            var field=MpvCore.class.getDeclaredField("relay");field.setAccessible(true);var relay=(MediaRelay)field.get(player);
            assertNotNull(relay);assertEquals(0,relay.r2Bytes());assertTrue(relay.cdnBytes()>0);
            System.out.println("Public live: CDN="+relay.cdnBytes()+" bytes; R2=0; native frames/audio advancing");
        }
    }
}
