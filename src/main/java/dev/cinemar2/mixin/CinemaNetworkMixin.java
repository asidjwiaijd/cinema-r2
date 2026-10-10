package dev.cinemar2.mixin;
import dev.cinemar2.CinemaProtection;
import net.minecraft.server.network.*;
import net.minecraft.network.packet.c2s.play.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerPlayNetworkHandler.class)
public abstract class CinemaNetworkMixin {
 @Shadow public ServerPlayerEntity player;
 @Inject(method="onCreativeInventoryAction",at=@At("HEAD"),cancellable=true)
 private void cinemaCreative(CreativeInventoryActionC2SPacket packet,CallbackInfo ci){net.minecraft.network.NetworkThreadUtils.forceMainThread(packet,(ServerPlayNetworkHandler)(Object)this,player.getServerWorld());if(CinemaProtection.enabled()&&(CinemaProtection.denyBuild(player)||packet.slot()<0||CinemaProtection.dangerous(packet.stack())||!packet.stack().getComponentChanges().isEmpty())){player.currentScreenHandler.syncState();ci.cancel();}}
 @Inject(method="onPlayerAction",at=@At("HEAD"),cancellable=true)
 private void cinemaDrop(PlayerActionC2SPacket packet,CallbackInfo ci){net.minecraft.network.NetworkThreadUtils.forceMainThread(packet,(ServerPlayNetworkHandler)(Object)this,player.getServerWorld());if(CinemaProtection.enabled()&&(packet.getAction()==PlayerActionC2SPacket.Action.DROP_ITEM||packet.getAction()==PlayerActionC2SPacket.Action.DROP_ALL_ITEMS)){player.currentScreenHandler.syncState();ci.cancel();}}
}
