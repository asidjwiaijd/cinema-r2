package dev.cinemar2.mixin;
import dev.cinemar2.CinemaProtection;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerWorld.class)
public abstract class CinemaWorldMixin {
 @Inject(method="spawnEntity",at=@At("HEAD"),cancellable=true)
 private void cinemaSpawn(Entity entity,CallbackInfoReturnable<Boolean> ci){if(CinemaProtection.denySpawn((ServerWorld)(Object)this,entity))ci.setReturnValue(false);}
}
