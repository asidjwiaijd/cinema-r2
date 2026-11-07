package dev.cinemar2.mixin;
import dev.cinemar2.CinemaProtection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.*;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CinemaNetworkMixin {
 @Shadow public ServerPlayer player;
 @Inject(method="handleSetCreativeModeSlot",at=@At("HEAD"),cancellable=true)
 private void cinemaCreative(ServerboundSetCreativeModeSlotPacket packet,CallbackInfo ci){net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet,(ServerGamePacketListenerImpl)(Object)this,player.level());if(CinemaProtection.enabled()&&(CinemaProtection.denyBuild(player)||packet.slotNum()<0||CinemaProtection.dangerous(packet.itemStack())||!packet.itemStack().getComponentsPatch().isEmpty())){player.containerMenu.sendAllDataToRemote();ci.cancel();}}
 @Inject(method="handlePlayerAction",at=@At("HEAD"),cancellable=true)
 private void cinemaDrop(ServerboundPlayerActionPacket packet,CallbackInfo ci){net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet,(ServerGamePacketListenerImpl)(Object)this,player.level());if(CinemaProtection.enabled()&&(packet.getAction()==ServerboundPlayerActionPacket.Action.DROP_ITEM||packet.getAction()==ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)){player.containerMenu.sendAllDataToRemote();ci.cancel();}}
}
