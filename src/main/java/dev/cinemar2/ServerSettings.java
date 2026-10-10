package dev.cinemar2;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

public final class ServerSettings {
    public List<String> allowedMediaHosts = List.of("media.example.com");
    public int viewDistance = 64;
    public String managementUrl = "";
    public String managementFallbackUrl = "";
    public String managementToken = "";
    public String serverId = "local-cinema";
    public boolean allowLocalMedia = false;
    public boolean protectCinema = true;
    public static ServerSettings load() {
        var file = FabricLoader.getInstance().getConfigDir().resolve("cinemar2-server.json");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (!Files.exists(file)) Files.writeString(file, gson.toJson(new ServerSettings()));
            var config = gson.fromJson(Files.readString(file), ServerSettings.class);
            if (config == null || config.allowedMediaHosts == null) throw new IllegalArgumentException("Missing hosts");
            config.viewDistance = Math.max(16, Math.min(96, config.viewDistance));
            if(!config.managementFallbackUrl.isBlank()){
                var fallback=URI.create(config.managementFallbackUrl);
                if(!"https".equals(fallback.getScheme())||fallback.getHost()==null||fallback.getUserInfo()!=null||fallback.getQuery()!=null||fallback.getFragment()!=null||!(fallback.getPath().isEmpty()||fallback.getPath().equals("/")))throw new IllegalArgumentException("管理备用地址必须为 HTTPS 域名根地址");
                config.managementFallbackUrl=fallback.toString().replaceAll("/$","");
            }
            return config;
        } catch (Exception e) { throw new IllegalStateException("Invalid cinemar2-server.json", e); }
    }
    public boolean allows(String url) {
        try {
            var uri = URI.create(url);
            boolean local = allowLocalMedia && "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 3210;
            return url.length() <= 2048 && (local || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getUserInfo() == null && uri.getFragment() == null
                && (local || uri.getPort() == -1 || uri.getPort() == 443)
                && (local || allowedMediaHosts.stream().anyMatch(h -> h.equalsIgnoreCase(uri.getHost())))
                && uri.getPath() != null && uri.getPath().matches("/[A-Za-z0-9_./-]+\\.(mp4|m3u8)")
                && !uri.getPath().contains("..") && !uri.getRawPath().contains("%");
        } catch (Exception e) { return false; }
    }
}
