package dev.cinemar2.mixin;
import dev.cinemar2.CinemaProtection;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayerInteractionManager.class)
public abstract class CinemaInteractionMixin {
 @Shadow protected ServerPlayerEntity player;
 @Inject(method="tryBreakBlock",at=@At("HEAD"),cancellable=true)
 private void cinemaProtect(BlockPos pos,CallbackInfoReturnable<Boolean> ci){if(CinemaProtection.denyBuild(player))ci.setReturnValue(false);}
}
