package dev.cinemar2;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
    public int volume=100;
    public boolean suppressMusic=true;
    public boolean live, pauseWhenEmpty, pausedForEmpty;
    public List<MediaSource> queue = new ArrayList<>();
    public List<String> recentCommandIds = new ArrayList<>();
    public record MediaSource(String url, boolean live, long durationMs, Boolean hasAudio) {}
    public CinemaEntity(BlockPos pos, BlockState state) { super(CinemaMod.ENTITY, pos, state); }
    public String screenId() { return level.dimension().identifier()+"@"+worldPosition.getX()+","+worldPosition.getY()+","+worldPosition.getZ(); }
    public boolean canControl(Player player) { return CinemaProtection.enabled()?player instanceof ServerPlayer p&&CinemaPermissions.canBuild(p):player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) || player.getUUID().equals(owner); }
    public int audience() { if (!(level instanceof ServerLevel sw)) return 0; return (int)sw.players().stream().filter(p->p.distanceToSqr(worldPosition.getCenter())<=CinemaMod.settings.viewDistance*CinemaMod.settings.viewDistance).count(); }
    public void source(MediaSource source,long now) { if(source==null||!CinemaMod.settings.allows(source.url())) throw new IllegalArgumentException("媒体地址未获准，请检查 HTTPS 域名配置"); url=source.url();live=source.live();hasAudio=source.hasAudio()==null||source.hasAudio();durationMs=Math.max(0,source.durationMs());timeline=new Timeline(0,now,false);pausedForEmpty=false;seekRevision++;playback.clear(); }
    public void changed() { revision++;setChanged();broadcast(); }
    public void send(ServerPlayer player, boolean open, String error) {
        if (!ServerPlayNetworking.canSend(player, Packets.State.ID)) return;
        long now = System.currentTimeMillis();
        ServerPlayNetworking.send(player, new Packets.State(worldPosition,url,timeline.at(now),timeline.playing(),now,revision,false,false,width,error,live,liveLatencySeconds,hasAudio,durationMs,quality,cacheSeconds,cacheStartSeconds,seekRevision));
        if(ServerPlayNetworking.canSend(player,Packets.Audio.ID))
            ServerPlayNetworking.send(player,new Packets.Audio(worldPosition,volume,suppressMusic));
        if(ServerPlayNetworking.canSend(player,Packets.Geometry.ID))
            ServerPlayNetworking.send(player,new Packets.Geometry(worldPosition,getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING).toYRot(),CinemaMod.settings.viewDistance));
    }
    public void broadcast() { if(level instanceof ServerLevel sw)for(var p:sw.players())if(p.distanceToSqr(worldPosition.getCenter())<=CinemaMod.settings.viewDistance*CinemaMod.settings.viewDistance)send(p,false,""); }
    public static void tick(Level world, BlockPos pos, BlockState state, CinemaEntity screen) {
        ManagementBridge.track(screen);
        long now=System.currentTimeMillis();
        screen.maintain(now);
        if(screen.timeline.playing()&&world.getGameTime()%200==0)screen.setChanged();
        if(world.getGameTime()%40==0)screen.broadcast();
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
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput nbt) {
        super.saveAdditional(nbt);if(owner!=null)nbt.store("owner",net.minecraft.core.UUIDUtil.CODEC,owner);
        nbt.putInt("volume",volume);nbt.putBoolean("suppressMusic",suppressMusic);
        nbt.putInt("cacheSeconds",cacheSeconds);nbt.putInt("cacheStartSeconds",cacheStartSeconds);nbt.putInt("quality",quality);nbt.putString("url",url);nbt.putLong("position",timeline.at(System.currentTimeMillis()));nbt.putInt("width",width);nbt.putBoolean("hasAudio",hasAudio);nbt.putBoolean("live",live);nbt.putLong("duration",durationMs);nbt.putString("onEnd",onEnd);nbt.putBoolean("pauseWhenEmpty",pauseWhenEmpty);nbt.putInt("latency",liveLatencySeconds);nbt.putString("lastCommandId",lastCommandId);nbt.putString("recentCommands",new Gson().toJson(recentCommandIds));nbt.putString("queue",new Gson().toJson(queue));nbt.putInt("queueIndex",queueIndex);
    }
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput nbt) {
        super.loadAdditional(nbt);owner=nbt.read("owner",net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        volume=Math.clamp(nbt.getIntOr("volume",100),0,100);suppressMusic=nbt.getBooleanOr("suppressMusic",true);
        cacheSeconds=Math.max(30,Math.min(3600,nbt.getIntOr("cacheSeconds",1200)));cacheStartSeconds=Math.max(1,Math.min(120,nbt.getIntOr("cacheStartSeconds",20)));quality=nbt.getIntOr("quality",0)==1080?1080:720;url=nbt.getStringOr("url","");width=Math.max(4,Math.min(100,nbt.getIntOr("width",0)));hasAudio=nbt.getBooleanOr("hasAudio",true);live=nbt.getBooleanOr("live",false);durationMs=nbt.getLongOr("duration",0L);onEnd=nbt.getStringOr("onEnd","");pauseWhenEmpty=nbt.getBooleanOr("pauseWhenEmpty",false);liveLatencySeconds=Math.max(4,Math.min(60,nbt.getIntOr("latency",0)));lastCommandId=nbt.getStringOr("lastCommandId","");queueIndex=nbt.getIntOr("queueIndex",0);
        try{var q=new Gson().fromJson(nbt.getStringOr("queue",""),MediaSource[].class);queue=q==null?new ArrayList<>():new ArrayList<>(List.of(q));}catch(Exception e){queue=new ArrayList<>();}
        try{var ids=new Gson().fromJson(nbt.getStringOr("recentCommands",""),String[].class);recentCommandIds=ids==null?new ArrayList<>():new ArrayList<>(List.of(ids));}catch(Exception e){recentCommandIds=new ArrayList<>();}
        timeline=new Timeline(nbt.getLongOr("position",0L),System.currentTimeMillis(),false);
    }
}
