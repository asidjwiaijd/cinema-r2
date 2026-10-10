package dev.cinemar2;
import com.google.gson.JsonParser;
import dev.cinemar2.client.CinemaClient;
import dev.cinemar2.client.ClientSettings;
import dev.cinemar2.client.MpvCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
class ConsoleDecodeTest {
 @Test @EnabledIfEnvironmentVariable(named="CINEMA_CONSOLE_TEST",matches="true")
 void clientDecoderReadsActualVodAndLiveHls() throws Exception {
  var client=HttpClient.newHttpClient();
  var state=JsonParser.parseString(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:3210/api/state")).build(),HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
  var settings=new ClientSettings();settings.mpvLibraryPath=System.getProperty("cinema.mpv");
  String vod="";for(var r:state.getAsJsonArray("resources"))if(r.getAsJsonObject().get("kind").getAsString().equals("vod")&&r.getAsJsonObject().get("status").getAsString().equals("ready")){vod=r.getAsJsonObject().get("url").getAsString();break;}
  assertFalse(vod.isEmpty());
  try(var player=new MpvCore(settings,vod,1000,false,0,false,12,true,1080,1200,1)){awaitFrame(player);assertEquals(1000,player.stats().positionMs());}
  assertTrue(state.getAsJsonObject("live").get("ready").getAsBoolean());
  try(var player=new MpvCore(settings,state.getAsJsonObject("live").get("url").getAsString(),0,true,0,true,8,true,720,30,1)){awaitFrame(player);Thread.sleep(1800);assertTrue(player.stats().positionMs()>500,player.error());assertEquals("",player.error());player.freeze();long paused=player.stats().positionMs();Thread.sleep(200);assertEquals(paused,player.stats().positionMs());assertTrue(player.hasFrames());}
 }
 private static void awaitFrame(MpvCore player)throws Exception{long deadline=System.currentTimeMillis()+18000;while(!player.hasFrames()&&player.error().isEmpty()&&System.currentTimeMillis()<deadline)Thread.sleep(50);assertTrue(player.hasFrames(),player.error());}
}
