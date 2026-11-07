package dev.cinemar2.mixin;
import dev.cinemar2.CinemaProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerLevel.class)
public abstract class CinemaWorldMixin {
 @Inject(method="addFreshEntity",at=@At("HEAD"),cancellable=true)
 private void cinemaSpawn(Entity entity,CallbackInfoReturnable<Boolean> ci){if(CinemaProtection.denySpawn((ServerLevel)(Object)this,entity))ci.setReturnValue(false);}
}
