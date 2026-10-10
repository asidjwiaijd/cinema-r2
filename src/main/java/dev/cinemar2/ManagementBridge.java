package dev.cinemar2;
import com.google.gson.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.state.property.Properties;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** All world mutations run on the Minecraft server thread; HTTP waits never block ticks. */
public final class ManagementBridge {
    private static final Map<String,CinemaEntity> screens=new ConcurrentHashMap<>();
    private static final LinkedHashMap<String,JsonObject> acks=new LinkedHashMap<>();
    private static final AtomicBoolean inFlight=new AtomicBoolean();
    private static final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Gson gson=new Gson();
    private static long lastPoll;
    private static final Map<UUID,Long> joined=new HashMap<>();
    private static final Map<UUID,String> connections=new HashMap<>(),sentClaims=new HashMap<>();
    private static final LinkedHashSet<String> playerCommands=new LinkedHashSet<>();
    private static volatile long primaryRetryAt;
    private static MinecraftServer active;
    private static String instanceId;
    public static String connection(UUID uuid){return connections.getOrDefault(uuid,"");}
    static { net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{joined.put(handler.player.getUuid(),System.currentTimeMillis());connections.put(handler.player.getUuid(),UUID.randomUUID().toString());});net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{joined.remove(handler.player.getUuid());connections.remove(handler.player.getUuid());sentClaims.remove(handler.player.getUuid());});ServerTickEvents.END_SERVER_TICK.register(ManagementBridge::tick); }
    public static void start(MinecraftServer server){active=server;instanceId=UUID.randomUUID().toString();joined.clear();connections.clear();sentClaims.clear();playerCommands.clear();screens.clear();acks.clear();inFlight.set(false);lastPoll=0;primaryRetryAt=0;}
    public static void stop(MinecraftServer server){active=null;screens.clear();}
    public static void track(CinemaEntity screen){if(active!=null&&screen.getWorld()!=null)screens.put(screen.screenId(),screen);}
    private static String text(JsonObject o,String k,String fallback){return o.has(k)?o.get(k).getAsString():fallback;}
    private static int number(JsonObject o,String k,int fallback){return o.has(k)?o.get(k).getAsInt():fallback;}
    private static void tick(MinecraftServer server){
        if(active!=server||CinemaMod.settings.managementUrl.isBlank()||CinemaMod.settings.managementToken.length()<32)return;
        long now=System.currentTimeMillis();if(now-lastPoll<250||!inFlight.compareAndSet(false,true))return;lastPoll=now;
        JsonArray list=new JsonArray();
        screens.entrySet().removeIf(e->{var s=e.getValue();return s.getWorld()==null||!s.getWorld().isChunkLoaded(s.getPos())||s.getWorld().getBlockEntity(s.getPos())!=s;});
        for(var s:screens.values()){s.maintain(now);var o=new JsonObject();var p=s.getPos();o.addProperty("id",s.screenId());o.addProperty("dimension",s.getWorld().getRegistryKey().getValue().toString());o.addProperty("x",p.getX());o.addProperty("y",p.getY());o.addProperty("z",p.getZ());o.addProperty("width",s.width);o.addProperty("quality",s.quality);o.addProperty("cacheSeconds",s.cacheSeconds);o.addProperty("cacheStartSeconds",s.cacheStartSeconds);o.addProperty("facing",s.getCachedState().get(Properties.HORIZONTAL_FACING).asString());o.addProperty("url",s.url);o.addProperty("positionMs",s.timeline.at(now));o.addProperty("playing",s.timeline.playing());o.addProperty("live",s.live);o.addProperty("durationMs",s.durationMs);o.addProperty("audience",s.audience());o.addProperty("sampleTime",now);o.addProperty("revision",s.revision);o.addProperty("seekRevision",s.seekRevision);o.add("playback",playback(s,now));list.add(o);}
        JsonObject body=new JsonObject();body.addProperty("serverId",CinemaMod.settings.serverId);body.addProperty("instanceId",instanceId);body.addProperty("serverTime",now);body.add("screens",list);body.add("players",players(server));body.add("bans",bans(server));body.add("cinemaAdmins",gson.toJsonTree(CinemaPermissions.roles()));body.add("arena",ArenaBuilder.status());body.addProperty("maxPlayers",server.getMaxPlayerCount());body.add("acks",gson.toJsonTree(acks.values()));
        try{
            poll(body).whenComplete((response,error)->{if(active!=server){inFlight.set(false);return;}server.execute(()->{try{if(error!=null||response.statusCode()!=200)return;var reply=JsonParser.parseString(response.body()).getAsJsonObject();acks.clear();if(reply.has("playerClaims"))for(var el:reply.getAsJsonArray("playerClaims")){var claim=el.getAsJsonObject();var uuid=UUID.fromString(claim.get("uuid").getAsString());var player=server.getPlayerManager().getPlayer(uuid);String token=claim.get("token").getAsString();if(player!=null&&!token.equals(sentClaims.get(uuid))&&net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,Packets.Identity.ID)){net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,new Packets.Identity(token));sentClaims.put(uuid,token);}}if(reply.has("mediaHosts")){CinemaMod.settings.allowedMediaHosts=new ArrayList<>();for(var h:reply.getAsJsonArray("mediaHosts"))CinemaMod.settings.allowedMediaHosts.add(h.getAsString());}for(var el:reply.getAsJsonArray("commands")){var command=el.getAsJsonObject();String id=text(command,"id","");JsonObject ack=new JsonObject();ack.addProperty("id",id);try{apply(server,command);}catch(Exception e){ack.addProperty("error",e.getMessage()==null?"执行失败":e.getMessage());}acks.put(id,ack);}}catch(Exception ignored){}finally{inFlight.set(false);}});});
        }catch(Exception e){inFlight.set(false);}
    }
    private static CompletableFuture<HttpResponse<String>> sendPoll(String base,JsonObject body){
        var request=HttpRequest.newBuilder(URI.create(base+"/api/bridge/poll")).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+CinemaMod.settings.managementToken).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
        return http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
    }
    private static CompletableFuture<HttpResponse<String>> poll(JsonObject body){
        String primary=CinemaMod.settings.managementUrl,backup=CinemaMod.settings.managementFallbackUrl;
        if(!backup.isBlank()&&System.currentTimeMillis()<primaryRetryAt)return sendPoll(backup,body);
        return sendPoll(primary,body).handle((response,error)->{
            if(error==null&&response.statusCode()==200){primaryRetryAt=0;return CompletableFuture.completedFuture(response);}
            if(!backup.isBlank()){primaryRetryAt=System.currentTimeMillis()+30000;return sendPoll(backup,body);}
            if(error!=null)return CompletableFuture.<HttpResponse<String>>failedFuture(error);
            return CompletableFuture.completedFuture(response);
        }).thenCompose(value->value);
    }
    private static JsonArray playback(CinemaEntity screen,long now){
        var result=new JsonArray();
        screen.playback.entrySet().removeIf(e->now-e.getValue().receivedMs()>4000||!(screen.getWorld() instanceof net.minecraft.server.world.ServerWorld sw)||sw.getPlayerByUuid(e.getKey())==null);
        for(var entry:screen.playback.entrySet()){
            var sample=entry.getValue();var report=sample.report();if(report.revision()!=screen.revision)continue;
            var row=new JsonObject();row.addProperty("uuid",entry.getKey().toString());row.addProperty("viewer",sample.viewer());row.addProperty("positionMs",report.positionMs());row.addProperty("cacheSeconds",report.cacheMs()/1000.0);row.addProperty("buffering",report.buffering());row.addProperty("error",report.error());row.addProperty("receivedAt",sample.receivedMs());result.add(row);
        }return result;
    }
    private static JsonArray players(MinecraftServer server){
        var list=new JsonArray();for(var p:server.getPlayerManager().getPlayerList()){
            var o=new JsonObject();var id=p.getUuid();o.addProperty("uuid",id.toString());o.addProperty("name",p.getName().getString());o.addProperty("ping",p.networkHandler.getLatency());o.addProperty("dimension",p.getServerWorld().getRegistryKey().getValue().toString());o.addProperty("x",p.getX());o.addProperty("y",p.getY());o.addProperty("z",p.getZ());o.addProperty("gameMode",p.interactionManager.getGameMode().asString());o.addProperty("joinedAt",joined.computeIfAbsent(id,k->System.currentTimeMillis()));o.addProperty("connectionId",connections.computeIfAbsent(id,k->UUID.randomUUID().toString()));o.addProperty("cinemaAdmin",CinemaPermissions.registered(id));o.addProperty("adminActive",CinemaPermissions.canBuild(p));list.add(o);
        }return list;
    }
    private static long banFileStamp=-1;
    private static JsonArray banRows=new JsonArray();
    private static JsonArray bans(MinecraftServer server){
        var nativeList=server.getPlayerManager().getUserBanList();var file=nativeList.getFile();
        if(file.lastModified()!=banFileStamp){
            try{banRows=file.exists()?JsonParser.parseString(java.nio.file.Files.readString(file.toPath())).getAsJsonArray():new JsonArray();banFileStamp=file.lastModified();}catch(Exception ignored){}
        }
        var list=new JsonArray();for(var el:banRows){try{var b=el.getAsJsonObject();var uuid=UUID.fromString(b.get("uuid").getAsString());var profile=new com.mojang.authlib.GameProfile(uuid,text(b,"name",""));var entry=nativeList.get(profile);if(entry==null)continue;var o=new JsonObject();o.addProperty("uuid",uuid.toString());o.addProperty("name",profile.getName());o.addProperty("reason",entry.getReason());list.add(o);}catch(Exception ignored){}}return list;
    }
    private static void applyPlayer(MinecraftServer server,JsonObject c){
        String id=text(c,"id","");if(playerCommands.contains(id))return;
        if(!c.has("expiresAt")||c.get("expiresAt").getAsLong()<System.currentTimeMillis())throw new IllegalArgumentException("玩家指令已过期");
        UUID uuid=UUID.fromString(text(c,"playerUuid",""));var manager=server.getPlayerManager();var player=manager.getPlayer(uuid);String reason=text(c,"reason","影院管理员操作");
        switch(text(c,"action","")){
            case "player:admin-grant" -> {if(player==null)throw new IllegalArgumentException("玩家已经离线");CinemaPermissions.grant(uuid,text(c,"connectionId",""),connection(uuid));}
            case "player:admin-revoke" -> CinemaPermissions.revoke(uuid);
            case "player:kick" -> {if(player==null)throw new IllegalArgumentException("玩家已经离线");player.networkHandler.disconnect(net.minecraft.text.Text.literal(reason));}
            case "player:ban" -> {var profile=player!=null?player.getGameProfile():new com.mojang.authlib.GameProfile(uuid,text(c,"playerName",""));manager.getUserBanList().add(new net.minecraft.server.BannedPlayerEntry(profile,new Date(),"Cinema 管理台",null,reason));if(player!=null)player.networkHandler.disconnect(net.minecraft.text.Text.literal(reason));}
            case "player:unban" -> {var profile=new com.mojang.authlib.GameProfile(uuid,text(c,"playerName",""));manager.getUserBanList().remove(profile);}
            case "player:teleport" -> {
                if(player==null)throw new IllegalArgumentException("玩家已经离线");var world=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(text(c,"dimension","minecraft:overworld"))));if(world==null)throw new IllegalArgumentException("维度不存在");
                BlockPos safe=null;int x=number(c,"x",0),y=number(c,"y",100),z=number(c,"z",0);
                search:for(int radius=1;radius<=6;radius++)for(int dy=2;dy>=-2;dy--)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){if(Math.abs(dx)!=radius&&Math.abs(dz)!=radius)continue;var pos=new BlockPos(x+dx,y+dy,z+dz);if(world.isChunkLoaded(pos)&&world.isInBuildLimit(pos.up())&&world.getBlockState(pos).isAir()&&world.getBlockState(pos.up()).isAir()&&world.getBlockState(pos.down()).isSolidBlock(world,pos.down())){safe=pos;break search;}}
                if(safe==null)throw new IllegalArgumentException("屏幕附近没有安全落脚点，请先搭建平台");player.teleport(world,safe.getX()+0.5,safe.getY(),safe.getZ()+0.5,player.getYaw(),player.getPitch());
            }
            default -> throw new IllegalArgumentException("未知玩家动作");
        }
        playerCommands.add(id);if(playerCommands.size()>200)playerCommands.removeFirst();
    }
    private static int queuePosition(CinemaEntity screen){for(int i=0;i<screen.queue.size();i++)if(screen.queue.get(i).url().equals(screen.url))return i;return -1;}
    private static void apply(MinecraftServer server,JsonObject c){
        String action=text(c,"action","");String screenId=text(c,"screenId","");CinemaEntity s=screens.get(screenId);
        if(action.startsWith("player:")){applyPlayer(server,c);return;}
        if(action.equals("arena:build")){String id=text(c,"id","");if(playerCommands.contains(id))return;if(!c.has("expiresAt")||c.get("expiresAt").getAsLong()<System.currentTimeMillis())throw new IllegalArgumentException("场景指令已过期");ArenaBuilder.start(server);playerCommands.add(id);return;}
        if(action.equals("create")){var key=RegistryKey.of(RegistryKeys.WORLD,Identifier.of(text(c,"dimension","minecraft:overworld")));var world=server.getWorld(key);if(world==null)throw new IllegalArgumentException("维度不存在");var pos=new BlockPos(number(c,"x",0),number(c,"y",100),number(c,"z",0));if(!world.isInBuildLimit(pos)||Math.abs(pos.getX())>29999984||Math.abs(pos.getZ())>29999984)throw new IllegalArgumentException("坐标超出世界边界");if(world.getBlockEntity(pos) instanceof CinemaEntity existing)s=existing;else{if(!world.getBlockState(pos).isAir())throw new IllegalArgumentException("该位置已有方块，请选择空位置");var facing=Direction.byName(text(c,"facing","south"));if(facing==null||facing.getAxis().isVertical())facing=Direction.SOUTH;world.setBlockState(pos,CinemaMod.SCREEN.getDefaultState().with(Properties.HORIZONTAL_FACING,facing),3);s=(CinemaEntity)world.getBlockEntity(pos);}if(s==null)throw new IllegalArgumentException("屏幕创建失败");s.width=Math.max(4,Math.min(32,number(c,"width",12)));track(s);}
        if(s==null)throw new IllegalArgumentException("屏幕所在区块未加载");
        String id=text(c,"id","");if(s.lastCommandId.equals(id)||s.recentCommandIds.contains(id))return;long now=System.currentTimeMillis();
        switch(action){
            case "create" -> {}
            case "load", "take" -> {s.source(gson.fromJson(c.get("source"),CinemaEntity.MediaSource.class),now);s.queueIndex=queuePosition(s);if(action.equals("take"))s.timeline.play(now);}
            case "play" -> {if(!s.url.isEmpty()){s.timeline.play(now);s.pausedForEmpty=false;}}
            case "pause" -> {s.timeline.pause(now);s.pausedForEmpty=false;}
            case "seek" -> {if(s.live)throw new IllegalArgumentException("直播不能按点播时间跳转");long target=Math.max(0,Math.min(86400000,c.get("positionMs").getAsLong()));if(s.durationMs>0)target=Math.min(target,Math.max(0,s.durationMs-100));s.timeline.seek(target,now);s.seekRevision++;}
            case "stop" -> {s.url="";s.timeline=new Timeline(0,now,false);s.pausedForEmpty=false;}
            case "playlist" -> {var q=gson.fromJson(c.get("queue"),CinemaEntity.MediaSource[].class);for(var source:q)if(!CinemaMod.settings.allows(source.url()))throw new IllegalArgumentException("队列媒体域名不允许");s.queue=new ArrayList<>(List.of(q));s.queueIndex=queuePosition(s);}
            case "configure" -> {s.width=Math.max(4,Math.min(32,number(c,"width",s.width)));var direction=Direction.byName(text(c,"facing","south"));if(direction!=null&&!direction.getAxis().isVertical())s.getWorld().setBlockState(s.getPos(),s.getCachedState().with(Properties.HORIZONTAL_FACING,direction),3);if(c.has("policy")){var p=c.getAsJsonObject("policy");String end=text(p,"onEnd","hold");s.onEnd=List.of("hold","loop","next").contains(end)?end:"hold";s.pauseWhenEmpty=p.has("pauseWhenEmpty")&&p.get("pauseWhenEmpty").getAsBoolean();s.liveLatencySeconds=Math.max(4,Math.min(60,number(p,"liveLatencySeconds",12)));s.quality=number(p,"quality",720)==1080?1080:720;s.cacheSeconds=Math.max(30,Math.min(3600,number(p,"cacheSeconds",1200)));s.cacheStartSeconds=Math.max(1,Math.min(120,number(p,"cacheStartSeconds",20)));}}
            case "remove" -> {s.getWorld().removeBlock(s.getPos(),false);screens.remove(screenId);}
            default -> throw new IllegalArgumentException("未知播控动作");
        }
        s.lastCommandId=id;s.recentCommandIds.add(id);if(s.recentCommandIds.size()>100)s.recentCommandIds.removeFirst();
        if(action.equals("playlist")){s.markDirty();s.broadcast();}else s.changed();
    }
}
