package dev.cinemar2.mixin;
import dev.cinemar2.CinemaProtection;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerExplosion.class)
public abstract class CinemaExplosionMixin {
 @Shadow @Final private ServerLevel level;
 @Inject(method="explode",at=@At("HEAD"),cancellable=true)
 private void cinemaExplosion(CallbackInfoReturnable<Integer> ci){if(CinemaProtection.protectedWorld(level))ci.setReturnValue(0);}
}
