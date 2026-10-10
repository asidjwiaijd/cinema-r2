package dev.cinemar2;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import java.nio.file.*;
import java.util.*;

/** Registered role persists; offline identities never automatically gain a live grant. */
public final class CinemaPermissions {
    private static final Set<UUID> registered=new HashSet<>();
    private static final Map<UUID,String> active=new HashMap<>();
    private static Path file(){return FabricLoader.getInstance().getConfigDir().resolve("cinemar2-admins.json");}
    public static void load(){registered.clear();active.clear();try{if(Files.exists(file()))for(var el:JsonParser.parseString(Files.readString(file())).getAsJsonArray())registered.add(UUID.fromString(el.getAsString()));}catch(Exception e){throw new IllegalStateException("影院管理员配置无效",e);}}
    private static void save(){try{Path tmp=file().resolveSibling(file().getFileName()+".tmp");Files.writeString(tmp,new Gson().toJson(registered.stream().map(UUID::toString).sorted().toList()));Files.move(tmp,file(),StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new IllegalStateException("无法保存影院管理员",e);}}
    public static void grant(UUID uuid,String expected,String connection){if(expected.isBlank()||!expected.equals(connection))throw new IllegalArgumentException("玩家连接已改变，请重新授权");registered.add(uuid);save();active.put(uuid,connection);}
    public static void revoke(UUID uuid){registered.remove(uuid);active.remove(uuid);save();}
    public static void disconnect(UUID uuid){active.remove(uuid);}
    public static boolean registered(UUID uuid){return registered.contains(uuid);}
    public static boolean canBuild(ServerPlayerEntity player){return registered.contains(player.getUuid())&&Objects.equals(active.get(player.getUuid()),ManagementBridge.connection(player.getUuid()))&&active.containsKey(player.getUuid());}
    public static List<String> roles(){return registered.stream().map(UUID::toString).sorted().toList();}
}
