package dev.cinemar2;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import dev.cinemar2.client.CinemaClient;
import dev.cinemar2.client.ClientSettings;
import dev.cinemar2.client.MpvCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.net.ssl.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class MediaDecodeTest {
    @Test
    @EnabledIfSystemProperty(named="cinema.integration", matches="true")
    void actualLibmpvDecodesSeekedFrameOverTrustedHttps() throws Exception {
        char[] password = "cinema-test".toCharArray();
        var keys = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(Path.of(".tools/test-tls.p12"))) { keys.load(stream, password); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
        var ssl = SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(), null, null);
        var server = HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        byte[] movie = Files.readAllBytes(Path.of(".tools/test-pattern.mp4"));
        server.createContext("/media/test.mp4", exchange -> {
            int start = 0, end = movie.length - 1;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                String[] bounds = range.substring(6).split("-", -1);
                start = Integer.parseInt(bounds[0]);
                if (!bounds[1].isEmpty()) end = Math.min(end, Integer.parseInt(bounds[1]));
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + movie.length);
            }
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(range == null ? 200 : 206, end - start + 1);
            try (var out = exchange.getResponseBody()) { out.write(movie, start, end - start + 1); }
            catch (java.io.IOException ignored) { /* Decoder can close once the requested frame arrives. */ }
        });
        server.start(); var settings=new ClientSettings();settings.mpvLibraryPath=System.getProperty("cinema.mpv");
        var ca=Files.createTempFile("cinema-test-ca-",".pem");
        var cert=keys.getCertificate(keys.aliases().nextElement());
        Files.writeString(ca,"-----BEGIN CERTIFICATE-----\n"+java.util.Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(cert.getEncoded())+"\n-----END CERTIFICATE-----\n");
        settings.tlsCaFile=ca.toString();
        try (var decoder = new MpvCore(settings,"https://127.0.0.1:" + server.getAddress().getPort() + "/media/test.mp4", 1000, false, 0, false,12,true,720,1200,1)) {
            long deadline = System.currentTimeMillis() + 15000;
            while (!decoder.hasFrames() && decoder.error().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertTrue(decoder.hasFrames(), decoder.error());
            assertEquals(1000, decoder.stats().positionMs()); assertEquals("", decoder.error());
        } finally { server.stop(0); executor.shutdownNow(); Files.deleteIfExists(ca); }
    }
}
