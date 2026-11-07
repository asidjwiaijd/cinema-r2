package dev.cinemar2.mixin;
import dev.cinemar2.CinemaProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayerGameMode.class)
public abstract class CinemaInteractionMixin {
 @Shadow protected ServerPlayer player;
 @Inject(method="destroyBlock",at=@At("HEAD"),cancellable=true)
 private void cinemaProtect(BlockPos pos,CallbackInfoReturnable<Boolean> ci){if(CinemaProtection.denyBuild(player))ci.setReturnValue(false);}
}
