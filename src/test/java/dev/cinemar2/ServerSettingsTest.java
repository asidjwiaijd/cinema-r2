package dev.cinemar2;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ServerSettingsTest {
 @Test void nestedHlsAllowsOnlyApprovedHostAndSafePaths(){
  var s=new ServerSettings();s.allowedMediaHosts=List.of("media.example");
  assertTrue(s.allows("https://media.example/media/film-1/index.m3u8"));
  assertFalse(s.allows("https://evil.example/media/a.mp4"));
  assertFalse(s.allows("https://user:secret@media.example/a.mp4"));
  assertFalse(s.allows("https://media.example/media/../a.mp4"));
  assertFalse(s.allows("https://media.example/media/%2e%2e/a.mp4"));
  assertFalse(s.allows("https://media.example/a.mp4#secret"));
 }
 @Test void loopbackRequiresExplicitLocalModeAndConsolePort(){
  var s=new ServerSettings();assertFalse(s.allows("http://127.0.0.1:3210/live/stream/index.m3u8"));
  s.allowLocalMedia=true;assertTrue(s.allows("http://127.0.0.1:3210/live/stream/index.m3u8"));
  assertFalse(s.allows("http://127.0.0.1:9999/a.mp4"));assertFalse(s.allows("http://192.168.1.2:3210/a.mp4"));
 }
}
