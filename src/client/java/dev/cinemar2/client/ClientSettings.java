package dev.cinemar2.client;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;

public final class ClientSettings {
    public String ffmpegPath = "ffmpeg";
    public float volume = 0.7f;
    public int bufferSeconds = 3; // Legacy setting; libmpv uses compressed cache options below.
    public String mpvLibraryPath = "libmpv-2";
    public int cacheMemoryMB = 512;
    public int cacheDiskGB = 8;
    public boolean diskCache = true;
    public String cacheDirectory = "cinema-cache";
    public int segmentCacheMB = 2048;
    public String tlsCaFile = "";
    public boolean r2Failover = true;
    public String primaryMediaBase = "https://video.mc.cxmlbt-tec.top";
    public String cdnMediaBase = "https://live.mc.acgzjd.cn";
    public String backupMediaBase = "https://live.al.mc.acgzjd.cn";
    public int cdnTimeoutSeconds = 6;
    public int primaryTimeoutSeconds = 6;
    public static ClientSettings load() {
        var file = FabricLoader.getInstance().getConfigDir().resolve("cinemar2-client.json");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (!Files.exists(file)) Files.writeString(file, gson.toJson(new ClientSettings()));
            var config = gson.fromJson(Files.readString(file), ClientSettings.class);
            if (config == null || config.ffmpegPath == null || config.ffmpegPath.isBlank()) throw new IllegalArgumentException("Missing FFmpeg path");
            config.volume = Math.max(0, Math.min(1, config.volume));
            config.bufferSeconds = Math.max(1, Math.min(5, config.bufferSeconds));
            config.cacheMemoryMB=Math.max(64,Math.min(4096,config.cacheMemoryMB));
            config.cacheDiskGB=Math.max(1,Math.min(64,config.cacheDiskGB));
            config.segmentCacheMB=Math.max(256,Math.min(8192,config.segmentCacheMB));
            config.primaryTimeoutSeconds=Math.max(2,Math.min(30,config.primaryTimeoutSeconds));
            config.cdnTimeoutSeconds=Math.max(2,Math.min(30,config.cdnTimeoutSeconds));
            return config;
        } catch (Exception e) { throw new IllegalStateException("Invalid cinemar2-client.json", e); }
    }
}
