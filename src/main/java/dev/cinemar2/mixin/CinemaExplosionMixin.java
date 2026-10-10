package dev.cinemar2.mixin;
import dev.cinemar2.CinemaProtection;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Explosion.class)
public abstract class CinemaExplosionMixin {
 @Shadow @Final private World world;
 @Inject(method="collectBlocksAndDamageEntities",at=@At("HEAD"),cancellable=true)
 private void cinemaExplosion(CallbackInfo ci){if(CinemaProtection.protectedWorld(world))ci.cancel();}
 @Inject(method="affectWorld",at=@At("HEAD"),cancellable=true)
 private void cinemaBlast(boolean particles,CallbackInfo ci){if(CinemaProtection.protectedWorld(world))ci.cancel();}
}
