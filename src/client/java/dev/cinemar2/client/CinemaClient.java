package dev.cinemar2.client;

import dev.cinemar2.CinemaMod;
import dev.cinemar2.Packets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.util.math.BlockPos;
import java.util.HashMap;
import java.util.Map;

public final class CinemaClient implements ClientModInitializer {
    public static ClientSettings settings;
    public static final Map<BlockPos, View> views = new HashMap<>();
    public static MediaPlayer player;
    public static MediaPlayer pending;
    public static BlockPos active;
    private static Object lastWorld;
    private static long offsetMs, bestRtt = Long.MAX_VALUE, lastPing, lastRestart;
    private static long activeRevision = -1;
    private static long activeSeekRevision=-1,lastReport;
    private static int corrections, recoveries;
    public record View(Packets.State state, long receivedMs) {}
    public void onInitializeClient() {
        settings = ClientSettings.load();
        BlockEntityRendererFactories.register(CinemaMod.ENTITY, CinemaRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(Packets.State.ID, (packet, ctx) -> ctx.client().execute(() -> {
            var old = views.get(packet.pos());
            if (old != null && packet.revision() < old.state().revision()) return;
            views.put(packet.pos(), new View(packet, System.currentTimeMillis()));

        }));
        ClientPlayNetworking.registerGlobalReceiver(Packets.Identity.ID,(packet,ctx)->MediaRelay.identityToken=packet.token());
        ClientPlayNetworking.registerGlobalReceiver(Packets.Clock.ID, (packet, ctx) -> ctx.client().execute(() -> {
            long now = System.currentTimeMillis(), rtt = now - packet.clientMs();
            if (rtt >= 0 && rtt < bestRtt) { bestRtt = rtt; offsetMs = packet.serverMs() - (packet.clientMs() + rtt / 2); }
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {MediaRelay.identityToken="";reset();});
        ClientTickEvents.END_CLIENT_TICK.register(CinemaClient::tick);
    }
    public static long target(Packets.State state) {
        var view = views.get(state.pos());
        long estimatedNow = bestRtt == Long.MAX_VALUE
            ? state.serverMs() + (view == null ? 0 : System.currentTimeMillis() - view.receivedMs())
            : System.currentTimeMillis() + offsetMs;
        return state.positionMs() + (state.playing() ? Math.max(0, estimatedNow - state.serverMs()) : 0);
    }
    public static void command(BlockPos pos, String action, String text, long value) {
        if (ClientPlayNetworking.canSend(Packets.Control.ID)) ClientPlayNetworking.send(new Packets.Control(pos, action, text, value));
    }
    private static void tick(MinecraftClient client) {
        if (client.world != lastWorld) { reset(); lastWorld = client.world; }
        if (client.player == null || client.world == null) return;
        long now = System.currentTimeMillis();
        if (now - lastPing > 10000) { lastPing = now; command(BlockPos.ORIGIN, "clock", "", now); }
        views.entrySet().removeIf(e -> now - e.getValue().receivedMs() > 10000 || !(client.world.getBlockEntity(e.getKey()) instanceof dev.cinemar2.CinemaEntity));
        var selected = views.entrySet().stream().filter(e -> !e.getValue().state().url().isEmpty() && client.player.squaredDistanceTo(e.getKey().toCenterPos()) <= 64 * 64)
            .min(java.util.Comparator.comparingDouble(e -> client.player.squaredDistanceTo(e.getKey().toCenterPos()))).orElse(null);
        if (selected == null) { stop(); return; }
        var pos = selected.getKey(); var state = selected.getValue().state();
        if(state.live()&&!state.playing()&&player!=null&&player.playing&&player.hasFrames()&&pos.equals(active)&&player.url.equals(state.url())){player.freezeLive();activeRevision=state.revision();}
        var wanted = pending != null ? pending : player;
        boolean changed = !pos.equals(active) || wanted == null || !wanted.matches(state);
        boolean edited = state.revision() != activeRevision;
        boolean failed = wanted != null && (!wanted.error().isEmpty()||wanted.stalled(now)) && state.playing() && (state.live() || state.durationMs()==0 || wanted.position()+1000 < state.durationMs());
        if (changed || (failed && now-lastRestart>5000L*(recoveries+1) && recoveries<3)) {
            if (changed) { corrections = 0; recoveries = 0; } else recoveries++;
            if(pending!=null){pending.close();pending=null;}
            active = pos; activeRevision = state.revision(); lastRestart = now;
            activeSeekRevision=state.seekRevision();
            var replacement = new MediaPlayer(state.url(), target(state), state.playing(), settings.volume, state.live(), state.liveLatencySeconds(),state.hasAudio(),state.quality(),state.cacheSeconds(),state.cacheStartSeconds());
            if(player==null)player=replacement;
            else {player.freeze();pending=replacement;}
        } else if(edited&&wanted!=null){
            boolean seek=state.seekRevision()!=activeSeekRevision;
            boolean resume=!wanted.playing&&state.playing();
            wanted.transport(target(state),state.playing(),seek||(!state.live()&&resume&&Math.abs(wanted.position()-target(state))>800));
            activeRevision=state.revision();activeSeekRevision=state.seekRevision();
        }
        wanted = pending != null ? pending : player;
        if(wanted!=null)wanted.setTarget(target(state));
        // Keep the last texture during seek/recovery, and swap only after the new
        // decoder has produced a frame. Ordinary drift is handled by the clock.
        if(pending!=null&&pending.hasFrames()) {if(player!=null)player.close();player=pending;pending=null;}
        if(now-lastReport>=1000&&wanted!=null&&ClientPlayNetworking.canSend(Packets.Playback.ID)){
            lastReport=now;var stats=wanted.bufferStats();String error=wanted.error();
            ClientPlayNetworking.send(new Packets.Playback(pos,state.revision(),Math.max(0,stats.positionMs()),(int)Math.min(Integer.MAX_VALUE,Math.max(0,stats.cacheSeconds()*1000)),stats.buffering()||!wanted.hasFrames(),error.substring(0,Math.min(256,error.length()))));
        }
        if (player != null) {
            double distance = Math.sqrt(client.player.squaredDistanceTo(pos.toCenterPos()));
            player.setVolume(settings.volume * (float)Math.max(0, 1 - distance / 64));
            if(pending!=null)pending.setVolume(settings.volume * (float)Math.max(0, 1 - distance / 64));
        }
    }
    private static void stop() { if (pending != null) pending.close();pending=null;if (player != null) player.close(); player = null; active = null; activeRevision = -1;activeSeekRevision=-1; }
    private static void reset() { stop(); views.clear(); bestRtt = Long.MAX_VALUE; offsetMs = 0; lastPing = 0; }
}
