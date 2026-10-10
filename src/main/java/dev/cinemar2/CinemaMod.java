package dev.cinemar2;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CinemaMod implements ModInitializer {
    public static final CinemaBlock SCREEN = new CinemaBlock(AbstractBlock.Settings.create().strength(2).nonOpaque());
    public static BlockEntityType<CinemaEntity> ENTITY;
    public static ServerSettings settings;
    private static final Map<UUID, Long> lastAction = new HashMap<>();
    private static final Map<UUID, Long> lastClock = new HashMap<>();
    public static Identifier id(String path) { return Identifier.of("cinemar2", path); }
    public void onInitialize() {
        Registry.register(Registries.BLOCK, id("screen"), SCREEN);
        Registry.register(Registries.ITEM, id("screen"), new BlockItem(SCREEN, new Item.Settings()));
        ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, id("screen"), FabricBlockEntityTypeBuilder.create(CinemaEntity::new, SCREEN).build());
        PayloadTypeRegistry.playC2S().register(Packets.Control.ID, Packets.Control.CODEC);
        PayloadTypeRegistry.playC2S().register(Packets.Playback.ID, Packets.Playback.CODEC);
        PayloadTypeRegistry.playS2C().register(Packets.State.ID, Packets.State.CODEC);
        PayloadTypeRegistry.playS2C().register(Packets.Identity.ID, Packets.Identity.CODEC);
        PayloadTypeRegistry.playS2C().register(Packets.Clock.ID, Packets.Clock.CODEC);
        CinemaProtection.initialize();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> { settings = ServerSettings.load(); CinemaPermissions.load();lastAction.clear(); lastClock.clear(); ManagementBridge.start(server); });
        ServerLifecycleEvents.SERVER_STARTED.register(CinemaProtection::configure);
        ServerLifecycleEvents.SERVER_STOPPING.register(ManagementBridge::stop);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { lastAction.remove(handler.player.getUuid()); lastClock.remove(handler.player.getUuid()); });
        ServerPlayNetworking.registerGlobalReceiver(Packets.Playback.ID,(packet,ctx)->ctx.server().execute(()->{
            var player=ctx.player();long now=System.currentTimeMillis();
            if(!(player.getServerWorld().getBlockEntity(packet.pos()) instanceof CinemaEntity screen)||player.squaredDistanceTo(packet.pos().toCenterPos())>64*64||packet.revision()!=screen.revision||packet.positionMs()<0||packet.positionMs()>86400000||packet.cacheMs()<0)return;
            var old=screen.playback.get(player.getUuid());if(old!=null&&now-old.receivedMs()<800)return;
            screen.playback.put(player.getUuid(),new CinemaEntity.PlaybackSample(packet,now,player.getName().getString()));
        }));
        ServerPlayNetworking.registerGlobalReceiver(Packets.Control.ID, (packet, ctx) -> ctx.server().execute(() -> {
            var player = ctx.player(); var now = System.currentTimeMillis();
            if (packet.action().equals("clock")) {
                if (now - lastClock.getOrDefault(player.getUuid(), 0L) >= 1000) {
                    lastClock.put(player.getUuid(), now);
                    ServerPlayNetworking.send(player, new Packets.Clock(packet.value(), now));
                }
                return;
            }
            // Playback commands are accepted only from the authenticated management bridge.

        }));
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClient && world.getBlockEntity(pos) instanceof CinemaEntity screen && !screen.canControl(player)) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
    }
}
