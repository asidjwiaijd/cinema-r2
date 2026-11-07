package dev.cinemar2;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CinemaMod implements ModInitializer {
    public static final CinemaBlock SCREEN = new CinemaBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK,id("screen"))).strength(2).noOcclusion());
    public static BlockEntityType<CinemaEntity> ENTITY;
    public static ServerSettings settings;
    private static final Map<UUID, Long> lastAction = new HashMap<>();
    private static final Map<UUID, Long> lastClock = new HashMap<>();
    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath("cinemar2", path); }
    public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, id("screen"), SCREEN);
        Registry.register(BuiltInRegistries.ITEM, id("screen"), new BlockItem(SCREEN, new Item.Properties().setId(ResourceKey.create(Registries.ITEM,id("screen")))));
        ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("screen"), FabricBlockEntityTypeBuilder.create(CinemaEntity::new, SCREEN).build());
        PayloadTypeRegistry.serverboundPlay().register(Packets.Control.ID, Packets.Control.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Packets.Playback.ID, Packets.Playback.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packets.State.ID, Packets.State.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packets.Audio.ID, Packets.Audio.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packets.Geometry.ID, Packets.Geometry.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packets.Identity.ID, Packets.Identity.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packets.Clock.ID, Packets.Clock.CODEC);
        CinemaProtection.initialize();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> { settings = ServerSettings.load(); CinemaPermissions.load();lastAction.clear(); lastClock.clear(); ManagementBridge.start(server); });
        ServerLifecycleEvents.SERVER_STARTED.register(CinemaProtection::configure);
        ServerLifecycleEvents.SERVER_STOPPING.register(ManagementBridge::stop);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { lastAction.remove(handler.player.getUUID()); lastClock.remove(handler.player.getUUID()); });
        ServerPlayNetworking.registerGlobalReceiver(Packets.Playback.ID,(packet,ctx)->ctx.server().execute(()->{
            var player=ctx.player();long now=System.currentTimeMillis();
            if(!(player.level().getBlockEntity(packet.pos()) instanceof CinemaEntity screen)||player.distanceToSqr(packet.pos().getCenter())>(double)settings.viewDistance*settings.viewDistance||packet.revision()!=screen.revision||packet.positionMs()<0||packet.positionMs()>86400000||packet.cacheMs()<0)return;
            var old=screen.playback.get(player.getUUID());if(old!=null&&now-old.receivedMs()<800)return;
            screen.playback.put(player.getUUID(),new CinemaEntity.PlaybackSample(packet,now,player.getName().getString()));
        }));
        ServerPlayNetworking.registerGlobalReceiver(Packets.Control.ID, (packet, ctx) -> ctx.server().execute(() -> {
            var player = ctx.player(); var now = System.currentTimeMillis();
            if (packet.action().equals("clock")) {
                if (now - lastClock.getOrDefault(player.getUUID(), 0L) >= 1000) {
                    lastClock.put(player.getUUID(), now);
                    ServerPlayNetworking.send(player, new Packets.Clock(packet.value(), now));
                }
                return;
            }
            // Playback commands are accepted only from the authenticated management bridge.

        }));
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() && world.getBlockEntity(pos) instanceof CinemaEntity screen && !screen.canControl(player)) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
    }
}
