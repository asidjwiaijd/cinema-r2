package dev.cinemar2;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.google.gson.Gson;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public final class CinemaEntity extends BlockEntity {
    public UUID owner;
    public String url = "", onEnd = "hold", lastCommandId = "";
    public Timeline timeline = new Timeline(0, 0, false);
    public int width = 12, liveLatencySeconds = 12, queueIndex = -1, quality = 720, cacheSeconds = 1200, cacheStartSeconds = 20;
    public long revision, durationMs,seekRevision;
    public record PlaybackSample(Packets.Playback report,long receivedMs,String viewer) {}
    public final java.util.Map<UUID,PlaybackSample> playback=new java.util.HashMap<>();
    private long lastPolicyCheck;
    public boolean hasAudio=true;
    public boolean live, pauseWhenEmpty, pausedForEmpty;
    public List<MediaSource> queue = new ArrayList<>();
    public List<String> recentCommandIds = new ArrayList<>();
    public record MediaSource(String url, boolean live, long durationMs, Boolean hasAudio) {}
    public CinemaEntity(BlockPos pos, BlockState state) { super(CinemaMod.ENTITY, pos, state); }
    public String screenId() { return world.getRegistryKey().getValue()+"@"+pos.getX()+","+pos.getY()+","+pos.getZ(); }
    public boolean canControl(PlayerEntity player) { return CinemaProtection.enabled()?player instanceof ServerPlayerEntity p&&CinemaPermissions.canBuild(p):player.hasPermissionLevel(2) || player.getUuid().equals(owner); }
    public int audience() { if (!(world instanceof ServerWorld sw)) return 0; return (int)sw.getPlayers().stream().filter(p->p.squaredDistanceTo(pos.toCenterPos())<=CinemaMod.settings.viewDistance*CinemaMod.settings.viewDistance).count(); }
    public void source(MediaSource source,long now) { if(source==null||!CinemaMod.settings.allows(source.url())) throw new IllegalArgumentException("媒体地址未获准，请检查 HTTPS 域名配置"); url=source.url();live=source.live();hasAudio=source.hasAudio()==null||source.hasAudio();durationMs=Math.max(0,source.durationMs());timeline=new Timeline(0,now,false);pausedForEmpty=false;seekRevision++;playback.clear(); }
    public void changed() { revision++;markDirty();broadcast(); }
    public void send(ServerPlayerEntity player, boolean open, String error) {
        if (!ServerPlayNetworking.canSend(player, Packets.State.ID)) return;
        long now = System.currentTimeMillis();
        ServerPlayNetworking.send(player, new Packets.State(pos,url,timeline.at(now),timeline.playing(),now,revision,false,false,width,error,live,liveLatencySeconds,hasAudio,durationMs,quality,cacheSeconds,cacheStartSeconds,seekRevision));
    }
    public void broadcast() { if(world instanceof ServerWorld sw)for(var p:sw.getPlayers())if(p.squaredDistanceTo(pos.toCenterPos())<=CinemaMod.settings.viewDistance*CinemaMod.settings.viewDistance)send(p,false,""); }
    public static void tick(World world, BlockPos pos, BlockState state, CinemaEntity screen) {
        ManagementBridge.track(screen);
        long now=System.currentTimeMillis();
        screen.maintain(now);
        if(screen.timeline.playing()&&world.getTime()%200==0)screen.markDirty();
        if(world.getTime()%40==0)screen.broadcast();
    }
    // Also called by the bridge: Minecraft may stop ticking block entities in an empty world.
    public void maintain(long now) {
        if(!url.isEmpty()&&!CinemaMod.settings.allows(url)){url="";timeline=new Timeline(0,now,false);changed();}
        if(now-lastPolicyCheck<1000)return;lastPolicyCheck=now;
        if(url.isEmpty())return;
        if(pauseWhenEmpty){if(audience()==0&&timeline.playing()){timeline.pause(now);pausedForEmpty=true;changed();}else if(audience()>0&&pausedForEmpty){timeline.play(now);pausedForEmpty=false;changed();}}
        if(!live&&durationMs>0&&timeline.playing()&&timeline.at(now)>=durationMs){
            if(onEnd.equals("loop")){timeline.seek(0,now);seekRevision++;changed();}
            else if(onEnd.equals("next")&&!queue.isEmpty()){queueIndex=(queueIndex+1)%queue.size();source(queue.get(queueIndex),now);timeline.play(now);changed();}
            else{timeline.pause(now);timeline.seek(Math.max(0,durationMs-100),now);seekRevision++;changed();}
        }
    }
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt,registries);if(owner!=null)nbt.putUuid("owner",owner);
        nbt.putInt("cacheSeconds",cacheSeconds);nbt.putInt("cacheStartSeconds",cacheStartSeconds);nbt.putInt("quality",quality);nbt.putString("url",url);nbt.putLong("position",timeline.at(System.currentTimeMillis()));nbt.putInt("width",width);nbt.putBoolean("hasAudio",hasAudio);nbt.putBoolean("live",live);nbt.putLong("duration",durationMs);nbt.putString("onEnd",onEnd);nbt.putBoolean("pauseWhenEmpty",pauseWhenEmpty);nbt.putInt("latency",liveLatencySeconds);nbt.putString("lastCommandId",lastCommandId);nbt.putString("recentCommands",new Gson().toJson(recentCommandIds));nbt.putString("queue",new Gson().toJson(queue));nbt.putInt("queueIndex",queueIndex);
    }
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt,registries);owner=nbt.containsUuid("owner")?nbt.getUuid("owner"):null;
        cacheSeconds=nbt.contains("cacheSeconds")?Math.max(30,Math.min(3600,nbt.getInt("cacheSeconds"))):1200;cacheStartSeconds=nbt.contains("cacheStartSeconds")?Math.max(1,Math.min(120,nbt.getInt("cacheStartSeconds"))):20;quality=nbt.getInt("quality")==1080?1080:720;url=nbt.getString("url");width=Math.max(4,Math.min(32,nbt.getInt("width")));hasAudio=!nbt.contains("hasAudio")||nbt.getBoolean("hasAudio");live=nbt.getBoolean("live");durationMs=nbt.getLong("duration");onEnd=nbt.getString("onEnd");pauseWhenEmpty=nbt.getBoolean("pauseWhenEmpty");liveLatencySeconds=Math.max(4,Math.min(60,nbt.getInt("latency")));lastCommandId=nbt.getString("lastCommandId");queueIndex=nbt.getInt("queueIndex");
        try{var q=new Gson().fromJson(nbt.getString("queue"),MediaSource[].class);queue=q==null?new ArrayList<>():new ArrayList<>(List.of(q));}catch(Exception e){queue=new ArrayList<>();}
        try{var ids=new Gson().fromJson(nbt.getString("recentCommands"),String[].class);recentCommandIds=ids==null?new ArrayList<>():new ArrayList<>(List.of(ids));}catch(Exception e){recentCommandIds=new ArrayList<>();}
        timeline=new Timeline(nbt.getLong("position"),System.currentTimeMillis(),false);
    }
}
