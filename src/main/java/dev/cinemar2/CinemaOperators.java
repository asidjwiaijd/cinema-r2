package dev.cinemar2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;

/** Use the native operator list and permission refresh, never console command text. */
public final class CinemaOperators {
    public static int level(MinecraftServer server,NameAndId profile){
        var entry=server.getPlayerList().getOps().get(profile);
        return entry==null?0:entry.permissions().level().id();
    }
    public static JsonArray roster(MinecraftServer server){
        var rows=new JsonArray();
        for(var entry:server.getPlayerList().getOps().getEntries()){
            var profile=entry.getUser();var row=new JsonObject();
            row.addProperty("uuid",profile.id().toString());row.addProperty("name",profile.name());
            row.addProperty("level",entry.permissions().level().id());rows.add(row);
        }
        return rows;
    }
    public static void set(MinecraftServer server,NameAndId profile,int level){
        if(level<0||level>4)throw new IllegalArgumentException("OP 等级必须为 0–4");
        var manager=server.getPlayerList();
        if(level==0)manager.deop(profile);
        else manager.op(profile,Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level))),Optional.empty());
    }
}
